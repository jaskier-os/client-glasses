package com.repository.glasses.capture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * RNNT greedy decoding on CPU (onnxruntime), batch of 1.
 *
 * Semantics that matter (mirrors gigaam.onnx_utils._decode_rnnt_batch):
 *  - blank index = vocab size (33 here; 34 logits total),
 *  - the decoder state is committed ONLY on a non-blank emission,
 *  - t always advances after at most MAX_SYMBOLS emissions,
 *  - the loop is bounded by encodedLen, NOT the fixed 125 encoder frames.
 *
 * Two tokenizations are supported, selected by which vocabulary is passed in:
 *  - v3_rnnt is CHARWISE over 33 entries (space + 32 Cyrillic, no yo), so
 *    detokenizing is a plain join;
 *  - v3_e2e_rnnt is SENTENCEPIECE over 1024 pieces, which is what carries
 *    punctuation and capitalisation. Detokenizing is still a join, because the
 *    only piece-level rule that matters here is U+2581 -> space.
 * Blank is always the last logit, i.e. index == vocab.size.
 */
class RnntDecoder(decoderOnnx: File, jointOnnx: File, vocab: Array<String>? = null) {

    companion object {
        const val ENC_DIM = 768
        const val ENC_FRAMES = 125
        const val PRED_HIDDEN = 320
        const val MAX_SYMBOLS_PER_FRAME = 10

        /** sentencepiece word-boundary marker, rendered as a space. */
        const val SPM_SPACE = "\u2581"

        // v3_rnnt.yaml `decoding.vocabulary`, in order. Index 33 = blank.
        val CHARWISE_VOCAB = arrayOf(
            " ", "а", "б", "в", "г", "д", "е", "ж", "з", "и", "й", "к", "л",
            "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш",
            "щ", "ъ", "ы", "ь", "э", "ю", "я"
        )

        /**
         * Turn decoded vocabulary pieces into the final transcript.
         *
         * Pure and ONNX-free so it is JVM-testable: this is the last step before
         * the text crosses the Binder into the phone's delivery path, and the
         * empty case is load-bearing. An empty piece list MUST yield "" -- an
         * explicit empty final, which the phone treats as CANCEL -- never null.
         * A whitespace-only result collapses to "" for the same reason: it must
         * not masquerade as a non-blank final to isNotBlank() callers upstream.
         *
         * The only sentencepiece rule that matters is U+2581 -> space. The
         * charwise vocabulary contains no U+2581, so the replace is a no-op
         * there. Punctuation and capitalisation come from the model and are not
         * touched.
         */
        fun detokenize(pieces: List<String>): String {
            val sb = StringBuilder(pieces.size * 4)
            for (p in pieces) sb.append(p)
            return sb.toString().replace(SPM_SPACE, " ").trim()
        }
    }

    private val vocabArr: Array<String> = vocab ?: CHARWISE_VOCAB
    private val blank: Int = vocabArr.size

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val decSess: OrtSession
    private val jointSess: OrtSession

    init {
        // These are TINY graphs (a 320-unit LSTM step and a 768x320 joint), run
        // once per token. Multi-threading them is a net LOSS on the 4x A55: the
        // per-run thread-pool fence costs more than the ~250k MACs of work, and
        // ORT's default spin-wait burns the other cores. Single-threaded, no spin.
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
            setMemoryPatternOptimization(true)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        decSess = env.createSession(decoderOnnx.absolutePath, opts)
        jointSess = env.createSession(jointOnnx.absolutePath, opts)
    }

