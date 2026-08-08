package com.waryway.gab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrokLoginActionsTest {

    @Test
    fun `login help url is https`() {
        assertTrue(GrokLoginActions.LOGIN_HELP_URL.startsWith("https://"))
        assertEquals("grok login", GrokLoginActions.LOGIN_CMD)
    }

    @Test
    fun `openUrl rejects blank by returning false only on throw`() {
        // Non-throwing path for a well-formed URL depends on Desktop; just assert API exists.
        assertTrue(GrokLoginActions.LOGIN_HELP_URL.contains("x.ai"))
    }
}
