package com.bjp.itantra.laya

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity gate for [LayaPrompt] — the ids and the marker positions the decision head sees.
 *
 * This is the quietest way for the layer to be wrong: a prompt built with the wrong special ids,
 * or with a marker pointing one token off, produces no exception, no shape error, and no log
 * line. It produces a slightly wrong answer. So the fixture
 * (`app/src/test/resources/laya_prompt_cases.txt`, from
 * `eval/laya_mobile_assets.py --out-prompt-fixture`) records what the harness's own
 * `LayaOnnx.build` assembles for the 12 bench sentences, awkward whitespace, an empty state, an
 * astral character, and a state long enough to be truncated — and this test replays all of it.
 *
 * The fixture's `SPEC` is also checked against the `config.json` the app reads at runtime, so a
 * hand-edit of one without the other cannot pass.
 */
class LayaPromptTest {

    @Test
    fun `the fixture carries the cases it claims to`() {
        val fx = LayaFixtures.prompts()
        assertTrue("fixture only carries ${fx.prompts.size} prompts", fx.prompts.size >= 16)
        assertEquals("fixture only carries ${fx.options.size} options", 4, fx.options.size)
        assertTrue(
            "no prompt in the fixture reaches the ${fx.spec.maxLen}-token window, so truncation is untested",
            fx.prompts.any { it.ids.size == fx.spec.maxLen },
        )
        // Self-consistency of the fixture itself: every marker must point at a <mask> id.
        for (p in fx.prompts) {
            assertEquals("marker count for '${p.state.take(40)}'", fx.options.size, p.markers.size)
            for (m in p.markers) {
                assertEquals("ids[$m] for '${p.state.take(40)}' is not <mask>", fx.spec.mask, p.ids[m])
            }
        }
    }

    @Test
    fun `every fixture prompt is built id-for-id with the same markers`() {
        val fx = LayaFixtures.prompts()
        val bpe = LayaFixtures.tokenizer().bpe
        for (p in fx.prompts) {
            val label = "prompt for '${p.state.take(40).replace("\n", "\\n")}'"
            val built = LayaPrompt.build(bpe, p.state, fx.spec, fx.question, fx.options)
            assertNotNull("$label: build returned null", built)
            assertEquals("$label: ids", p.ids, built!!.ids.toList())
            assertEquals("$label: markers", p.markers, built.markers.toList())
        }
    }

    @Test
    fun `a window that cuts off the last marker is refused instead of answered`() {
        // Marker positions depend only on the question and the options, so they are the same for
        // every state; `first()` is safe to read them from.
        val bpe = LayaFixtures.tokenizer().bpe
        val fx = LayaFixtures.prompts()
        val last = fx.prompts.first().markers.last()

        // A window ending exactly at the last marker loses it, and a head fed such a prompt would
        // still return confident numbers for the options whose markers survived. The builder must
        // say so instead of letting the caller guess.
        val cut = LayaPrompt.Spec(fx.spec.bos, fx.spec.eos, fx.spec.mask, maxLen = last)
        assertNull("a window ending at the last marker must be refused", LayaPrompt.build(bpe, "बाढ़", cut))

        val kept = LayaPrompt.build(bpe, "बाढ़", cutOf(fx, last + 1))
        assertNotNull("a window one past the last marker must build", kept)
        assertEquals(last + 1, kept!!.ids.size)
    }

    private fun cutOf(fx: LayaFixtures.PromptFixture, maxLen: Int) =
        LayaPrompt.Spec(fx.spec.bos, fx.spec.eos, fx.spec.mask, maxLen)

    @Test
    fun `the runtime config matches the fixture's spec`() {
        val fx = LayaFixtures.prompts()
        val text = configFile().readText()
        assertEquals("config.json bos", fx.spec.bos, intIn(text, "bos"))
        assertEquals("config.json eos", fx.spec.eos, intIn(text, "eos"))
        assertEquals("config.json mask", fx.spec.mask, intIn(text, "mask"))
        assertEquals("config.json max_len", fx.spec.maxLen, intIn(text, "max_len"))
    }

    /**
     * `src/main/assets/laya/config.json`, found by walking up from the test's working directory.
     * Read as text with a regex rather than `org.json`: this is a unit test, where the framework's
     * JSONObject is a stub that throws.
     */
    private fun configFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(4) {
            val f = File(dir, "src/main/assets/laya/config.json")
            if (f.isFile) return f
            dir = dir?.parentFile
        }
        error("src/main/assets/laya/config.json not found from ${System.getProperty("user.dir")}")
    }

    private fun intIn(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
            ?: error("config.json has no integer \"$key\"")
}
