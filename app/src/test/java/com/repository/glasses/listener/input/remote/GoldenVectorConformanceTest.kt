package com.repository.glasses.listener.input.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-repo conformance: the glasses decoder consumes vectors produced by the REAL sender codec in
 * the phone/watch repo.
 *
 * This is the drift guard. Each side asserting against its own literals proves only that it agrees
 * with itself; the failure mode this catches is the two repos quietly disagreeing about arg order,
 * the steps sign convention, the sid width, or which form of the type field the HMAC covers -- none
 * of which surface as anything but "every frame is rejected" on real hardware.
 *
 * The key is the fixed published test vector. It is deliberately not a secret and is not used by any
 * real build.
 */
class GoldenVectorConformanceTest {

    /** Raw key bytes 00..0f, matching the sender's `parseHexOrNull(GOLDEN_KEY_HEX, 16)`. */
    private val key = ByteArray(16) { it.toByte() }
    private val auth = RemoteInputAuth(key)

    private data class Vector(
        val sid: Long,
        val seq: Long,
        val type: String,
        val typeCode: Int,
        val steps: Int,
        val wms: Long,
        val canonical: String,
        val tag: String,
        val rfcommArgs: List<String>,
    )

    /**
     * A frame the SENDER says we must refuse, carried in the same file as the valid ones.
     *
     * This is the half of the contract a receiver can satisfy by accident. A set of only-valid
     * tuples is reproduced byte-for-byte by a receiver still enforcing a superseded rule, so it
     * cannot guard the rule it was written for -- which is exactly what happened when the steps cap
     * moved from 16 to 8.
     */
    private data class RejectVector(
        val reason: String,
        val encoding: String,
        val payloadHex: String?,
        val rfcommArgs: List<String>?,
    )

    private fun lines(): List<String> {
        val stream = javaClass.classLoader!!.getResourceAsStream(RESOURCE)
            ?: error("missing $RESOURCE -- copy it from the sender repo")
        return stream.bufferedReader().readLines().filter { it.isNotBlank() }
    }

    private fun isReject(line: String): Boolean =
        Regex("\"mustReject\"\\s*:\\s*true").containsMatchIn(line)

    private fun loadVectors(): List<Vector> =
        lines().filterNot { isReject(it) }.map { line ->
            Vector(
                sid = jsonLong(line, "sid"),
                seq = jsonLong(line, "seq"),
                type = jsonString(line, "type"),
                typeCode = jsonLong(line, "typeCode").toInt(),
                steps = jsonLong(line, "steps").toInt(),
                wms = jsonLong(line, "wms"),
                canonical = jsonString(line, "canonical"),
                tag = jsonString(line, "tag"),
                rfcommArgs = jsonStringArray(line, "rfcommArgs"),
            )
        }

    private fun loadRejectVectors(): List<RejectVector> =
        lines().filter { isReject(it) }.map { line ->
            RejectVector(
                reason = jsonString(line, "reason"),
                encoding = jsonString(line, "encoding"),
                payloadHex = Regex("\"payloadHex\"\\s*:\\s*\"([^\"]*)\"")
                    .find(line)?.groupValues?.get(1),
                rfcommArgs = Regex("\"rfcommArgs\"\\s*:\\s*\\[([^\\]]*)\\]").find(line)
                    ?.groupValues?.get(1)
                    ?.split(",")
                    ?.map { it.trim().trim('"') }
                    ?.filter { it.isNotEmpty() },
            )
        }

    // Minimal extraction rather than a JSON dependency: the schema is fixed and flat.
    private fun jsonLong(line: String, key: String): Long =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(line)!!.groupValues[1].toLong()

    private fun jsonString(line: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(line)!!.groupValues[1]

    private fun jsonStringArray(line: String, key: String): List<String> =
        Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]").find(line)!!.groupValues[1]
            .split(",")
            .map { it.trim().trim('"') }

