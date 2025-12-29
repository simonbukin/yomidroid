package com.vndict.dictionary

data class DictionaryEntry(
    val id: Long,
    val expression: String,
    val reading: String,
    val glossary: List<String>,
    val partsOfSpeech: List<String>,
    val score: Int,
    val matchedText: String = expression,
    val deinflectionPath: String = ""
)
