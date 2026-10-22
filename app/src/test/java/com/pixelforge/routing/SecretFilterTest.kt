package com.pixelforge.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretFilterTest {

    @Test
    fun masksKnownTokenFormats() {
        val text = "key sk-or-v1-abcdef0123456789abcdef0123456789 dan github_pat_11ABCDEFG0123456789_abcdefghijklmnop " +
            "dan ghp_abcdefghijklmnopqrstuvwxyz0123456789 dan AKIAABCDEFGHIJKLMNOP"
        val out = SecretFilter.redact(text)
        assertFalse(out.contains("sk-or-v1"))
        assertFalse(out.contains("github_pat_"))
        assertFalse(out.contains("ghp_"))
        assertFalse(out.contains("AKIA"))
        assertEquals(4, Regex(Regex.escape(SecretFilter.MASK)).findAll(out).count())
    }

    @Test
    fun masksPrivateKeyBlock() {
        val out = SecretFilter.redact("a\n-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----\nb")
        assertEquals("a\n${SecretFilter.MASK}\nb", out)
    }

    @Test
    fun masksSecretAssignmentsButKeepsOrdinaryCode() {
        val out = SecretFilter.redact("apiKey = \"a1b2c3d4e5f6g7h8i9\"\nval token = readToken()\nval name = \"hello\"")
        assertTrue(out.contains("apiKey = \"${SecretFilter.MASK}"))
        assertTrue(out.contains("val token = readToken()"))
        assertTrue(out.contains("val name = \"hello\""))
    }
}
