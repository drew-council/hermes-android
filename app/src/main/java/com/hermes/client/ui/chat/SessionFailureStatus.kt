package com.hermes.client.ui.chat

import com.hermes.client.data.network.GatewayRpcException
import com.hermes.client.data.network.GatewayRefusalReason
import com.hermes.client.data.network.HermesApiException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException

/** Static stage/category and numeric protocol codes only; exception messages can contain secrets. */
internal fun sessionFailureStatus(stage: String, failure: Throwable): String = "$stage: " + when (failure) {
    is HermesApiException -> "HTTP ${failure.code}"
    is GatewayRpcException -> "RPC ${failure.code}" + when (failure.reason) {
        GatewayRefusalReason.SESSION_NOT_OWNED -> " · session owned by another live client"
        GatewayRefusalReason.MAX_CONCURRENT_SESSIONS -> " · concurrency limit reached"
        GatewayRefusalReason.SESSION_COORDINATION_UNAVAILABLE -> " · session coordination unavailable"
        null -> ""
    }
    is SerializationException -> "invalid-response"
    is TimeoutCancellationException -> "timeout"
    is java.io.IOException -> "transport"
    else -> "unavailable"
}
