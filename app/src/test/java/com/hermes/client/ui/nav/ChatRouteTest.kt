package com.hermes.client.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRouteTest {
    @Test fun owner_profile_is_encoded_in_saved_route() {
        assertEquals("chat/stored?profile=team%20%2F%20fixture", chatRoute("stored", "team / fixture"))
        assertEquals("chat/stored", chatRoute("stored"))
    }
    @Test fun deep_link_retains_owner_and_legacy_links_remain_supported() {
        assertEquals("chat/stored?profile=fixture", deepLinkRouteFor("hermes://chat/stored?profile=fixture"))
        assertEquals("chat/stored", deepLinkRouteFor("hermes://chat/stored"))
    }
}
