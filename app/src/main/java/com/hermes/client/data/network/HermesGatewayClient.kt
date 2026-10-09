package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

class GatewayRpcException(val code: Int, message: String) : Exception(message)

data class BackoffPolicy(
    val baseMs: Long = 500,
    val factor: Double = 2.0,
    val maxMs: Long = 10_000,
) {
    fun delayFor(attempt: Int): Long =
        min(maxMs, (baseMs * factor.pow(attempt)).toLong())
}

open class HermesGatewayClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    // suspend so gated mode can fetch a fresh single-use WS ticket (an HTTP round trip) before
    // each connect; loopback mode returns immediately.
    private val wsUrlProvider: suspend () -> String,
) {
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    private val transportLock = Any()
    private val serverRequests = ServerRequestCoordinator(json)
    val pendingServerRequests = serverRequests.pending
    private var handlerAvailable = false
    private var socketReady = false
    private var closedGeneration = -1
    private var advertised: Boolean? = null
    private val capabilityIds = mutableSetOf<Long>()

    /** Register real renderer support, not sheet visibility. Call before connect/session RPCs.
     * Changes on a ready socket are written synchronously before any subsequent call(). */
    fun setServerRequestHandlerAvailable(available: Boolean) = synchronized(transportLock) {
        handlerAvailable = available
        if (socketReady && !advertise(ws)) onSocketClosed(generation.get(), "capability send failed")
    }

    fun respondServerRequest(request: PendingServerRequest, result: JsonObject): RespondOutcome = synchronized(transportLock) {
        serverRequests.respond(request, result, socketReady) { ws?.send(it) ?: false }
    }

    fun failServerRequest(request: PendingServerRequest): RespondOutcome = synchronized(transportLock) {
        serverRequests.fail(request) { ws?.send(it) ?: false }
    }

    /** Batch clarify only. Accepted locks are restored from server snapshots, never retried here. */
    suspend fun lockServerClarification(request: PendingServerRequest, questionId: String, answer: String): ClarificationLockOutcome {
        val id: Long
        val deferred: CompletableDeferred<JsonElement>
        synchronized(transportLock) {
            if (!serverRequests.isCurrent(request)) return ClarificationLockOutcome.Stale
            serverRequests.validateBatchQuestion(request, questionId)
            if (!socketReady) return ClarificationLockOutcome.Disconnected
            id = nextId.getAndIncrement()
            deferred = CompletableDeferred()
            pending[id] = deferred
            val sent = ws?.send(RpcRequest(id, "clarify.lock", buildJsonObject {
                put("request_id", request.id); put("question_id", questionId); put("answer", answer)
            }).encode(json)) ?: false
            if (!sent) {
                pending.remove(id)
                return ClarificationLockOutcome.Disconnected
            }
        }
        val response = try {
            deferred.await()
        } catch (e: GatewayRpcException) {
            return synchronized(transportLock) {
                when {
                    request.generation != generation.get() -> ClarificationLockOutcome.Stale
                    !socketReady -> ClarificationLockOutcome.Disconnected
                    else -> throw e
                }
            }
        } finally { pending.remove(id, deferred) }
        return synchronized(transportLock) {
            if (!serverRequests.isCurrent(request)) return@synchronized ClarificationLockOutcome.Stale
            val obj = response as? JsonObject ?: throw GatewayRpcException(0, "Invalid clarification lock response")
            val status = (obj["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (status) {
                "expired" -> {
                    serverRequests.settle(request)
                    ClarificationLockOutcome.Expired
                }
                "ok" -> {
                    val remaining = (obj["remaining"] as? JsonArray)?.map {
                        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                            ?: throw GatewayRpcException(0, "Invalid clarification lock response")
                    } ?: throw GatewayRpcException(0, "Invalid clarification lock response")
                    remaining.forEach { serverRequests.validateBatchQuestion(request, it) }
                    if (remaining.isEmpty()) serverRequests.settle(request)
                    ClarificationLockOutcome.Ok(remaining)
                }
                else -> throw GatewayRpcException(0, "Invalid clarification lock response")
            }
        }
    }

    private fun advertise(socket: WebSocket?): Boolean {
        if (advertised == handlerAvailable) return true
        val id = nextId.getAndIncrement()
        capabilityIds.add(id)
        val sent = socket?.send(RpcRequest(id, "client.capabilities", buildJsonObject {
            put("server_requests", handlerAvailable)
        }).encode(json)) ?: false
        if (sent) advertised = handlerAvailable else capabilityIds.remove(id)
        return sent
    }

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile private var ws: WebSocket? = null
    @Volatile protected var manuallyClosed = false
    private val attempt = AtomicInteger(0)
    // Monotonic socket generation. Each openSocket() bumps it; a socket's callbacks are
    // ignored once a newer socket has been opened, so an in-flight backoff reopen can never
    // race a manual reconnectNow() into two live sockets.
    private val generation = AtomicInteger(0)

    // Readiness gate: awaited by call() before sending RPCs.
    // Recreated (uncompleted) on each openSocket(); completed when gateway.ready arrives;
    // completed exceptionally when socket closes/fails or close() is called.
    @Volatile private var readyGate: CompletableDeferred<Unit> = CompletableDeferred()

    private companion object {
        const val READY_TIMEOUT_MS = 15_000L
    }

    fun connect(): Unit = synchronized(transportLock) {
        // Idempotent: multiple owners (the foreground service, view models, etc.) may all call
        // connect() on this shared singleton. If a socket is already open, or a connect/backoff
        // reconnect is already in flight, this must be a no-op — otherwise a second openSocket()
        // would leak a duplicate live WebSocket that the generation check only shadows, never
        // closes. reconnectNow() intentionally bypasses this guard to force a fresh socket.
        val cur = _state.value
        if (cur is ConnectionState.Connecting || cur is ConnectionState.Connected ||
            cur is ConnectionState.Reconnecting
        ) {
            DebugLog.log("ws", "connect() no-op — already $cur")
            return@synchronized
        }
        manuallyClosed = false
        openSocket()
    }

    protected fun openSocket(): Unit = synchronized(transportLock) {
        val gen = generation.incrementAndGet()
        socketReady = false
        advertised = null
        capabilityIds.clear()
        serverRequests.reset(gen)
        readyGate.completeExceptionally(GatewayRpcException(0, "socket replaced"))
        failAllPending("socket replaced")
        DebugLog.log("ws", "opening socket (gen=$gen)")
        // Install a fresh, uncompleted readiness gate for this new socket attempt.
        readyGate = CompletableDeferred()
        _state.value = ConnectionState.Connecting
        // Resolve the URL off the calling thread: gated mode mints a WS ticket (HTTP) here. A
        // failure (e.g. login/ticket error) routes through onSocketClosed so backoff retries.
        scope.launch {
            val url = try {
                wsUrlProvider()
            } catch (e: Exception) {
                DebugLog.log("ws", "ws url/ticket failed (gen=$gen)")
                onSocketClosed(gen, "ws url failed")
                return@launch
            }
            // A newer socket may have superseded this one — or the client was closed — while we
            // fetched the ticket. Either way, don't open a now-orphaned socket.
            if (gen != generation.get() || manuallyClosed) return@launch
            val request = Request.Builder().url(url).build()
            synchronized(transportLock) {
                if (gen == generation.get() && !manuallyClosed) ws = okHttp.newWebSocket(request, makeListener(gen))
            }
        }
    }

    /**
     * Force an immediate reconnect, bypassing any pending backoff wait. The previous socket is
     * cancelled; because openSocket() bumps the generation first, the old socket's close callback
     * is ignored and cannot schedule a competing reopen.
     */
    fun reconnectNow(): Unit = synchronized(transportLock) {
        manuallyClosed = false
        attempt.set(0)
        val old = ws
        openSocket()
        old?.cancel()
    }

    private fun makeListener(gen: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Do NOT set state to Connected here. Wait for gateway.ready event.
            // Do NOT reset the attempt counter here either.
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(transportLock) {
            if (gen != generation.get() || manuallyClosed || (!socketReady && readyGate.isCompleted)) return@synchronized
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val msg = try { parseInbound(json, line) } catch (_: Exception) {
                    DebugLog.log("ws", "invalid RPC frame")
                    return@forEach
                }
                when (msg) {
                    is RpcServerRequest -> serverRequests.receive(msg, false, handlerAvailable) { webSocket.send(it) }
                    is RpcResult -> {
                        if (!capabilityIds.remove(msg.id)) {
                            pending.remove(msg.id)?.let { deferred ->
                                serverRequests.replay(msg.result, handlerAvailable) { webSocket.send(it) }
                                deferred.complete(msg.result)
                            }
                        }
                    }
                    is RpcErrorReply -> {
                        if (!capabilityIds.remove(msg.id)) {
                            DebugLog.log("ws", "rpc#${msg.id} error ${msg.error.code}")
                            pending.remove(msg.id)?.completeExceptionally(GatewayRpcException(msg.error.code, "Gateway RPC failed"))
                        }
                    }
                    is RpcEvent -> {
                        if (msg.event.type == "gateway.ready" && !socketReady) {
                            ws = webSocket
                            if (!advertise(webSocket)) {
                                onSocketClosed(gen, "capability send failed")
                                return@forEach
                            }
                            socketReady = true
                            attempt.set(0)
                            _state.value = ConnectionState.Connected
                            readyGate.complete(Unit)
                        }
                        if (msg.event.type == "request.cancel") serverRequests.cancel(msg.event.payload)
                        _events.tryEmit(msg.event)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onSocketClosed(gen, "connection failed")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            onSocketClosed(gen, "closed")
            webSocket.close(code, "closed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onSocketClosed(gen, "closed")
        }
    }

    protected open fun onSocketClosed(gen: Int, reason: String): Unit = synchronized(transportLock) {
        // A newer socket has superseded this one (e.g. reconnectNow()) — ignore its death.
        if (gen != generation.get() || closedGeneration == gen) return@synchronized
        closedGeneration = gen
        DebugLog.log("ws", "socket closed (gen=$gen)")
        socketReady = false
        serverRequests.reset(gen)
        ws = null
        // Fail any call() that is currently awaiting readiness so it throws immediately.
        readyGate.completeExceptionally(GatewayRpcException(0, reason))
        failAllPending(reason)
        if (manuallyClosed) {
            _state.value = ConnectionState.Disconnected
            return@synchronized
        }
        _state.value = ConnectionState.Reconnecting
        val delayMs = backoff.delayFor(attempt.getAndIncrement())
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!manuallyClosed && gen == generation.get()) openSocket()
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(GatewayRpcException(0, reason))
        }
    }

    suspend fun call(method: String, params: JsonObject): JsonElement {
        // Wait until gateway.ready has been received before sending any RPC.
        // Bounded wait: if the server never sends gateway.ready, throw after READY_TIMEOUT_MS.
        // The await() happens BEFORE registering in `pending`, so a timeout here never leaks
        // a pending entry.
        try {
            withTimeout(READY_TIMEOUT_MS) { readyGate.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayRpcException(0, "gateway readiness timeout")
        }
        val deferred = synchronized(transportLock) {
            if (!socketReady || !readyGate.isCompleted) throw GatewayRpcException(0, "not connected")
            val id = nextId.getAndIncrement()
            val d = CompletableDeferred<JsonElement>()
            pending[id] = d
            val sent = ws?.send(RpcRequest(id, method, params).encode(json)) ?: false
            if (!sent) {
                pending.remove(id)
                throw GatewayRpcException(0, "not connected")
            }
            d
        }
        return deferred.await()
    }

    fun close(): Unit = synchronized(transportLock) {
        manuallyClosed = true
        socketReady = false
        serverRequests.reset(generation.incrementAndGet())
        // Fail any call() awaiting readiness so it throws immediately rather than hanging.
        readyGate.completeExceptionally(GatewayRpcException(0, "client closing"))
        failAllPending("client closing")
        ws?.close(1000, "client closing")
        ws = null
        _state.value = ConnectionState.Disconnected
    }

    /** Immediately cancel the underlying socket (no graceful close handshake). */
    internal fun cancelNow(): Unit = synchronized(transportLock) {
        manuallyClosed = true
        socketReady = false
        serverRequests.reset(generation.incrementAndGet())
        readyGate.completeExceptionally(GatewayRpcException(0, "client cancelled"))
        failAllPending("client cancelled")
        ws?.cancel()
        ws = null
        _state.value = ConnectionState.Disconnected
    }
}
