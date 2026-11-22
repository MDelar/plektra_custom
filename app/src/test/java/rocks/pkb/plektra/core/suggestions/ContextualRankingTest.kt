package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualRankingTest {

    private fun result(word: String, score: Double, source: SuggestionSource = SuggestionSource.MAIN) =
        SuggestionResult(word, 0, score, source)

    @Test
    fun contextLiftsTheLikelyCompletion() {
        val engine = listOf(result("young", 7.2), result("year", 7.1), result("you", 7.0))
        val ranked = ContextualRanking.rerankCurrentWord(
            typed = "yo",
            results = engine,
            modelProbability = { if (it == "you") 0.4 else 0.0 },
            learnedCounts = emptyMap(),
            extraCompletions = emptyList(),
            limit = 3,
            locale = Locale.UK
        )
        assertEquals("you", ranked.first().candidate)
    }

    @Test
    fun extraCompletionsFillInMissingContextWords() {
        val ranked = ContextualRanking.rerankCurrentWord(
            typed = "Mo",
            results = listOf(result("Moscow", 6.4)),
            modelProbability = { if (it == "morning") 0.3 else 0.0 },
            learnedCounts = emptyMap(),
            extraCompletions = listOf(ContextualRanking.Candidate("morning", 0.3)),
            limit = 3,
            locale = Locale.UK
        )
        assertEquals("Morning", ranked.first().candidate)
    }

    @Test
    fun userDictionaryWordsStayFirst() {
        val ranked = ContextualRanking.rerankCurrentWord(
            typed = "th",
            results = listOf(result("the", 6.0), result("thing", 6.5), result("Thabo", 1.0, SuggestionSource.USER)),
            modelProbability = { if (it == "the") 0.9 else 0.0 },
            learnedCounts = emptyMap(),
            extraCompletions = emptyList(),
            limit = 3,
            locale = Locale.UK
        )
        assertEquals(listOf("Thabo", "the", "thing"), ranked.map { it.candidate })
    }

    @Test
    fun learnedWordsBoostCompletions() {
        val ranked = ContextualRanking.rerankCurrentWord(
            typed = "ma",
            results = listOf(result("make", 7.0), result("many", 6.9), result("mate", 6.0)),
            modelProbability = { 0.0 },
            learnedCounts = mapOf("mate" to 6),
            extraCompletions = emptyList(),
            limit = 3,
            locale = Locale.UK
        )
        assertEquals("mate", ranked.first().candidate)
    }

    @Test
    fun blendKeepsLearnedWordsAndAddsModelPredictions() {
        val learned = listOf(result("ya", 2.0, SuggestionSource.USER))
        val model = listOf(BuiltInBigramModel.Prediction("you", 0.4), BuiltInBigramModel.Prediction("the", 0.1))
        val blended = ContextualRanking.blendNextWords(learned, model, 3, Locale.UK)
        assertEquals(listOf("you", "ya", "the"), blended.map { it.candidate })
        assertTrue(blended.all { it.kind == SuggestionKind.NEXT_WORD })
        assertEquals(SuggestionSource.USER, blended[1].source)
    }
}
