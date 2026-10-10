package com.hermes.client.ui.nav

/** Save the session owner with the durable navigation identity, independently of global focus. */
fun chatRoute(sessionId: String, profile: String? = null): String {
    fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return "chat/${encode(sessionId)}" + if (profile.isNullOrBlank()) "" else "?profile=${encode(profile)}"
}
const val CHAT_ROUTE_PATTERN = "chat/{id}?profile={profile}"
