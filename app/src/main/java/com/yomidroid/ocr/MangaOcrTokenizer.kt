package com.yomidroid.ocr

import java.io.File

/**
 * Tokenizer for kha-white/manga-ocr. Loads vocab.txt and decodes token IDs to
 * text, then applies the same post-processing the reference Python pipeline
 * does in `manga_ocr/ocr.py`:
 *   text = "".join(text.split())             # strip ALL whitespace
 *   text = text.replace("…", "...")
 *   text = re.sub("[・.]{2,}", lambda x: "."*len(x.group()), text)
 *   text = jaconv.h2z(text, ascii=True, digit=True)
 */
class MangaOcrTokenizer(vocabPath: String) {

    companion object {
        const val PAD_ID = 0
        const val UNK_ID = 1
        const val CLS_ID = 2  // start token
        const val SEP_ID = 3  // end-of-sequence token
        const val MASK_ID = 4
    }

    private val vocab: List<String> = File(vocabPath).readLines()

    fun decode(tokenIds: List<Int>): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            // koharu's reference ONNX impl filters all ids < 5 (PAD, UNK, CLS,
            // SEP, MASK) — the unused0..N tokens that follow are also harmless
            // to skip but actually appear in vocab.txt; gating only on the
            // five named specials lets through any meaningful token above 4.
            if (id == PAD_ID || id == UNK_ID || id == CLS_ID || id == SEP_ID || id == MASK_ID) continue
            if (id < 0 || id >= vocab.size) continue
            val token = vocab[id]
            if (token.startsWith("##")) sb.append(token.substring(2))
            else sb.append(token)
        }
        return postProcess(sb.toString())
    }

    private fun postProcess(raw: String): String {
        var s = WHITESPACE.split(raw).joinToString("")
        s = s.replace("…", "...")
        s = DOT_RUN.replace(s) { match -> ".".repeat(match.value.length) }
        s = halfToFullAscii(s)
        return s
    }

    private fun halfToFullAscii(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == ' ' -> sb.append('　')                      // ideographic space
                c.code in 0x21..0x7E -> sb.append((c.code + 0xFEE0).toChar())  // ! .. ~
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val WHITESPACE = Regex("\\s+")
    private val DOT_RUN = Regex("[・.]{2,}")
}
