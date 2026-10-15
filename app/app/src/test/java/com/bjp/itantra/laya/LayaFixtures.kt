package com.bjp.itantra.laya

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals

/**
 * Readers for the two generated fixtures under `app/src/test/resources/`. Both are line-oriented
 * with base64 fields and no JSON, so nothing here needs a parser dependency.
 *
 * `laya_tokenizer_cases.txt` — written by `eval/laya_mobile_assets.py`, full vocab and merges plus
 * one `CASE` per recorded text: the pruned tokenizer as *data*, so [LayaBpeTest] can build the
 * app's tokenizer without the app.
 *
 * `laya_prompt_cases.txt` — written by the same script with `--out-prompt-fixture`: the special
 * ids, the question, the four options, and the assembled prompt (ids + marker positions) for
 * every case, produced by the harness's own `LayaOnnx.build`. [LayaPromptTest] replays it against
 * [LayaPrompt.build].
 */
object LayaFixtures {

    /** A recorded `CASE` from the tokenizer fixture. */
    class TokenizerCase(val text: String, val ids: List<Int>, val tokens: List<String>)

    class TokenizerFixture(val bpe: LayaBpe, val cases: List<TokenizerCase>, val lines: Int)

    /** A recorded `PROMPT` from the prompt fixture. */
    class PromptCase(val state: String, val ids: List<Int>, val markers: List<Int>)

    class PromptFixture(
        val spec: LayaPrompt.Spec,
        val question: String,
        val options: List<String>,
        val prompts: List<PromptCase>,
    )

    fun tokenizer(): TokenizerFixture {
        val vocabLines = StringBuilder()
        val mergeLines = StringBuilder()
        val cases = ArrayList<TokenizerCase>()
        val lines = forEachRecord("laya_tokenizer_cases.txt") { kind, rest, lineNo ->
            when (kind) {
                "VOCAB" -> vocabLines.append(rest).append('\n')
                "MERGES" -> mergeLines.append(rest).append('\n')
                "CASE" -> {
                    val parts = rest.split(' ')
                    assertEquals("line $lineNo: a CASE record has 3 fields", 3, parts.size)
                    cases.add(
                        TokenizerCase(
                            text = field(parts[0]),
                            ids = idList(parts[1]),
                            tokens = if (parts[2] == "-") emptyList()
                            else field(parts[2]).split('\u0001'),
                        )
                    )
                }
                else -> error("line $lineNo: unknown record type '$kind'")
            }
        }
        val bpe = LayaBpe.fromReaders(
            BufferedReader(StringReader(vocabLines.toString())),
            BufferedReader(StringReader(mergeLines.toString())),
        )
        return TokenizerFixture(bpe, cases, lines)
    }

    fun prompts(): PromptFixture {
        var spec: LayaPrompt.Spec? = null
        var question: String? = null
        val options = ArrayList<String>()
        val prompts = ArrayList<PromptCase>()
        forEachRecord("laya_prompt_cases.txt") { kind, rest, lineNo ->
            when (kind) {
                "SPEC" -> {
                    val p = rest.split(' ')
                    assertEquals("line $lineNo: a SPEC record has 4 fields", 4, p.size)
                    spec = LayaPrompt.Spec(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt())
                }
                "QUESTION" -> question = field(rest)
                "OPTION" -> options.add(field(rest))
                "PROMPT" -> {
                    val p = rest.split(' ')
                    assertEquals("line $lineNo: a PROMPT record has 3 fields", 3, p.size)
                    prompts.add(PromptCase(field(p[0]), idList(p[1]), idList(p[2])))
                }
                else -> error("line $lineNo: unknown record type '$kind'")
            }
        }
        return PromptFixture(
            spec ?: error("laya_prompt_cases.txt has no SPEC record"),
            question ?: error("laya_prompt_cases.txt has no QUESTION record"),
            options,
            prompts,
        )
    }

    /**
     * Read records from a classpath fixture. Comments and blank lines are skipped; every record is
     * `<KIND> <rest>`; a malformed line is an error, never silently ignored.
     */
    private fun forEachRecord(name: String, body: (String, String, Int) -> Unit): Int {
        val stream = LayaFixtures::class.java.getResourceAsStream("/$name")
            ?: error("$name is missing from test resources")
        var lineNo = 0
        stream.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            lineNo++
            if (raw.isNotEmpty() && raw[0] != '#') {
                val sp = raw.indexOf(' ')
                if (sp <= 0) error("$name line $lineNo: malformed record '$raw'")
                body(raw.substring(0, sp), raw.substring(sp + 1), lineNo)
            }
        }
        return lineNo
    }

    /** A fixture field; "-" is the writer's marker for the empty string. */
    private fun field(s: String): String = if (s == "-") "" else LayaBpe.decodeBase64Utf8(s)

    /** A comma-separated id list; "-" is an empty one. */
    private fun idList(s: String): List<Int> =
        if (s == "-") emptyList() else s.split(',').map { it.toInt() }
}
