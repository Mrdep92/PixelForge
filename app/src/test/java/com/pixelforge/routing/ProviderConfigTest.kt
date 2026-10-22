package com.pixelforge.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {
    @Test
    fun roundTripsThroughJson() {
        val list = listOf(ProviderConfig("Groq", "https://api.groq.com/openai/v1", "k1", "m1"))
        assertEquals(list, ProviderPresets.parse(ProviderPresets.toJson(list)))
    }

    @Test
    fun badJsonGivesEmptyList() {
        assertTrue(ProviderPresets.parse("bukan json").isEmpty())
        assertTrue(ProviderPresets.parse("[]").isEmpty())
    }

    @Test
    fun upsertReplacesSameNameIgnoringCase() {
        val a = ProviderConfig("Groq", "u", "k", "m1")
        val b = ProviderConfig("groq", "u", "k", "m2")
        val c = ProviderConfig("Gemini", "u", "k", "m3")
        assertEquals(listOf(b), ProviderPresets.upsert(listOf(a), b))
        assertEquals(listOf(a, c), ProviderPresets.upsert(listOf(a), c))
    }

    @Test
    fun parsesModelListsInBothShapes() {
        assertEquals(listOf("a", "b"), ProviderPresets.parseModelIds("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}"""))
        assertEquals(listOf("x", "y"), ProviderPresets.parseModelIds("""[{"id":"y","name":"Y"},{"id":"x"}]"""))
        assertTrue(ProviderPresets.parseModelIds("""{"error":"x"}""").isEmpty())
    }
}