    /**
     * Every reject vector the sender publishes that this receiver can actually be fed.
     *
     * Driven from the FILE, not from locally reconstructed cases: a hand-written case can only test
     * the rule its author was already thinking about, which is how a superseded cap survives a
     * green suite.
     *
     * Encoding A (the 26-byte binary framing) is deliberately not implemented here -- this receiver
     * takes remote input as RFCOMM string args, i.e. Encoding B, and a decoder for a framing that
     * never arrives would be untested code on the security path. Those vectors are counted and
     * reported rather than silently skipped, so the gap stays visible.
     */
    @Test
    fun `every applicable reject vector is refused`() {
        val rejects = loadRejectVectors()
        assertTrue("no reject vectors found -- is the file stale?", rejects.isNotEmpty())

        val applicable = rejects.filter { !it.rfcommArgs.isNullOrEmpty() }
        val notApplicable = rejects.size - applicable.size

        val accepted = applicable.filter { v ->
            RemoteInputCodec.decode(v.rfcommArgs!!, "watch", auth) is RemoteInputCodec.Result.Ok
        }
        assertEquals(
            "these MUST-REJECT vectors were accepted: ${accepted.map { it.reason }}",
            emptyList<String>(),
            accepted.map { it.reason },
        )

        println(
            "reject vectors: ${rejects.size} published, ${applicable.size} applicable to this " +
                "receiver (all refused), $notApplicable Encoding-A only (no binary decoder here)",
        )
    }

    /**
     * The vector file must actually contain refusal cases.
     *
     * Guards against silently regressing to an only-valid file, which every receiver passes
     * regardless of which rules it enforces.
     */
    @Test
    fun `the vector file carries reject cases including the superseded steps cap`() {
        val reasons = loadRejectVectors().map { it.reason }
        assertTrue("expected reject vectors in the file", reasons.size >= 20)
        // 9 and 16 are the values a receiver still on the old cap-of-16 would wrongly accept.
        assertTrue(
            "missing the steps-cap vectors that catch a stale MAX_STEPS=16",
            reasons.any { it.contains("steps)=9") } && reasons.any { it.contains("steps)=16") },
        )
    }

    @Test
    fun `vectors are present`() {
        val vectors = loadVectors()
        assertTrue("expected golden vectors, found none", vectors.isNotEmpty())
    }

    @Test
    fun `our canonical string matches the sender's byte for byte`() {
        for (v in loadVectors()) {
            val ours = RemoteInputAuth.canonicalMessage(
                RemoteInputCodec.PROTOCOL_VERSION, "watch", v.sid, v.seq, v.typeCode, v.steps, v.wms,
            )
            assertEquals("canonical mismatch for ${v.type} seq=${v.seq}", v.canonical, ours)
        }
    }

    @Test
    fun `our HMAC reproduces the sender's tag`() {
        for (v in loadVectors()) {
            assertEquals("tag mismatch for ${v.type} seq=${v.seq}", v.tag, auth.sign(v.canonical))
        }
    }

    @Test
    fun `every sender-produced frame decodes and verifies`() {
        for (v in loadVectors()) {
            val result = RemoteInputCodec.decode(v.rfcommArgs, "watch", auth)
            assertTrue(
                "vector ${v.type} seq=${v.seq} was rejected: " +
                    (result as? RemoteInputCodec.Result.Rejected)?.reason,
                result is RemoteInputCodec.Result.Ok,
            )
        }
    }

