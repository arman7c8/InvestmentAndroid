package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanClientTest {
    @Test
    fun pkceChallengeMatchesRfc7636Vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ChatGptPlanClient.pkceChallenge(verifier)
        )
    }

    @Test
    fun planScopeMustBeExplicitlyGranted() {
        val granted = ChatGptPlanClient.parseScopes(
            "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        )
        assertTrue(ChatGptPlanClient.PLAN_SCOPE in granted)
    }

    @Test
    fun directFlowUsesNoApiKeyConstants() {
        assertEquals(
            "https://auth.openai.com/api/accounts/authorize",
            ChatGptPlanClient.AUTHORIZE_URL
        )
        assertEquals("dynamic_agent_client", ChatGptPlanClient.DYNAMIC_CLIENT_ID)
        assertEquals("https://api.openai.com/v1", ChatGptPlanClient.RESOURCE)
    }
}
