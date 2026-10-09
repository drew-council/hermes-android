package com.hermes.client.data.network

/** Android 17 (API 37) requires an explicit runtime grant for LAN sockets. */
internal fun localNetworkAccessAllowed(sdkInt: Int, permissionGranted: Boolean): Boolean =
    sdkInt < 37 || permissionGranted
