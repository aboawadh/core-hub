package hub.core.android.parity

import hub.core.android.ui.screens.McpOAuthRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.McpOAuthState
import hub.core.client.model.McpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A remote MCP server's OAuth sign-in on the row (DECISIONS §122), as the web's page offers it. iOS's McpOAuthRulesTests checks the same. */
class McpOAuthTest {
    private val json = Serializer.kotlinxSerializationJson

    private fun server(transport: String, config: String, oauth: String?) = json.decodeFromString(
        McpServer.serializer(),
        """{"name":"clickup","transport":"$transport","enabled":true,"connected":false,"tools":[],"error":null,"config":$config,
            "updated_at":"2026-09-27T10:00:00Z"${oauth?.let { ""","oauth":$it""" } ?: ""}}""",
    )

    @Test
    fun readsTheSignInStateAndOffersItOnlyWhereHermesCanSignIn() {
        val oauth = server("http", """{"url":"https://mcp.clickup.example/mcp","auth":"oauth"}""", """{"required":true,"status":"not_connected","expires_at":null}""")
        assertEquals(McpOAuthState.Status.NOT_CONNECTED, oauth.oauth?.status)
        assertTrue(McpOAuthRules.offers(oauth))
        // A server that signs in with its own header, an older hub, a process: nothing to offer.
        assertFalse(McpOAuthRules.offers(server("http", """{"url":"https://x.example","headers":{"Authorization":"[stored]"}}""", """{"required":false,"status":"not_connected"}""")))
        assertNull(server("http", """{"url":"https://x.example"}""", null).oauth)
        assertFalse(McpOAuthRules.offers(server("http", """{"url":"https://x.example"}""", null)))
        assertFalse(McpOAuthRules.offers(server("stdio", """{"command":"npx"}""", """{"required":false,"status":"not_connected"}""")))
        val expired = server("sse", """{"url":"https://x.example","headers":{"Authorization":"[stored]"}}""", """{"required":false,"status":"expired","expires_at":"2026-09-27T09:00:00Z"}""")
        assertTrue(McpOAuthRules.offers(expired))
    }
}
