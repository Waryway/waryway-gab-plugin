package com.waryway.gab

import kotlin.test.Test
import kotlin.test.assertTrue

class WarywayGabPluginTest {
    @Test
    fun `plugin loads basic classes`() {
        // Smoke test that core classes are loadable
        assertTrue(com.waryway.gab.settings.WarywayGabSettings::class.java.name.isNotEmpty())
    }
}