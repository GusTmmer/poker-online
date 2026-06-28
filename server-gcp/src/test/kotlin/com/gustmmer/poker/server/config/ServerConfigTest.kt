package com.gustmmer.poker.server.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerConfigTest {

    private fun config(jwt: String, internal: String) = ServerConfig(
        port = 8080,
        jwtSecret = jwt,
        firestoreProjectId = "p",
        internalToken = internal,
    )

    @Test
    fun `assertSecretsAreSet rejects the dev defaults`() {
        assertThrows<IllegalStateException> {
            config(ServerConfig.DEFAULT_JWT_SECRET, "real-internal").assertSecretsAreSet()
        }
        assertThrows<IllegalStateException> {
            config("real-jwt", ServerConfig.DEFAULT_INTERNAL_TOKEN).assertSecretsAreSet()
        }
    }

    @Test
    fun `assertSecretsAreSet accepts real secrets`() {
        config("real-jwt", "real-internal").assertSecretsAreSet() // does not throw
    }
}
