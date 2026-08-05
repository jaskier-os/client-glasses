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

    @Test
    fun `keys shorter than the floor are treated as unconfigured`() {
        assertFalse(RemoteInputAuth("short".toByteArray()).isConfigured)
        assertFalse(RemoteInputAuth(" ".toByteArray()).isConfigured)
        assertFalse(RemoteInputAuth(ByteArray(RemoteInputAuth.MIN_KEY_BYTES - 1)).isConfigured)
        assertTrue(RemoteInputAuth(ByteArray(RemoteInputAuth.MIN_KEY_BYTES)).isConfigured)
        // ...and an under-length key verifies nothing.
        val weak = RemoteInputAuth("abc".toByteArray())
        assertFalse(weak.verify(msg(), auth.sign(msg())))
    }

    @Test
    fun `u32 fields render unsigned so a signed-int sender cannot desync`() {
        // The canonical desync: a sender holding wms in a signed 32-bit int.
        assertEquals("4294966062", RemoteInputAuth.u32(-1234L and 0xFFFFFFFFL))
        assertEquals("4294967295", RemoteInputAuth.u32(0xFFFFFFFFL))
        assertEquals("0", RemoteInputAuth.u32(0L))
        assertEquals("2147483648", RemoteInputAuth.u32(2147483648L))
        // A value already truncated to the low 32 bits round-trips identically.
        assertEquals(RemoteInputAuth.u32(-1L), RemoteInputAuth.u32(0xFFFFFFFFL))
        // No sign character ever appears.
        for (v in listOf(-1L, -2147483648L, -1234L, 0L, 1L, 0xFFFFFFFFL)) {
            assertFalse("u32 must never emit a sign", RemoteInputAuth.u32(v).contains('-'))
        }
    }

    @Test
    fun `steps renders signed decimal with no leading plus`() {
        assertEquals("3", RemoteInputAuth.steps(3))
        assertEquals("-3", RemoteInputAuth.steps(-3))
        assertEquals("0", RemoteInputAuth.steps(0))
        assertFalse(RemoteInputAuth.steps(3).contains('+'))
    }

    @Test
    fun `canonical message is the frozen pipe-joined form with the numeric type code`() {
        // Frozen by the wire contract and produced by the source device, so this is the one thing
        // in the file that is not ours to choose. Cross-checked against the sender's own vectors in
        // GoldenVectorConformanceTest.
        assertEquals(
            "1|watch|1|0|4|0|0",
            RemoteInputAuth.canonicalMessage(1, "watch", 1L, 0L, 4, 0, 0L),
        )
        assertEquals(
            "1|watch|1|2|1|-1|1060",
            RemoteInputAuth.canonicalMessage(1, "watch", 1L, 2L, 1, -1, 1060L),
        )
    }

    @Test
    fun `each type code produces a distinct digest`() {
        val tags = (1..6).map {
            auth.sign(RemoteInputAuth.canonicalMessage(1, "watch", 1L, 1L, it, 0, 1L))
        }
        assertEquals("type code must be covered by the tag", tags.size, tags.toSet().size)
    }

    @Test
    fun `unicode digits in a tag are rejected`() {
        val m = msg()
        // Character.digit would resolve these as hex; strict ASCII decoding must not.
        assertFalse(auth.verify(m, "\uFF10\uFF11\uFF12\uFF13456789abcdef"))
    }

    private fun msg(
        v: Int = 1,
        src: String = "watch",
        sid: Long = 12345L,
        seq: Long = 7L,
        type: Int = 1,
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
            msg(type = 2),
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
    fun `sid seq and wms are covered as unsigned values`() {
        // Two frames whose sid differs only above the sign bit must not share a tag.
        val a = RemoteInputAuth.canonicalMessage(1, "watch", 0x7FFFFFFFL, 1L, 1, 1, 1L)
        val b = RemoteInputAuth.canonicalMessage(1, "watch", 0x80000000L, 1L, 1, 1, 1L)
        assertNotEquals(a, b)
        assertFalse(auth.verify(b, auth.sign(a)))
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
    fun `a validated source id cannot move bytes across a field boundary`() {
        // The pipe-joined form is not injective in general, because src is variable length: "ab"
        // with sid 1 and "a" with sid 11 would collide if src could contain the separator. That is
        // contained by validating src BEFORE hashing, so the collision is unreachable rather than
        // merely unlikely -- these two ids are both legal, and they must still differ.
        val a = RemoteInputAuth.canonicalMessage(1, "ab", 1L, 1L, 1, 1, 1L)
        val b = RemoteInputAuth.canonicalMessage(1, "a", 11L, 1L, 1, 1, 1L)
        assertNotEquals(a, b)
        assertNotEquals(auth.sign(a), auth.sign(b))

        // And the ids that WOULD enable a collision are refused before they reach the digest.
        assertFalse(InputSource.isValidSourceId("a|1"))
        assertFalse(InputSource.isValidSourceId("ab|"))
    }

    @Test
    fun `source id validation rejects separators and overlong ids`() {
        assertTrue(InputSource.isValidSourceId("watch"))
        assertTrue(InputSource.isValidSourceId("ble_gadget_01"))
        assertFalse(InputSource.isValidSourceId(""))
        assertFalse(InputSource.isValidSourceId("wa|tch"))
        assertFalse(InputSource.isValidSourceId("wa:tch"))
        assertFalse(InputSource.isValidSourceId("Watch"))
        assertFalse(InputSource.isValidSourceId("watch watch"))
        assertFalse(InputSource.isValidSourceId("a".repeat(17)))
        assertTrue(InputSource.isValidSourceId("a".repeat(16)))
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
