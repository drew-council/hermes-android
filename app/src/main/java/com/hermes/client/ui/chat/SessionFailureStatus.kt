package com.hermes.client.ui.chat

import com.hermes.client.data.network.GatewayRpcException
import com.hermes.client.data.network.HermesApiException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException

/** Static stage/category and numeric protocol codes only; exception messages can contain secrets. */
internal fun sessionFailureStatus(stage: String, failure: Exception): String = "$stage: " + when (failure) {
    is HermesApiException -> "HTTP ${failure.code}"
    is GatewayRpcException -> "RPC ${failure.code}"
    is SerializationException -> "invalid-response"
    is TimeoutCancellationException -> "timeout"
    is java.io.IOException -> "transport"
    else -> "unavailable"
}
