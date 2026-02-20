package com.yomidroid.ocr

import java.io.File

/**
 * Tokenizer for manga-ocr model. Loads vocab.txt and decodes token IDs to text.
 * Character-level Japanese BERT tokenizer with WordPiece continuation tokens.
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

    /**
     * Decode a list of token IDs to text.
     * Strips special tokens, removes ## prefix from continuation tokens,
     * and concatenates without spaces (Japanese character-level).
     */
    fun decode(tokenIds: List<Int>): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id == PAD_ID || id == CLS_ID || id == SEP_ID || id == MASK_ID || id == UNK_ID) continue
            if (id < 0 || id >= vocab.size) continue
            val token = vocab[id]
            if (token.startsWith("##")) {
                sb.append(token.substring(2))
            } else {
                sb.append(token)
            }
        }
        return sb.toString()
    }
}