    @Test
    fun `decoded fields round-trip exactly, including the steps sign`() {
        for (v in loadVectors()) {
            val frame = (RemoteInputCodec.decode(v.rfcommArgs, "watch", auth)
                as RemoteInputCodec.Result.Ok).frame
            assertEquals(v.sid, frame.sid)
            assertEquals(v.seq, frame.seq)
            assertEquals(v.wms, frame.wms)
            when (frame) {
                is RemoteInputFrame.Action -> {
                    assertEquals("steps sign or magnitude drifted", v.steps, frame.delta)
                    val expected = when (v.typeCode) {
                        RemoteInputCodec.TYPE_SCROLL -> RemoteAction.SCROLL_STEP
                        RemoteInputCodec.TYPE_TAP -> RemoteAction.TAP
                        else -> RemoteAction.BACK
                    }
                    assertEquals(expected, frame.action)
                }
                is RemoteInputFrame.Lifecycle -> {
                    assertEquals(0, v.steps)
                    val expected = when (v.typeCode) {
                        RemoteInputCodec.TYPE_OPEN -> RemoteLifecycle.OPEN
                        RemoteInputCodec.TYPE_CLOSE -> RemoteLifecycle.CLOSE
                        else -> RemoteLifecycle.PING
                    }
                    assertEquals(expected, frame.kind)
                }
            }
        }
    }

    @Test
    fun `both scroll directions appear in the vectors`() {
        // A one-direction corpus cannot catch a sign inversion, which would scroll the wrong way.
        val steps = loadVectors().map { it.steps }
        assertTrue("vectors must include a forward scroll", steps.any { it > 0 })
        assertTrue("vectors must include a backward scroll", steps.any { it < 0 })
    }

    @Test
    fun `a vector re-signed with a different key is rejected`() {
        val other = RemoteInputAuth(ByteArray(16) { (it + 1).toByte() })
        for (v in loadVectors()) {
            val forged = v.rfcommArgs.toMutableList()
            forged[7] = other.sign(v.canonical)
            val result = RemoteInputCodec.decode(forged, "watch", auth)
            assertTrue(
                "a frame signed with the wrong key must be rejected",
                result is RemoteInputCodec.Result.Rejected,
            )
        }
    }

    // --- REJECT vectors ---
    //
    // Accept-only vectors cannot catch a validation mismatch: a receiver enforcing a cap of 16
    // reproduces every valid vector byte for byte and passes. These are the cases that must FAIL.

    /** Build args and sign them, so the ONLY thing under test is the validation rule. */
    private fun signed(
        sid: Long = 1L,
        seq: Long = 1L,
        typeCode: Int = RemoteInputCodec.TYPE_SCROLL,
        typeName: String = "SCROLL",
        steps: Int = 1,
        wms: Long = 1000L,
        src: String = "watch",
        version: Int = 1,
    ): List<String> {
        val canonical =
            RemoteInputAuth.canonicalMessage(version, src, sid, seq, typeCode, steps, wms)
        return listOf(
            version.toString(), src, sid.toString(), seq.toString(), typeName,
            steps.toString(), wms.toString(), auth.sign(canonical),
        )
    }

    private fun assertRejected(args: List<String>, why: String) {
        val result = RemoteInputCodec.decode(args, "watch", auth)
        assertTrue("must be rejected: $why", result is RemoteInputCodec.Result.Rejected)
    }

    @Test
    fun `steps beyond the cap are rejected in both signs`() {
        for (steps in listOf(9, 16, 17, 127, -9, -16, -17, -128)) {
            assertRejected(signed(steps = steps), "steps $steps exceeds the cap of 8")
        }
        // ...and the cap itself is accepted, so the test cannot pass by rejecting everything.
        for (steps in listOf(1, 8, -1, -8)) {
            assertTrue(
                "steps $steps is within the cap and must be accepted",
                RemoteInputCodec.decode(signed(steps = steps), "watch", auth)
                    is RemoteInputCodec.Result.Ok,
            )
        }
    }

    @Test
    fun `Int MIN_VALUE steps is rejected rather than passing an absolute-value check`() {
        // abs(Int.MIN_VALUE) is itself negative, so a magnitude check written the obvious way
        // admits the single most extreme value.
        assertRejected(signed(steps = Int.MIN_VALUE), "Int.MIN_VALUE steps")
    }

    @Test
    fun `a SCROLL carrying zero steps is rejected`() {
        assertRejected(signed(steps = 0), "SCROLL with no movement")
    }

