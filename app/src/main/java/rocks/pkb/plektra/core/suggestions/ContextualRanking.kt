package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Plektra Custom: context-aware ordering of suggestions.
 *
 * Current-word suggestions are re-ranked using the previous word: the bundled bigram model
 * ("see y" -> "you") plus what the user has typed before (learned next words). Likely words
 * that the dictionary ranking left out are added as extra completions.
 */
object ContextualRanking {

    data class Candidate(val word: String, val probability: Double)

    private const val MODEL_WEIGHT = 3.0
    private const val LEARNED_WEIGHT = 1.2
    private const val EXTRA_COMPLETION_BASE_SCORE = 6.0
    private const val NEXT_WORD_MODEL_WEIGHT = 6.0

    /**
     * @param typed the word being typed
     * @param results the dictionary engine's candidates (more than [limit] is fine)
     * @param modelProbability P(candidate | context) from the bundled model, 0.0 when unknown
     * @param learnedCounts how often the user typed each candidate (lowercase key) in this context
     * @param extraCompletions likely context words starting with [typed] (model or learned)
     */
    fun rerankCurrentWord(
        typed: String,
        results: List<SuggestionResult>,
        modelProbability: (String) -> Double,
        learnedCounts: Map<String, Int>,
        extraCompletions: List<Candidate>,
        limit: Int,
        locale: Locale,
        isKnownWord: (String) -> Boolean = { true }
    ): List<SuggestionResult> {
        if (limit <= 0) return emptyList()
        val typedNormalized = WordNormalization.normalizeForDictionary(typed, locale)

        fun bonus(word: String): Double {
            val p = modelProbability(word).coerceIn(0.0, 1.0)
            val learned = learnedCounts[word.lowercase(locale)] ?: 0
            return MODEL_WEIGHT * sqrt(p) + LEARNED_WEIGHT * ln(1.0 + learned)
        }

        fun isCompletion(word: String): Boolean {
            val normalized = WordNormalization.normalizeForDictionary(word, locale)
            return typedNormalized.isNotEmpty() && normalized.length > typedNormalized.length &&
                normalized.startsWith(typedNormalized)
        }

        val seen = HashSet<String>()
        val scored = ArrayList<Pair<SuggestionResult, Int>>()
        for (result in results) {
            if (!seen.add(result.candidate.lowercase(locale))) continue
            val tier = when {
                result.source == SuggestionSource.USER || result.source == SuggestionSource.DEFAULT_USER -> 0
                isCompletion(result.candidate) -> 1
                else -> 2
            }
            scored.add(result.copy(score = result.score + bonus(result.candidate)) to tier)
        }
        for (extra in extraCompletions) {
            if (extra.word.equals(typed, ignoreCase = true)) continue
            if (!isCompletion(extra.word) || !isKnownWord(extra.word)) continue
            val display = CasingHelper.applyCasing(extra.word, typed)
            if (!seen.add(display.lowercase(locale))) continue
            scored.add(
                SuggestionResult(
                    candidate = display,
                    distance = 0,
                    score = EXTRA_COMPLETION_BASE_SCORE + bonus(extra.word),
                    source = SuggestionSource.MAIN,
                    kind = SuggestionKind.CURRENT_WORD
                ) to 1
            )
        }
        return scored
            .sortedWith(
                compareBy<Pair<SuggestionResult, Int>> { it.second }
                    .thenByDescending { it.first.score }
                    .thenBy { it.first.candidate.length }
            )
            .map { it.first }
            .take(limit)
    }

    /** Puts [preferred] (e.g. "colour" for typed "color") first, without duplicates. */
    fun withPreferredSpelling(results: List<SuggestionResult>, preferred: String?, limit: Int, locale: Locale): List<SuggestionResult> {
        if (preferred == null) return results.take(limit)
        val first = SuggestionResult(preferred, 1, Double.MAX_VALUE / 4, SuggestionSource.MAIN, SuggestionKind.CURRENT_WORD)
        val key = preferred.lowercase(locale)
        return (listOf(first) + results.filterNot { it.candidate.lowercase(locale) == key }).take(limit)
    }

    /**
     * Merges learned next words (count per word) with the bundled model's predictions.
     * Learned words keep the user's own spelling and are marked as USER so they can be forgotten.
     */
    fun blendNextWords(
        learned: List<SuggestionResult>,
        model: List<BuiltInBigramModel.Prediction>,
        limit: Int,
        locale: Locale,
        kind: SuggestionKind = SuggestionKind.NEXT_WORD
    ): List<SuggestionResult> {
        if (limit <= 0) return emptyList()
        val merged = LinkedHashMap<String, SuggestionResult>()
        for (result in learned) {
            val key = result.candidate.lowercase(locale)
            if (key !in merged) merged[key] = result.copy(kind = kind)
        }
        for (prediction in model) {
            val key = prediction.word.lowercase(locale)
            val extra = NEXT_WORD_MODEL_WEIGHT * prediction.probability
            val existing = merged[key]
            merged[key] = existing?.copy(score = existing.score + extra) ?: SuggestionResult(
                candidate = prediction.word,
                distance = 0,
                score = extra,
                source = SuggestionSource.MAIN,
                kind = kind
            )
        }
        return merged.values
            .sortedWith(compareByDescending<SuggestionResult> { it.score }.thenBy { it.candidate.length })
            .take(limit)
    }
}
