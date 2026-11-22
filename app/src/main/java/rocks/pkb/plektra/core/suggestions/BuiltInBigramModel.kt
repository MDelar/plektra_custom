package rocks.pkb.plektra.core.suggestions

import android.content.res.AssetManager
import android.util.Log
import java.io.DataInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln

/**
 * Plektra Custom: read-only next-word model bundled with the app (no network, no learning).
 *
 * File format (big-endian), written by tools/british_english/build_en_gb_assets.py:
 *   "PKBG" u8 version u32 vocabCount { u8 len, utf8 display word }*
 *   u16 startCount { u16 word, u8 q }*
 *   u32 rowCount { u16 prev, u8 n, { u16 next, u8 q }* }*
 * q is a log-scaled probability: p = exp(ln(P_MIN) * (1 - q / 255)).
 */
class BuiltInBigramModel internal constructor(
    private val vocab: Array<String>,
    private val keyIndex: Map<String, Int>,
    private val starts: IntArray,
    private val followers: Map<Int, IntArray>
) {

    data class Prediction(val word: String, val probability: Double)

    /** Most likely words after [previousWord], best first. */
    fun predict(previousWord: String?, limit: Int): List<Prediction> {
        if (limit <= 0) return emptyList()
        val row = rowFor(previousWord) ?: return emptyList()
        return row.asSequence().take(limit).map { decode(it) }.toList()
    }

    /** Most likely first words of a message or sentence. */
    fun predictSentenceStart(limit: Int): List<Prediction> {
        if (limit <= 0) return emptyList()
        return starts.asSequence().take(limit).map { decode(it) }.toList()
    }

    /** P(word | previousWord), or 0.0 when the pair is not in the model. */
    fun probability(previousWord: String?, word: String): Double {
        val row = rowFor(previousWord) ?: return 0.0
        val target = keyIndex[key(word)] ?: return 0.0
        for (packed in row) {
            if (packed ushr 8 == target) return dequantise(packed and 0xFF)
        }
        return 0.0
    }

    /** P(word starts a sentence), or 0.0 when it is not a common sentence start. */
    fun sentenceStartProbability(word: String): Double {
        val target = keyIndex[key(word)] ?: return 0.0
        for (packed in starts) {
            if (packed ushr 8 == target) return dequantise(packed and 0xFF)
        }
        return 0.0
    }

    /** Sentence starts beginning with [typedPrefix], best first. */
    fun sentenceStartCompletions(typedPrefix: String, limit: Int): List<Prediction> =
        matchPrefix(starts, typedPrefix, limit)

    /**
     * Likely next words after [previousWord] that start with [typedPrefix] and are longer than it.
     * Matching ignores case, accents and apostrophes, like the dictionary does.
     */
    fun completions(previousWord: String?, typedPrefix: String, limit: Int): List<Prediction> {
        val row = rowFor(previousWord) ?: return emptyList()
        return matchPrefix(row, typedPrefix, limit)
    }

    private fun matchPrefix(row: IntArray, typedPrefix: String, limit: Int): List<Prediction> {
        if (limit <= 0 || typedPrefix.isBlank()) return emptyList()
        val prefix = WordNormalization.normalizeForDictionary(typedPrefix, Locale.ROOT)
        if (prefix.isEmpty()) return emptyList()
        val results = ArrayList<Prediction>(limit)
        for (packed in row) {
            val word = vocab[packed ushr 8]
            if (word.length <= typedPrefix.length) continue
            val normalized = WordNormalization.normalizeForDictionary(word, Locale.ROOT)
            if (normalized.length > prefix.length && normalized.startsWith(prefix)) {
                results.add(Prediction(word, dequantise(packed and 0xFF)))
                if (results.size >= limit) break
            }
        }
        return results
    }

    private fun rowFor(previousWord: String?): IntArray? {
        val prev = previousWord?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val index = keyIndex[key(prev)] ?: return null
        return followers[index]
    }

    private fun decode(packed: Int) = Prediction(vocab[packed ushr 8], dequantise(packed and 0xFF))

    companion object {
        private const val TAG = "BuiltInBigramModel"
        private val LN_P_MIN = ln(1e-5)

        internal fun key(word: String): String =
            WordNormalization.normalizeApostrophes(word.trim()).lowercase(Locale.ROOT)

        internal fun dequantise(q: Int): Double = exp(LN_P_MIN * (1.0 - q / 255.0))

        /** Asset path of the bundled model for [locale], or null when there is none. */
        fun assetPathFor(locale: Locale): String? = when (locale.language.lowercase(Locale.ROOT)) {
            "en" -> "common/ngrams/en_gb_bigrams.bin"
            else -> null
        }

        fun read(input: InputStream): BuiltInBigramModel {
            val data = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { data.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "PKBG") { "Not a Plektra bigram model" }
            val version = data.readUnsignedByte()
            require(version == 1) { "Unsupported bigram model version $version" }
            val vocabCount = data.readInt()
            val vocab = Array(vocabCount) {
                val bytes = ByteArray(data.readUnsignedByte())
                data.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
            val keyIndex = HashMap<String, Int>(vocabCount * 2)
            vocab.forEachIndexed { index, word -> keyIndex.putIfAbsent(key(word), index) }
            val starts = IntArray(data.readUnsignedShort()) {
                (data.readUnsignedShort() shl 8) or data.readUnsignedByte()
            }
            val rowCount = data.readInt()
            val followers = HashMap<Int, IntArray>(rowCount * 2)
            repeat(rowCount) {
                val prev = data.readUnsignedShort()
                followers[prev] = IntArray(data.readUnsignedByte()) {
                    (data.readUnsignedShort() shl 8) or data.readUnsignedByte()
                }
            }
            return BuiltInBigramModel(vocab, keyIndex, starts, followers)
        }

        private val cache = ConcurrentHashMap<String, BuiltInBigramModel>()
        private val missing = ConcurrentHashMap.newKeySet<String>()

        /** Returns the cached model for [locale] without loading it. */
        fun cached(locale: Locale): BuiltInBigramModel? = assetPathFor(locale)?.let { cache[it] }

        /** Loads (once) and returns the model for [locale]. Call off the main thread. */
        fun load(assets: AssetManager, locale: Locale): BuiltInBigramModel? {
            val path = assetPathFor(locale) ?: return null
            cache[path]?.let { return it }
            if (path in missing) return null
            return synchronized(this) {
                cache[path] ?: try {
                    val start = System.currentTimeMillis()
                    assets.open(path).use { read(it) }.also {
                        cache[path] = it
                        Log.i(TAG, "Loaded $path in ${System.currentTimeMillis() - start}ms")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No built-in bigram model at $path: ${e.message}")
                    missing.add(path)
                    null
                }
            }
        }
    }
}
