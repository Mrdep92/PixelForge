package com.pixelforge.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeModelsTest {

    private val catalog = """
        {"data":[
          {"id":"a/small:free","context_length":8000,"pricing":{"prompt":"0","completion":"0"}},
          {"id":"b/big:free","context_length":262144,"pricing":{"prompt":"0","completion":"0"},
           "architecture":{"output_modalities":["text"]}},
          {"id":"c/huge:free","context_length":1000000,"pricing":{"prompt":"0","completion":"0"}},
          {"id":"d/paid","context_length":500000,"pricing":{"prompt":"0.000001","completion":"0.000002"}},
          {"id":"e/image:free","context_length":100000,"pricing":{"prompt":"0","completion":"0"},
           "architecture":{"output_modalities":["image"]}},
          {"id":"f/sneaky:free","context_length":100000,"pricing":{"prompt":"0.5","completion":"0"}}
        ]}
    """.trimIndent()

    @Test
    fun catalogKeepsOnlyFreeTextModelsSortedByContext() {
        assertEquals(listOf("c/huge:free", "b/big:free"), FreeModels.parseCatalog(catalog))
    }

    @Test
    fun autoIsNeverTreatedAsFree() {
        assertFalse(FreeModels.isFree("openrouter/auto"))
        assertTrue(FreeModels.isFree("x/y:free"))
        assertTrue(FreeModels.isAutoChoice(""))
        assertTrue(FreeModels.isAutoChoice("openrouter/auto"))
    }

    @Test
    fun candidatesDropPaidUnlessAllowed() {
        val cat = listOf("c/huge:free")
        assertEquals(listOf("c/huge:free"), FreeModels.candidates("openrouter/auto", cat, false))
        assertEquals(listOf("c/huge:free"), FreeModels.candidates("x/paid-model", cat, false))
        assertEquals(listOf("x/paid-model", "c/huge:free"), FreeModels.candidates("x/paid-model", cat, true))
        assertEquals(listOf("u/mine:free", "c/huge:free"), FreeModels.candidates("u/mine:free", cat, false))
    }

    @Test
    fun emptyCatalogFallsBackToStaticFreeList() {
        val c = FreeModels.candidates("", emptyList(), false)
        assertTrue(c.isNotEmpty() && c.all { FreeModels.isFree(it) })
    }
}
