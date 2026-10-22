package com.pixelforge.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {
    private val free = Provider("OpenRouter", "u", "k", listOf("a:free", "b:free"), freeOnly = true)
    private val paid = Provider("OpenRouter", "u", "k", listOf("a:free", "gpt-x"), freeOnly = false)
    private val omni = Provider("OmniRoute", "u", "", listOf("auto"), trusted = true)
    private val omniUntrusted = Provider("OmniRoute", "u", "", listOf("auto"))

    @Test
    fun publicProjectUsesEverything() {
        assertEquals(listOf(free, omniUntrusted), Privacy.apply(listOf(free, omniUntrusted), false, false).providers)
    }

    @Test
    fun privateWithoutConsentBlocksFreeAndUntrusted() {
        assertTrue(Privacy.apply(listOf(free, omniUntrusted), true, false).providers.isEmpty())
    }

    @Test
    fun privateWithoutConsentKeepsTrustedAndPaidOnly() {
        val r = Privacy.apply(listOf(free, paid, omni), true, false).providers
        assertEquals(listOf("OpenRouter", "OmniRoute"), r.map { it.name })
        assertEquals(listOf("gpt-x"), r[0].models)
    }

    @Test
    fun privateWithConsentUsesEverything() {
        assertEquals(listOf(free), Privacy.apply(listOf(free), true, true).providers)
    }
}