    // Reused direct buffers: allocating a fresh heap FloatArray per step and
    // letting ORT box outputs into nested Java arrays dominated the decode time.
    private val bufX = java.nio.ByteBuffer.allocateDirect(8)
        .order(java.nio.ByteOrder.nativeOrder()).asLongBuffer()
    private val bufH = java.nio.ByteBuffer.allocateDirect(PRED_HIDDEN * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
    private val bufC = java.nio.ByteBuffer.allocateDirect(PRED_HIDDEN * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
    private val bufEnc = java.nio.ByteBuffer.allocateDirect(ENC_DIM * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
    private val bufDec = java.nio.ByteBuffer.allocateDirect(PRED_HIDDEN * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()

    private val shapeX = longArrayOf(1, 1)
    private val shapeState = longArrayOf(1, 1, PRED_HIDDEN.toLong())
    private val shapeEnc = longArrayOf(1, ENC_DIM.toLong(), 1)
    private val shapeDec = longArrayOf(1, PRED_HIDDEN.toLong(), 1)

    /**
     * @param encoded flat [768*125], dim-major (index = d*125 + t)
     * @param encodedLen number of valid encoder frames
     */
    /**
     * NOT reentrant: the direct I/O buffers above are reused across steps, so
     * concurrent callers would interleave writes into the same buffer. The
     * capture thread and the self-test thread both call this, hence @Synchronized.
     */
    /** Number of decoder+joint steps in the last decode() call. */
    var steps = 0; private set

    // Scratch for the per-frame encoder slice. Filling a direct FloatBuffer with
    // 768 individual put() calls costs more than the inference itself on this
    // device (each put does a bounds check + byte-order conversion); gathering
    // into a plain FloatArray and doing ONE bulk put is dramatically cheaper.
    private val encScratch = FloatArray(ENC_DIM)

    @Synchronized
    fun decode(encoded: FloatArray, encodedLen: Int): String {
        steps = 0
        val tokens = ArrayList<Int>(64)

        // Committed state. Zeroed = "fresh": a fresh step feeds the blank label
        // with all-zero h/c, and the state is committed ONLY on a non-blank.
        val h = FloatArray(PRED_HIDDEN)
        val c = FloatArray(PRED_HIDDEN)
        var lastLabel = blank.toLong()

        val tLimit = minOf(encodedLen, ENC_FRAMES)

        for (t in 0 until tLimit) {
            // Encoder slice [1,768,1] for this frame; `encoded` is dim-major.
            // NOTE: createTensor CONSUMES the buffer position, so every buffer
            // must be rewound immediately before each createTensor call.
            // Reset position AND limit explicitly: createTensor advances the
            // position and can leave the limit short, so clear() alone is not
            // enough to make the buffer refillable.
            for (d in 0 until ENC_DIM) encScratch[d] = encoded[d * ENC_FRAMES + t]
            bufEnc.position(0); bufEnc.limit(ENC_DIM); bufEnc.put(encScratch); bufEnc.rewind()

            var symbols = 0
            while (symbols < MAX_SYMBOLS_PER_FRAME) {
                bufX.position(0); bufX.limit(1); bufX.put(lastLabel); bufX.rewind()
                bufH.position(0); bufH.limit(PRED_HIDDEN); bufH.put(h); bufH.rewind()
                bufC.position(0); bufC.limit(PRED_HIDDEN); bufC.put(c); bufC.rewind()

                val xT = OnnxTensor.createTensor(env, bufX, shapeX)
                val hT = OnnxTensor.createTensor(env, bufH, shapeState)
                val cT = OnnxTensor.createTensor(env, bufC, shapeState)
                val decOut = decSess.run(mapOf("x" to xT, "hi" to hT, "ci" to cT))

                // Read outputs through their direct FloatBuffers instead of
                // .value, which would allocate nested Java arrays per step.
                val decBuf = (decOut[0] as OnnxTensor).floatBuffer
                val hoBuf = (decOut[1] as OnnxTensor).floatBuffer
                val coBuf = (decOut[2] as OnnxTensor).floatBuffer

                bufDec.position(0); bufDec.limit(PRED_HIDDEN)
                decBuf.rewind(); bufDec.put(decBuf); bufDec.rewind()
                bufEnc.position(0); bufEnc.limit(ENC_DIM)

                val encT = OnnxTensor.createTensor(env, bufEnc, shapeEnc)
                val decT = OnnxTensor.createTensor(env, bufDec, shapeDec)
                val jOut = jointSess.run(mapOf("enc" to encT, "dec" to decT))
                steps++
                val logits = (jOut[0] as OnnxTensor).floatBuffer

                var best = 0
                var bestV = logits.get(0)
                for (i in 1..vocabArr.size) {
                    val v = logits.get(i)
                    if (v > bestV) { bestV = v; best = i }
                }

                if (best != blank) {
                    // Commit the new state only on an actual emission.
                    hoBuf.rewind(); hoBuf.get(h)
                    coBuf.rewind(); coBuf.get(c)
                    lastLabel = best.toLong()
                    tokens.add(best)
                    symbols++
                }

                xT.close(); hT.close(); cT.close(); encT.close(); decT.close()
                decOut.close(); jOut.close()

                if (best == blank) break
            }
        }

        // Go through the shared pure detokenizer so the JVM-tested path IS the
        // production path (see RnntDetokenizeTest).
        return detokenize(tokens.mapNotNull { vocabArr.getOrNull(it) })
    }

    fun close() {
        runCatching { decSess.close() }
        runCatching { jointSess.close() }
    }
}
