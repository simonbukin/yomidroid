package com.yomidroid.dictionary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for sharing dictionary lookup results between the accessibility service
 * and in-app UI screens (decoupled mode).
 */
object LookupResultRepository {

    private val _latestEntries = MutableStateFlow<List<DictionaryEntry>>(emptyList())
    val latestEntries: StateFlow<List<DictionaryEntry>> = _latestEntries.asStateFlow()

    private val _latestSentence = MutableStateFlow<String?>(null)
    val latestSentence: StateFlow<String?> = _latestSentence.asStateFlow()

    fun updateEntries(entries: List<DictionaryEntry>, sentence: String?) {
        _latestEntries.value = entries
        _latestSentence.value = sentence
    }

    fun clear() {
        _latestEntries.value = emptyList()
        _latestSentence.value = null
    }
}
