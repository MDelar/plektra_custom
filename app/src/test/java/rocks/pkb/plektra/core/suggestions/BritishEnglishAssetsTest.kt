package rocks.pkb.plektra.core.suggestions

import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plektra Custom: checks on the generated British English assets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BritishEnglishAssetsTest {

    private fun asset(path: String): File {
        val candidates = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path"))
        return candidates.first { it.exists() }
    }

    private val model by lazy { asset("common/ngrams/en_gb_bigrams.bin").inputStream().use { BuiltInBigramModel.read(it) } }

    @Test
    fun modelPredictsEverydayNextWords() {
        assertEquals("you", model.predict("see", 1).first().word)
        assertEquals("me", model.predict("let", 1).first().word)
        assertTrue(model.predict("don't", 5).map { it.word }.contains("know"))
        assertTrue(model.predict("I'm", 5).map { it.word }.contains("going"))
        assertTrue(model.probability("speak", "soon") > 0.0)
        assertEquals(0.0, model.probability("see", "zzzz"), 0.0)
    }

    @Test
    fun modelIsCaseAndApostropheInsensitiveForContext() {
        assertEquals(model.predict("i'm", 3), model.predict("I’m", 3))
    }

    @Test
    fun modelCompletesFromPrefixInContext() {
        assertEquals("you", model.completions("see", "y", 3).first().word)
        assertTrue(model.completions("good", "mo", 3).map { it.word }.contains("morning"))
        assertTrue(model.completions("see", "you", 3).none { it.word == "you" })
    }

    @Test
    fun modelUsesBritishSpellings() {
        val afterMy = model.predict("my", 40).map { it.word }
        assertTrue(afterMy.contains("favourite"))
        assertFalse(afterMy.contains("favorite"))
        assertTrue(model.predict("sentence", 0).isEmpty())
        assertTrue(model.predictSentenceStart(5).map { it.word }.contains("I"))
    }

    @Test
    fun overlayRemovesUsSpellingsAndKeepsBritishOnes() {
        val overlay = asset("common/dictionaries/en_gb_overlay.tsv").inputStream().use { LanguageOverlay.parse(it) }
        for (us in listOf("color", "favorite", "realize", "center", "traveling", "mom")) {
            assertTrue("$us should be removed", us in overlay.removals)
        }
        for (uk in listOf("colour", "favourite", "realise", "centre", "travelling", "mum")) {
            assertTrue("$uk should be set", overlay.frequencies.containsKey(uk))
        }
        // Words that are also normal British usage must survive.
        for (kept in listOf("among", "learned", "program", "while", "Manila", "Dora", "sulfur")) {
            assertFalse("$kept must not be removed", kept in overlay.removals)
        }
        assertEquals(LanguageOverlay.assetPathFor(Locale.UK), LanguageOverlay.assetPathFor(Locale.US))
        assertEquals(null, LanguageOverlay.assetPathFor(Locale.ITALIAN))
    }

    @Test
    fun spellingCorrectionsMapUsToUk() {
        val json = JSONObject(asset("common/autocorrect/en_gb_spelling.json").readText())
        assertEquals("colour", json.getString("color"))
        assertEquals("realise", json.getString("realize"))
        assertEquals("travelling", json.getString("traveling"))
        assertFalse(json.has("program"))
        assertFalse(json.has("among"))
    }
}
