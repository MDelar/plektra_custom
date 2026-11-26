package rocks.pkb.plektra.core.suggestions

import android.os.Looper
import android.view.KeyEvent
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Plektra Custom: accepting a suggestion from the bar must not leave it showing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuggestionAcceptedTest {

    private lateinit var store: UserNGramStore
    private lateinit var controller: SuggestionController
    private val snapshots = mutableListOf<List<SuggestionResult>>()
    private val tag = Locale.GERMANY.toLanguageTag()

    @Before
    fun setUp() {
        store = UserNGramStore(RuntimeEnvironment.getApplication()).apply { clearAll() }
        val context = RuntimeEnvironment.getApplication()
        val repository = FakeDictionaryRepository().apply {
            isReady = true
            // Common words that the starter fill-in would otherwise offer.
            addTestEntry("hallo", 250)
            addTestEntry("welt", 200)
            addTestEntry("ich", 240)
            addTestEntry("bin", 230)
        }
        controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> repository },
            nextWordPredictorOverride = NextWordPredictor(store, asyncLearning = false),
            builtInModelLoader = { null }
        )
    }

    @After
    fun tearDown() {
        controller.flushNextWordLearningForTests()
        controller.destroy()
        store.clearAll()
        store.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun typeLetters(text: String) {
        text.forEach { controller.onCharacterCommitted(it.toString(), null) }
        idle()
    }

    private fun typeWordAndSpace(word: String) {
        typeLetters(word)
        controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, null, ' ')
        idle()
    }

    @Test
    fun acceptedWordIsNotShownAgain() {
        typeLetters("hal")
        controller.onSuggestionAccepted("hallo", followedBySpace = true)
        idle()
        val bar = snapshots.last().map { it.candidate.lowercase() }
        assertFalse("bar after accepting: $bar", "hallo" in bar)
        assertTrue(bar.isNotEmpty())
    }

    @Test
    fun acceptedWordShowsLearnedNextWord() {
        store.learn(tag, "hallo", "welt")
        typeLetters("hal")
        controller.onSuggestionAccepted("hallo", followedBySpace = true)
        idle()
        assertEquals("welt", snapshots.last().first().candidate)
        assertEquals(SuggestionKind.NEXT_WORD, snapshots.last().first().kind)
    }

    @Test
    fun acceptedWordIsLearnedAfterThePreviousWord() {
        typeWordAndSpace("ich")
        typeLetters("b")
        controller.onSuggestionAccepted("bin", followedBySpace = true)
        idle()
        controller.flushNextWordLearningForTests()
        assertEquals(listOf("bin"), store.predict(tag, "ich", 3).map { it.word })
    }

    @Test
    fun withoutSpaceTheBarIsCleared() {
        typeLetters("hal")
        controller.onSuggestionAccepted("hallo", followedBySpace = false)
        idle()
        assertTrue(snapshots.last().isEmpty())
    }
}
