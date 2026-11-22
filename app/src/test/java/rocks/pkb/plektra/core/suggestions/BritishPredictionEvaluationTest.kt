package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Plektra Custom: measures prediction quality on British messages that are NOT in the
 * training data, with and without the British English improvements. Prints a small report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BritishPredictionEvaluationTest {

    companion object {
        private val HELD_OUT = listOf(
            "Are you still up for coffee on Saturday morning",
            "I'm going to be a bit late because the train is delayed",
            "Can you let me know what time you finish work",
            "Thanks so much for the lovely flowers",
            "Do you want me to pick anything up from the shop",
            "I've left my keys in the car again",
            "We could go for a walk in the park if the weather is nice",
            "My favourite colour is green",
            "I don't think I can make it this evening",
            "Have you finished the homework for tomorrow",
            "I'll be home in about half an hour",
            "Sorry I didn't get back to you sooner",
            "It was really nice to catch up last night",
            "What do you want for dinner tonight",
            "I need to organise the trip for next term",
            "The meeting has been moved to Thursday afternoon",
            "Let me have a look and I'll get back to you",
            "Hope you have a good time at the party",
            "I'm not sure if I can come to the theatre",
            "Did you see the football last night",
            "Could you send me the address of the restaurant",
            "I realise it's short notice but are you free",
            "We're thinking of going to the seaside next weekend",
            "Just let me know if you need a lift",
            "I've been really busy at work this week",
            "Shall we meet at the station at half past six",
            "Don't forget to bring your passport",
            "It's my mum's birthday on Sunday",
            "I'm really looking forward to the holiday",
            "Can we talk about it tomorrow",
            "How was the film last night",
            "The kids have got a day off school on Friday",
            "Give me a ring when you get a chance",
            "I've got loads of marking to do",
            "Is it okay if I bring my brother",
            "Text me when you get to the centre",
        )

    }

    private lateinit var repository: AndroidDictionaryRepository
    private lateinit var model: BuiltInBigramModel

    @Before
    fun load() {
        val context = RuntimeEnvironment.getApplication()
        repository = AndroidDictionaryRepository(context, context.assets, UserDictionaryStore(), Locale.UK)
        runBlocking(Dispatchers.IO) { repository.loadIfNeeded() }
        model = BuiltInBigramModel.load(context.assets, Locale.UK)!!
    }

    private fun tokens(sentence: String) = sentence.split(" ").filter { it.isNotBlank() }

    private fun engineSuggestions(typed: String, limit: Int): List<SuggestionResult> =
        SuggestionEngine(repository, Locale.UK).suggest(typed, limit, true, false, false)

    /** Characters typed before [target] shows in the top three (word length if it never does). */
    private fun charsNeeded(target: String, previous: String?, atStart: Boolean, contextual: Boolean): Int {
        for (n in 1..target.length) {
            val typed = target.substring(0, n)
            if (typed == target) return n
            val raw = engineSuggestions(typed, if (contextual) 9 else 3)
            val ranked = if (!contextual) raw.take(3) else {
                val completions = when {
                    previous != null -> model.completions(previous, typed, 6)
                    atStart -> model.sentenceStartCompletions(typed, 6)
                    else -> emptyList()
                }.map { ContextualRanking.Candidate(it.word, it.probability) }
                ContextualRanking.rerankCurrentWord(
                    typed, raw,
                    { w -> if (previous != null) model.probability(previous, w) else if (atStart) model.sentenceStartProbability(w) else 0.0 },
                    emptyMap(), completions, 3, Locale.UK, { repository.isKnownWord(it) }
                )
            }
            if (ranked.any { it.candidate.equals(target, ignoreCase = true) }) return n
        }
        return target.length
    }

    @Test
    fun reportPredictionQuality() {
        var words = 0
        var letters = 0
        var nextWordHits = 0
        var nextWordCases = 0
        var plainChars = 0
        var contextChars = 0
        for (sentence in HELD_OUT) {
            val ws = tokens(sentence)
            ws.forEachIndexed { i, word ->
                val previous = ws.getOrNull(i - 1)
                words++
                letters += word.length
                if (previous != null) {
                    nextWordCases++
                    if (model.predict(previous, 3).any { it.word.equals(word, ignoreCase = true) }) nextWordHits++
                }
                plainChars += charsNeeded(word, previous, i == 0, contextual = false)
                contextChars += charsNeeded(word, previous, i == 0, contextual = true)
            }
        }
        val nextWordRate = 100.0 * nextWordHits / nextWordCases
        val plainSaving = 100.0 * (letters - plainChars) / letters
        val contextSaving = 100.0 * (letters - contextChars) / letters
        println(
            "BRITISH_EVAL words=$words nextWordTop3=%.1f%% (was 0%% before learning) ".format(nextWordRate) +
                "lettersNeededBefore=$plainChars after=$contextChars of $letters " +
                "savingBefore=%.1f%% savingAfter=%.1f%%".format(plainSaving, contextSaving)
        )
        assertTrue("next-word top-3 should be useful", nextWordRate > 25.0)
        assertTrue("context should reduce typing", contextChars < plainChars)
    }

    @Test
    fun britishSpellingsWinOverAmerican() {
        assertTrue(repository.isKnownWord("colour"))
        assertTrue(!repository.isKnownWord("color"))
        assertTrue(!repository.isKnownWord("realize"))
        assertTrue(repository.isKnownWord("among"))
        assertTrue(repository.isKnownWord("Manila"))
        for ((typed, expected) in listOf("colo" to "colour", "favo" to "favourite", "realis" to "realise", "travell" to "travelling")) {
            val suggestions = engineSuggestions(typed, 3).map { it.candidate }
            assertTrue("$typed -> $suggestions should include $expected", suggestions.contains(expected))
        }
        val assets = RuntimeEnvironment.getApplication().assets
        val preferred = LanguageOverlay.preferredSpelling(assets, Locale.UK, "Color")
        val color = ContextualRanking.withPreferredSpelling(engineSuggestions("Color", 3), preferred, 3, Locale.UK)
        assertTrue("Color -> $color should offer Colour first", color.first().candidate == "Colour")
        assertTrue(LanguageOverlay.preferredSpelling(assets, Locale.UK, "program") == null)
    }
}
