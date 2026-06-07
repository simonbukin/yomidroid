package com.yomidroid

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yomidroid.dictionary.HoshiDicts
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device validation of the Hoshidicts JNI round-trip against the real
 * Jitendex dictionary.
 *
 * Prereq: push the dictionary first:
 *   adb push jitendex-yomitan.zip /data/local/tmp/jitendex-yomitan.zip
 *
 * Run:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.yomidroid.HoshiDictsInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class HoshiDictsInstrumentedTest {

    private val tag = "HoshiTest"

    /**
     * Diagnoses the user-installed custom dictionary in place: loads it from the
     * app's own files dir and probes both Japanese and English headwords, so we
     * can tell whether it's matching (and in which direction).
     */
    @Test
    fun verifyInstalledCustomDicts() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dictsRoot = File(ctx.filesDir, "dictionaries")
        Log.i(tag, "installed dict folders: ${dictsRoot.list()?.toList()}")

        // Load every term dictionary folder (skip freq/pitch/kanji helpers by
        // probing all and reporting what matches).
        val termFolders = dictsRoot.listFiles()?.flatMap { dir ->
            dir.listFiles()?.filter { File(it, ".hoshidicts_1").exists() }?.toList() ?: emptyList()
        } ?: emptyList()
        assumeTrue("No imported dictionaries on device", termFolders.isNotEmpty())

        for (folder in termFolders) {
            HoshiDicts.load(listOf(folder.absolutePath), emptyList(), emptyList())
            val probes = listOf("水", "日本", "食べる", "water", "japan", "eat", "green", "goddess")
            val hits = probes.associateWith { HoshiDicts.lookup(it).size }
            Log.i(tag, "DICT '${folder.name}': ${hits.filterValues { it > 0 }}")
        }
    }

    /**
     * Confirms a Japanese→English custom dictionary surfaces on Japanese lookups
     * (the direction the user actually wants). Push the dict first:
     *   adb push "[Bilingual] 新和英.zip" /data/local/tmp/waei.zip
     */
    @Test
    fun importAndLookupCustomWaei() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zip = File("/data/local/tmp/waei.zip")
        assumeTrue("Push a 和英 dict to /data/local/tmp/waei.zip to run this", zip.exists())

        val outDir = File(ctx.cacheDir, "waei_test").apply { deleteRecursively(); mkdirs() }
        val res = HoshiDicts.import(zip.absolutePath, outDir.absolutePath)
        Log.i(tag, "custom import success=${res.success} title='${res.title}' terms=${res.termCount}")
        assertTrue("import failed: ${res.errors}", res.success)

        HoshiDicts.load(listOf(File(outDir, res.title).absolutePath), emptyList(), emptyList())
        for (word in listOf("水", "日本", "食べる", "綺麗")) {
            val results = HoshiDicts.lookup(word)
            Log.i(tag, "custom 和英 '$word' -> ${results.size}; first gloss=${results.firstOrNull()?.glossaries?.firstOrNull()?.glossaryJson?.take(80)}")
        }
        assertTrue("expected Japanese-headword hits", HoshiDicts.lookup("水").isNotEmpty())
    }

    @Test
    fun importAndLookupJitendex() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zip = File("/data/local/tmp/jitendex-yomitan.zip")
        // Dev-only test: skipped (not failed) when the dictionary isn't present,
        // so it never breaks a CI/connectedCheck run on a clean device.
        assumeTrue("Push jitendex-yomitan.zip to /data/local/tmp to run this test", zip.exists())

        val outDir = File(ctx.cacheDir, "hoshi_test").apply { deleteRecursively(); mkdirs() }

        val importStart = System.currentTimeMillis()
        val res = HoshiDicts.import(zip.absolutePath, outDir.absolutePath)
        val importMs = System.currentTimeMillis() - importStart
        Log.i(tag, "import success=${res.success} title='${res.title}' terms=${res.termCount} " +
                "freq=${res.freqCount} pitch=${res.pitchCount} meta=${res.metaCount} in ${importMs}ms errors=${res.errors}")
        assertTrue("import failed: ${res.errors}", res.success)
        assertTrue("expected term entries", res.termCount > 0)

        val folder = File(outDir, res.title)
        assertTrue("imported folder marker missing", File(folder, ".hoshidicts_1").exists())
        val sizeKb = folder.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / 1024
        Log.i(tag, "imported folder=${folder.name} size=${sizeKb}KB (source zip=${zip.length() / 1024}KB)")

        // Exercise the atomic load path the app uses (not reset+add).
        HoshiDicts.load(listOf(folder.absolutePath), emptyList(), emptyList())

        // Conjugated form — exercises the deconjugator (食べる ← 食べた).
        for (word in listOf("食べる", "食べた", "日本語", "見ない")) {
            val lookupStart = System.currentTimeMillis()
            val results = HoshiDicts.lookup(word, maxResults = 16, scanLength = 20)
            val lookupUs = (System.currentTimeMillis() - lookupStart)
            val first = results.firstOrNull()
            Log.i(tag, "lookup '$word' -> ${results.size} results in ${lookupUs}ms; " +
                    "first=${first?.expression}/${first?.reading} matched='${first?.matched}' " +
                    "steps=${first?.steps} gloss=${first?.glossaries?.firstOrNull()?.glossaryJson?.take(100)}")
            assertTrue("expected results for '$word'", results.isNotEmpty())
        }

        // Warm-latency microbenchmark: the parse tab / full-screen scan issues one
        // lookup per character position, so per-lookup cost drives that path.
        val words = listOf(
            "食べる", "日本語", "見ない", "学校", "綺麗", "食べさせられた",
            "本", "美味しかった", "走って", "図書館", "勉強した", "ありがとう"
        )
        repeat(60) { HoshiDicts.lookup(words[it % words.size]) } // warmup
        val micros = ArrayList<Long>(300)
        repeat(300) {
            val w = words[it % words.size]
            val t0 = System.nanoTime()
            HoshiDicts.lookup(w)
            micros.add((System.nanoTime() - t0) / 1000)
        }
        micros.sort()
        Log.i(tag, "WARM lookup latency over ${micros.size}: avg=${micros.average().toInt()}us " +
                "p50=${micros[micros.size / 2]}us p95=${micros[micros.size * 95 / 100]}us max=${micros.last()}us")

        // Exact-query path (findExact) used by some re-lookups.
        val exact = HoshiDicts.query("日本語")
        Log.i(tag, "exact query '日本語' -> ${exact.size} results")
        assertTrue(exact.isNotEmpty())

        // Kanji → example-words reverse index: build cost + query.
        val idxFile = File(outDir, com.yomidroid.data.KanjiWordIndex.FILE_NAME)
        val idxStart = System.currentTimeMillis()
        com.yomidroid.data.KanjiWordIndex.build(zip, idxFile)
        Log.i(tag, "kanji index built in ${System.currentTimeMillis() - idxStart}ms, size=${idxFile.length() / 1024}KB")
        val index = com.yomidroid.data.KanjiWordIndex.load(idxFile)
        for (k in listOf("食", "水", "語", "学")) {
            val words = index[k] ?: emptyList()
            Log.i(tag, "words containing '$k': ${words.take(8)} (${words.size} total)")
            assertTrue("expected example words for '$k'", words.isNotEmpty())
        }
    }
}
