package com.repository.glasses.listener.input.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputAuthTest {

    // A test-only key. The real key is never present in this repo.
    private val key = "test-key-not-the-real-one".toByteArray()
    private val auth = RemoteInputAuth(key)

    private fun msg(
        v: Int = 1,
        src: String = "watch",
        sid: Long = 12345L,
        seq: Long = 7L,
        type: String = "SCROLL",
        steps: Int = 3,
        wms: Long = 999L,
    ) = RemoteInputAuth.canonicalMessage(v, src, sid, seq, type, steps, wms)

    @Test
    fun `a correctly signed frame verifies`() {
        val m = msg()
        assertTrue(auth.verify(m, auth.sign(m)))
    }

    @Test
    fun `tag is the documented width`() {
        assertEquals(RemoteInputAuth.TAG_HEX_CHARS, auth.sign(msg()).length)
        assertEquals(16, auth.sign(msg()).length)
    }

    @Test
    fun `a tag from a different key is rejected`() {
        val other = RemoteInputAuth("some-other-key".toByteArray())
        assertFalse(auth.verify(msg(), other.sign(msg())))
    }

    @Test
    fun `every field is covered by the tag`() {
        val base = msg()
        val tag = auth.sign(base)
        val mutations = listOf(
            msg(v = 2),
            msg(src = "gadget"),
            msg(sid = 12346L),
            msg(seq = 8L),
            msg(type = "SELECT"),
            msg(steps = -3),
            msg(wms = 1000L),
        )
        for (m in mutations) {
            assertNotEquals("mutation must change the message: $m", base, m)
            assertFalse("mutated frame must not verify under the original tag: $m", auth.verify(m, tag))
        }
    }

    @Test
    fun `steps sign is covered`() {
        val plus = msg(steps = 3)
        val minus = msg(steps = -3)
        assertFalse(auth.verify(minus, auth.sign(plus)))
    }

    @Test
    fun `blank key fails closed`() {
        val unconfigured = RemoteInputAuth(ByteArray(0))
        assertFalse(unconfigured.isConfigured)
        // Not even a tag it "signed" itself is accepted.
        assertFalse(unconfigured.verify(msg(), unconfigured.sign(msg())))
        assertFalse(unconfigured.verify(msg(), auth.sign(msg())))
        assertFalse(unconfigured.verify(msg(), "0000000000000000"))
    }

    @Test
    fun `malformed tags are rejected without throwing`() {
        val m = msg()
        val good = auth.sign(m)
        assertFalse(auth.verify(m, ""))
        assertFalse(auth.verify(m, good.dropLast(1)))
        assertFalse(auth.verify(m, good + "00"))
        assertFalse(auth.verify(m, "zzzzzzzzzzzzzzzz"))
        assertFalse(auth.verify(m, "................"))
        assertFalse(auth.verify(m, "0123456789abcdeg"))
    }

    @Test
    fun `canonical message is injective across a field boundary`() {
        // The whole point of length-prefixing: with a bare "a|b" join these two frames could be
        // made to produce the same digest by moving bytes across the src boundary.
        val a = RemoteInputAuth.canonicalMessage(1, "ab", 1L, 1L, "SCROLL", 1, 1L)
        val b = RemoteInputAuth.canonicalMessage(1, "a", 11L, 1L, "SCROLL", 1, 1L)
        assertNotEquals(a, b)
        assertNotEquals(auth.sign(a), auth.sign(b))
    }

    @Test
    fun `canonical message carries a domain separator`() {
        assertTrue(msg().startsWith(RemoteInputAuth.DOMAIN_EVENT))
    }

    @Test
    fun `nonce slot changes the digest so it can be enabled without ambiguity`() {
        val without = RemoteInputAuth.canonicalMessage(1, "watch", 1L, 1L, "SCROLL", 1, 1L)
        val with = RemoteInputAuth.canonicalMessage(1, "watch", 1L, 1L, "SCROLL", 1, 1L, "abcd")
        assertNotEquals(without, with)
        assertFalse(auth.verify(with, auth.sign(without)))
    }

    @Test
    fun `source id validation rejects separators and overlong ids`() {
        assertTrue(RemoteInputAuth.isValidSourceId("watch"))
        assertTrue(RemoteInputAuth.isValidSourceId("ble_gadget_01"))
        assertFalse(RemoteInputAuth.isValidSourceId(""))
        assertFalse(RemoteInputAuth.isValidSourceId("wa|tch"))
        assertFalse(RemoteInputAuth.isValidSourceId("wa:tch"))
        assertFalse(RemoteInputAuth.isValidSourceId("Watch"))
        assertFalse(RemoteInputAuth.isValidSourceId("watch watch"))
        assertFalse(RemoteInputAuth.isValidSourceId("a".repeat(17)))
        assertTrue(RemoteInputAuth.isValidSourceId("a".repeat(16)))
    }

    @Test
    fun `verification is deterministic and repeatable`() {
        val m = msg()
        val tag = auth.sign(m)
        repeat(50) { assertTrue(auth.verify(m, tag)) }
        assertEquals(tag, auth.sign(m))
    }

    @Test
    fun `tag hex is lowercase and well formed`() {
        val tag = auth.sign(msg())
        assertTrue(tag.matches(Regex("^[0-9a-f]{16}$")))
    }
}