    @Test
    fun `a non-SCROLL frame carrying steps is rejected`() {
        for ((code, name) in listOf(
            RemoteInputCodec.TYPE_TAP to "TAP",
            RemoteInputCodec.TYPE_BACK to "BACK",
            RemoteInputCodec.TYPE_OPEN to "OPEN",
            RemoteInputCodec.TYPE_CLOSE to "CLOSE",
            RemoteInputCodec.TYPE_PING to "PING",
        )) {
            assertRejected(
                signed(typeCode = code, typeName = name, steps = 3),
                "$name must not carry steps",
            )
        }
    }

    @Test
    fun `unknown type codes are rejected`() {
        for (bad in listOf("0", "7", "99", "-1", "", "SCROLLX", "scroll")) {
            val args = signed().toMutableList()
            args[4] = bad
            assertRejected(args, "type '$bad'")
        }
    }

    @Test
    fun `short frames are rejected and long frames are accepted`() {
        val valid = signed()
        for (n in 0 until RemoteInputCodec.MIN_ARGS) {
            assertRejected(valid.take(n), "only $n args")
        }
        // Extra trailing args are ignored for forward compatibility within v1.
        assertTrue(
            "extra trailing args must be ignored, not rejected",
            RemoteInputCodec.decode(valid + listOf("future", "fields"), "watch", auth)
                is RemoteInputCodec.Result.Ok,
        )
    }

    @Test
    fun `unsupported protocol versions are rejected`() {
        for (v in listOf(0, 2, 99, -1)) {
            assertRejected(signed(version = v), "version $v")
        }
    }

    @Test
    fun `a frame naming a different source is rejected`() {
        assertRejected(signed(src = "gadget"), "source id mismatch")
    }

    @Test
    fun `malformed source ids are rejected before they reach the digest`() {
        for (bad in listOf("", "Watch", "wa|tch", "a".repeat(17))) {
            val args = signed().toMutableList()
            args[1] = bad
            assertRejected(args, "source id '$bad'")
        }
    }

    @Test
    fun `non-canonical numeric renderings are rejected`() {
        // Each of these would produce a DIFFERENT canonical string on the sender, so accepting them
        // here would mean accepting a frame whose tag covers something else.
        for ((idx, bad) in listOf(
            2 to "+1", 2 to "007", 2 to "-1", 2 to " 1", 2 to "1 ", 2 to "4294967296",
            3 to "+2", 3 to "0002",
            5 to "+3", 5 to "003", 5 to "-0",
            6 to "+1000", 6 to "01000", 6 to "4294967296",
        )) {
            val args = signed().toMutableList()
            args[idx] = bad
            assertRejected(args, "arg $idx = '$bad'")
        }
    }

    @Test
    fun `malformed tags are rejected`() {
        for (bad in listOf("", "abc", "0".repeat(15), "0".repeat(17), "zzzzzzzzzzzzzzzz")) {
            val args = signed().toMutableList()
            args[7] = bad
            assertRejected(args, "tag '$bad'")
        }
    }

    @Test
    fun `a tampered field invalidates the tag`() {
        // Change each field in turn, leaving the original tag in place.
        val valid = signed()
        for (idx in 0..6) {
            val args = valid.toMutableList()
            args[idx] = when (idx) {
                1 -> "gadget"
                4 -> "TAP"
                5 -> "2"
                else -> "9"
            }
            assertRejected(args, "field $idx tampered")
        }
    }

    @Test
    fun `an unconfigured key rejects every valid vector`() {
        val unconfigured = RemoteInputAuth(ByteArray(0))
        for (v in loadVectors()) {
            assertTrue(
                "an unconfigured build must refuse remote input, not accept it",
                RemoteInputCodec.decode(v.rfcommArgs, "watch", unconfigured)
                    is RemoteInputCodec.Result.Rejected,
            )
        }
    }

    companion object {
        private const val RESOURCE = "golden-vectors-v1.ndjson"
    }
}
