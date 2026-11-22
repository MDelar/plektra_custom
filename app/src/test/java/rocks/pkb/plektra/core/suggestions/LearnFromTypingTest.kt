package rocks.pkb.plektra.core.suggestions

import android.os.Looper
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Plektra Custom: "Learn from my typing" switch, incognito fields and the size cap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LearnFromTypingTest {

    private lateinit var store: UserNGramStore
    private var learningEnabled = true
    private var allowedInField = true
    private val controllers = mutableListOf<SuggestionController>()

    @Before
    fun setUp() {
        store = UserNGramStore(RuntimeEnvironment.getApplication()).apply { clearAll() }
    }

    @After
    fun tearDown() {
        controllers.forEach { it.flushNextWordLearningForTests(); it.destroy() }
        store.clearAll()
        store.close()
    }

    private fun controller(): SuggestionController {
        val context = RuntimeEnvironment.getApplication()
        val repository = FakeDictionaryRepository().apply { isReady = true }
        return SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
            onSuggestionsUpdated = { },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> repository },
            nextWordPredictorOverride = NextWordPredictor(store, asyncLearning = false),
            isLearningEnabled = { learningEnabled },
            isLearningAllowedInField = { allowedInField },
            builtInModelLoader = { null }
        ).also { controllers.add(it) }
    }

    private fun type(controller: SuggestionController, text: String) {
        text.split(" ").forEach { word ->
            word.forEach { controller.onCharacterCommitted(it.toString(), null) }
            controller.onBoundaryKey(android.view.KeyEvent.KEYCODE_SPACE, null, null, ' ')
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun learned(prefix: String) = store.predict(Locale.GERMANY.toLanguageTag(), prefix, 10).map { it.word }

    @Test
    fun learnsWhenEnabled() {
        type(controller(), "hallo welt")
        assertEquals(listOf("welt"), learned("hallo"))
    }

    @Test
    fun doesNotLearnWhenSwitchedOff() {
        learningEnabled = false
        type(controller(), "hallo welt")
        assertTrue(learned("hallo").isEmpty())
    }

    @Test
    fun doesNotLearnInIncognitoFields() {
        allowedInField = false
        type(controller(), "geheim passwort")
        assertTrue(learned("geheim").isEmpty())
    }

    @Test
    fun pruneKeepsTheMostUsedPairs() {
        val tag = "en-GB"
        repeat(30) { i -> store.learn(tag, "w$i", "next") }
        repeat(5) { store.learn(tag, "keep", "me") }
        store.pruneIfNeeded(maxRows = 20, targetRows = 10)
        assertEquals(10L, store.learnedPairCount())
        assertEquals(listOf("me"), store.predict(tag, "keep", 3).map { it.word })
    }
}
