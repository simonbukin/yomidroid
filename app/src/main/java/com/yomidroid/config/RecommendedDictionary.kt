package com.yomidroid.config

enum class DictCategory { TERMS, KANJI, FREQUENCY, PRONUNCIATION }

data class RecommendedDictionary(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val type: DictSourceType,
    val category: DictCategory,
    val sizeEstimate: String
)

object RecommendedDictionaryCatalog {
    val dictionaries = listOf(
        RecommendedDictionary(
            id = "jitendex",
            name = "Jitendex",
            description = "Comprehensive JP-EN dictionary based on JMdict",
            downloadUrl = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
            type = DictSourceType.DICTIONARY,
            category = DictCategory.TERMS,
            sizeEstimate = "~45 MB"
        ),
        RecommendedDictionary(
            id = "jmnedict",
            name = "JMnedict",
            description = "Japanese names and places",
            downloadUrl = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            type = DictSourceType.NAMES,
            category = DictCategory.TERMS,
            sizeEstimate = "~12 MB"
        ),
        RecommendedDictionary(
            id = "kanjidic",
            name = "KANJIDIC",
            description = "Kanji meanings, readings, and stroke counts",
            downloadUrl = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip",
            type = DictSourceType.KANJI,
            category = DictCategory.KANJI,
            sizeEstimate = "~4 MB"
        ),
        RecommendedDictionary(
            id = "innocent_corpus",
            name = "Innocent Corpus",
            description = "Word frequency data from 5000+ visual novels",
            downloadUrl = "https://github.com/FooSoft/yomichan/raw/dictionaries/innocent_corpus.zip",
            type = DictSourceType.FREQUENCY,
            category = DictCategory.FREQUENCY,
            sizeEstimate = "~5 MB"
        ),
        RecommendedDictionary(
            id = "jpdb_freq",
            name = "JPDB Frequency",
            description = "Frequency data from jpdb.io",
            downloadUrl = "https://github.com/MarvNC/jpdb-freq-list/releases/download/2022-05-09/Freq.JPDB_2022-05-10T03_27_02.930Z.zip",
            type = DictSourceType.FREQUENCY,
            category = DictCategory.FREQUENCY,
            sizeEstimate = "~2 MB"
        ),
        RecommendedDictionary(
            id = "kanjium_pitch",
            name = "Kanjium Pitch Accents",
            description = "Pitch accent data for Japanese words",
            downloadUrl = "https://github.com/toasted-nutbread/yomichan-pitch-accent-dictionary/releases/latest/download/kanjium_pitch_accents.zip",
            type = DictSourceType.PITCH,
            category = DictCategory.PRONUNCIATION,
            sizeEstimate = "~3 MB"
        )
    )
}
