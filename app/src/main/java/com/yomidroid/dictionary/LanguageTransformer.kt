package com.yomidroid.dictionary

/**
 * Language transformer for Japanese deinflection.
 * Ported from Yomitan's japanese-transforms.js (GPL-3.0)
 * Copyright (C) 2024-2025 Yomitan Authors
 */

data class SuffixRule(
    val inflectedSuffix: String,
    val baseSuffix: String,
    val conditionsIn: Set<String>,
    val conditionsOut: Set<String>
)

data class Transform(
    val name: String,
    val rules: List<SuffixRule>
)

data class Variant(
    val text: String,
    val conditions: Set<String>,
    val path: List<String>
)

data class Condition(
    val name: String,
    val isDictionaryForm: Boolean,
    val subConditions: Set<String> = emptySet()
)

class LanguageTransformer {

    companion object {
        private val ikuVerbs = listOf("いく", "行く", "逝く", "往く")
        private val godanUSpecialVerbs = listOf(
            "こう", "とう", "請う", "乞う", "恋う", "問う",
            "訪う", "宣う", "曰う", "給う", "賜う", "揺蕩う"
        )
        private val fuVerbTeConjugations = listOf(
            "のたまう" to "のたもう",
            "たまう" to "たもう",
            "たゆたう" to "たゆとう"
        )

        private fun irregularVerbSuffixInflections(
            suffix: String,
            conditionsIn: Set<String>,
            conditionsOut: Set<String>
        ): List<SuffixRule> {
            val inflections = mutableListOf<SuffixRule>()

            for (verb in ikuVerbs) {
                inflections.add(SuffixRule(
                    "${verb[0]}っ$suffix", verb, conditionsIn, conditionsOut
                ))
            }
            for (verb in godanUSpecialVerbs) {
                inflections.add(SuffixRule(
                    "$verb$suffix", verb, conditionsIn, conditionsOut
                ))
            }
            for ((verb, teRoot) in fuVerbTeConjugations) {
                inflections.add(SuffixRule(
                    "$teRoot$suffix", verb, conditionsIn, conditionsOut
                ))
            }
            return inflections
        }

        private fun s(
            inflectedSuffix: String,
            baseSuffix: String,
            conditionsIn: List<String>,
            conditionsOut: List<String>
        ) = SuffixRule(inflectedSuffix, baseSuffix, conditionsIn.toSet(), conditionsOut.toSet())
    }

    val conditions = mapOf(
        "v" to Condition("Verb", false, setOf("v1", "v5", "vk", "vs", "vz")),
        "v1" to Condition("Ichidan verb", true, setOf("v1d", "v1p")),
        "v1d" to Condition("Ichidan verb, dictionary form", false),
        "v1p" to Condition("Ichidan verb, progressive form", false),
        "v5" to Condition("Godan verb", true, setOf("v5d", "v5s")),
        "v5d" to Condition("Godan verb, dictionary form", false),
        "v5s" to Condition("Godan verb, short causative form", false, setOf("v5ss", "v5sp")),
        "v5ss" to Condition("Godan verb, short causative さす", false),
        "v5sp" to Condition("Godan verb, short causative す", false),
        "vk" to Condition("Kuru verb", true),
        "vs" to Condition("Suru verb", true),
        "vz" to Condition("Zuru verb", true),
        "adj-i" to Condition("i-adjective", true),
        "-ます" to Condition("Polite -ます", false),
        "-ません" to Condition("Polite negative -ません", false),
        "-て" to Condition("te-form", false),
        "-ば" to Condition("ba-conditional", false),
        "-く" to Condition("adverbial -く", false),
        "-た" to Condition("ta-form", false),
        "-ん" to Condition("n-negative", false),
        "-なさい" to Condition("nasai-imperative", false),
        "-ゃ" to Condition("ya-conditional", false)
    )

    val transforms: List<Transform> = listOf(
        // -ば conditional
        Transform("-ば", listOf(
            s("ければ", "い", listOf("-ば"), listOf("adj-i")),
            s("えば", "う", listOf("-ば"), listOf("v5")),
            s("けば", "く", listOf("-ば"), listOf("v5")),
            s("げば", "ぐ", listOf("-ば"), listOf("v5")),
            s("せば", "す", listOf("-ば"), listOf("v5")),
            s("てば", "つ", listOf("-ば"), listOf("v5")),
            s("ねば", "ぬ", listOf("-ば"), listOf("v5")),
            s("べば", "ぶ", listOf("-ば"), listOf("v5")),
            s("めば", "む", listOf("-ば"), listOf("v5")),
            s("れば", "る", listOf("-ば"), listOf("v1", "v5", "vk", "vs", "vz")),
            s("れば", "", listOf("-ば"), listOf("-ます"))
        )),

        // -ゃ contraction of -ば
        Transform("-ゃ", listOf(
            s("けりゃ", "ければ", listOf("-ゃ"), listOf("-ば")),
            s("きゃ", "ければ", listOf("-ゃ"), listOf("-ば")),
            s("や", "えば", listOf("-ゃ"), listOf("-ば")),
            s("きゃ", "けば", listOf("-ゃ"), listOf("-ば")),
            s("ぎゃ", "げば", listOf("-ゃ"), listOf("-ば")),
            s("しゃ", "せば", listOf("-ゃ"), listOf("-ば")),
            s("ちゃ", "てば", listOf("-ゃ"), listOf("-ば")),
            s("にゃ", "ねば", listOf("-ゃ"), listOf("-ば")),
            s("びゃ", "べば", listOf("-ゃ"), listOf("-ば")),
            s("みゃ", "めば", listOf("-ゃ"), listOf("-ば")),
            s("りゃ", "れば", listOf("-ゃ"), listOf("-ば"))
        )),

        // -ちゃ contraction of ては
        Transform("-ちゃ", listOf(
            s("ちゃ", "る", listOf("v5"), listOf("v1")),
            s("いじゃ", "ぐ", listOf("v5"), listOf("v5")),
            s("いちゃ", "く", listOf("v5"), listOf("v5")),
            s("しちゃ", "す", listOf("v5"), listOf("v5")),
            s("っちゃ", "う", listOf("v5"), listOf("v5")),
            s("っちゃ", "く", listOf("v5"), listOf("v5")),
            s("っちゃ", "つ", listOf("v5"), listOf("v5")),
            s("っちゃ", "る", listOf("v5"), listOf("v5")),
            s("んじゃ", "ぬ", listOf("v5"), listOf("v5")),
            s("んじゃ", "ぶ", listOf("v5"), listOf("v5")),
            s("んじゃ", "む", listOf("v5"), listOf("v5")),
            s("じちゃ", "ずる", listOf("v5"), listOf("vz")),
            s("しちゃ", "する", listOf("v5"), listOf("vs")),
            s("為ちゃ", "為る", listOf("v5"), listOf("vs")),
            s("きちゃ", "くる", listOf("v5"), listOf("vk")),
            s("来ちゃ", "来る", listOf("v5"), listOf("vk")),
            s("來ちゃ", "來る", listOf("v5"), listOf("vk"))
        )),

        // -ちゃう contraction of てしまう
        Transform("-ちゃう", listOf(
            s("ちゃう", "る", listOf("v5"), listOf("v1")),
            s("いじゃう", "ぐ", listOf("v5"), listOf("v5")),
            s("いちゃう", "く", listOf("v5"), listOf("v5")),
            s("しちゃう", "す", listOf("v5"), listOf("v5")),
            s("っちゃう", "う", listOf("v5"), listOf("v5")),
            s("っちゃう", "く", listOf("v5"), listOf("v5")),
            s("っちゃう", "つ", listOf("v5"), listOf("v5")),
            s("っちゃう", "る", listOf("v5"), listOf("v5")),
            s("んじゃう", "ぬ", listOf("v5"), listOf("v5")),
            s("んじゃう", "ぶ", listOf("v5"), listOf("v5")),
            s("んじゃう", "む", listOf("v5"), listOf("v5")),
            s("じちゃう", "ずる", listOf("v5"), listOf("vz")),
            s("しちゃう", "する", listOf("v5"), listOf("vs")),
            s("為ちゃう", "為る", listOf("v5"), listOf("vs")),
            s("きちゃう", "くる", listOf("v5"), listOf("vk")),
            s("来ちゃう", "来る", listOf("v5"), listOf("vk")),
            s("來ちゃう", "來る", listOf("v5"), listOf("vk"))
        )),

        // -ちまう contraction of てしまう
        Transform("-ちまう", listOf(
            s("ちまう", "る", listOf("v5"), listOf("v1")),
            s("いじまう", "ぐ", listOf("v5"), listOf("v5")),
            s("いちまう", "く", listOf("v5"), listOf("v5")),
            s("しちまう", "す", listOf("v5"), listOf("v5")),
            s("っちまう", "う", listOf("v5"), listOf("v5")),
            s("っちまう", "く", listOf("v5"), listOf("v5")),
            s("っちまう", "つ", listOf("v5"), listOf("v5")),
            s("っちまう", "る", listOf("v5"), listOf("v5")),
            s("んじまう", "ぬ", listOf("v5"), listOf("v5")),
            s("んじまう", "ぶ", listOf("v5"), listOf("v5")),
            s("んじまう", "む", listOf("v5"), listOf("v5")),
            s("じちまう", "ずる", listOf("v5"), listOf("vz")),
            s("しちまう", "する", listOf("v5"), listOf("vs")),
            s("為ちまう", "為る", listOf("v5"), listOf("vs")),
            s("きちまう", "くる", listOf("v5"), listOf("vk")),
            s("来ちまう", "来る", listOf("v5"), listOf("vk")),
            s("來ちまう", "來る", listOf("v5"), listOf("vk"))
        )),

        // -しまう
        Transform("-しまう", listOf(
            s("てしまう", "て", listOf("v5"), listOf("-て")),
            s("でしまう", "で", listOf("v5"), listOf("-て"))
        )),

        // -なさい polite imperative
        Transform("-なさい", listOf(
            s("なさい", "る", listOf("-なさい"), listOf("v1")),
            s("いなさい", "う", listOf("-なさい"), listOf("v5")),
            s("きなさい", "く", listOf("-なさい"), listOf("v5")),
            s("ぎなさい", "ぐ", listOf("-なさい"), listOf("v5")),
            s("しなさい", "す", listOf("-なさい"), listOf("v5")),
            s("ちなさい", "つ", listOf("-なさい"), listOf("v5")),
            s("になさい", "ぬ", listOf("-なさい"), listOf("v5")),
            s("びなさい", "ぶ", listOf("-なさい"), listOf("v5")),
            s("みなさい", "む", listOf("-なさい"), listOf("v5")),
            s("りなさい", "る", listOf("-なさい"), listOf("v5")),
            s("じなさい", "ずる", listOf("-なさい"), listOf("vz")),
            s("しなさい", "する", listOf("-なさい"), listOf("vs")),
            s("為なさい", "為る", listOf("-なさい"), listOf("vs")),
            s("きなさい", "くる", listOf("-なさい"), listOf("vk")),
            s("来なさい", "来る", listOf("-なさい"), listOf("vk")),
            s("來なさい", "來る", listOf("-なさい"), listOf("vk"))
        )),

        // -そう appearing that
        Transform("-そう", listOf(
            s("そう", "い", listOf(), listOf("adj-i")),
            s("そう", "る", listOf(), listOf("v1")),
            s("いそう", "う", listOf(), listOf("v5")),
            s("きそう", "く", listOf(), listOf("v5")),
            s("ぎそう", "ぐ", listOf(), listOf("v5")),
            s("しそう", "す", listOf(), listOf("v5")),
            s("ちそう", "つ", listOf(), listOf("v5")),
            s("にそう", "ぬ", listOf(), listOf("v5")),
            s("びそう", "ぶ", listOf(), listOf("v5")),
            s("みそう", "む", listOf(), listOf("v5")),
            s("りそう", "る", listOf(), listOf("v5")),
            s("じそう", "ずる", listOf(), listOf("vz")),
            s("しそう", "する", listOf(), listOf("vs")),
            s("為そう", "為る", listOf(), listOf("vs")),
            s("きそう", "くる", listOf(), listOf("vk")),
            s("来そう", "来る", listOf(), listOf("vk")),
            s("來そう", "來る", listOf(), listOf("vk"))
        )),

        // -すぎる too much
        Transform("-すぎる", listOf(
            s("すぎる", "い", listOf("v1"), listOf("adj-i")),
            s("すぎる", "る", listOf("v1"), listOf("v1")),
            s("いすぎる", "う", listOf("v1"), listOf("v5")),
            s("きすぎる", "く", listOf("v1"), listOf("v5")),
            s("ぎすぎる", "ぐ", listOf("v1"), listOf("v5")),
            s("しすぎる", "す", listOf("v1"), listOf("v5")),
            s("ちすぎる", "つ", listOf("v1"), listOf("v5")),
            s("にすぎる", "ぬ", listOf("v1"), listOf("v5")),
            s("びすぎる", "ぶ", listOf("v1"), listOf("v5")),
            s("みすぎる", "む", listOf("v1"), listOf("v5")),
            s("りすぎる", "る", listOf("v1"), listOf("v5")),
            s("じすぎる", "ずる", listOf("v1"), listOf("vz")),
            s("しすぎる", "する", listOf("v1"), listOf("vs")),
            s("為すぎる", "為る", listOf("v1"), listOf("vs")),
            s("きすぎる", "くる", listOf("v1"), listOf("vk")),
            s("来すぎる", "来る", listOf("v1"), listOf("vk")),
            s("來すぎる", "來る", listOf("v1"), listOf("vk"))
        )),

        // -過ぎる too much (kanji)
        Transform("-過ぎる", listOf(
            s("過ぎる", "い", listOf("v1"), listOf("adj-i")),
            s("過ぎる", "る", listOf("v1"), listOf("v1")),
            s("い過ぎる", "う", listOf("v1"), listOf("v5")),
            s("き過ぎる", "く", listOf("v1"), listOf("v5")),
            s("ぎ過ぎる", "ぐ", listOf("v1"), listOf("v5")),
            s("し過ぎる", "す", listOf("v1"), listOf("v5")),
            s("ち過ぎる", "つ", listOf("v1"), listOf("v5")),
            s("に過ぎる", "ぬ", listOf("v1"), listOf("v5")),
            s("び過ぎる", "ぶ", listOf("v1"), listOf("v5")),
            s("み過ぎる", "む", listOf("v1"), listOf("v5")),
            s("り過ぎる", "る", listOf("v1"), listOf("v5")),
            s("じ過ぎる", "ずる", listOf("v1"), listOf("vz")),
            s("し過ぎる", "する", listOf("v1"), listOf("vs")),
            s("為過ぎる", "為る", listOf("v1"), listOf("vs")),
            s("き過ぎる", "くる", listOf("v1"), listOf("vk")),
            s("来過ぎる", "来る", listOf("v1"), listOf("vk")),
            s("來過ぎる", "來る", listOf("v1"), listOf("vk"))
        )),

        // -たい want to
        Transform("-たい", listOf(
            s("たい", "る", listOf("adj-i"), listOf("v1")),
            s("いたい", "う", listOf("adj-i"), listOf("v5")),
            s("きたい", "く", listOf("adj-i"), listOf("v5")),
            s("ぎたい", "ぐ", listOf("adj-i"), listOf("v5")),
            s("したい", "す", listOf("adj-i"), listOf("v5")),
            s("ちたい", "つ", listOf("adj-i"), listOf("v5")),
            s("にたい", "ぬ", listOf("adj-i"), listOf("v5")),
            s("びたい", "ぶ", listOf("adj-i"), listOf("v5")),
            s("みたい", "む", listOf("adj-i"), listOf("v5")),
            s("りたい", "る", listOf("adj-i"), listOf("v5")),
            s("じたい", "ずる", listOf("adj-i"), listOf("vz")),
            s("したい", "する", listOf("adj-i"), listOf("vs")),
            s("為たい", "為る", listOf("adj-i"), listOf("vs")),
            s("きたい", "くる", listOf("adj-i"), listOf("vk")),
            s("来たい", "来る", listOf("adj-i"), listOf("vk")),
            s("來たい", "來る", listOf("adj-i"), listOf("vk"))
        )),

        // -たら conditional
        Transform("-たら", irregularVerbSuffixInflections("たら", emptySet(), setOf("v5")) + listOf(
            s("かったら", "い", listOf(), listOf("adj-i")),
            s("たら", "る", listOf(), listOf("v1")),
            s("いたら", "く", listOf(), listOf("v5")),
            s("いだら", "ぐ", listOf(), listOf("v5")),
            s("したら", "す", listOf(), listOf("v5")),
            s("ったら", "う", listOf(), listOf("v5")),
            s("ったら", "つ", listOf(), listOf("v5")),
            s("ったら", "る", listOf(), listOf("v5")),
            s("んだら", "ぬ", listOf(), listOf("v5")),
            s("んだら", "ぶ", listOf(), listOf("v5")),
            s("んだら", "む", listOf(), listOf("v5")),
            s("じたら", "ずる", listOf(), listOf("vz")),
            s("したら", "する", listOf(), listOf("vs")),
            s("為たら", "為る", listOf(), listOf("vs")),
            s("きたら", "くる", listOf(), listOf("vk")),
            s("来たら", "来る", listOf(), listOf("vk")),
            s("來たら", "來る", listOf(), listOf("vk")),
            s("ましたら", "ます", listOf(), listOf("-ます"))
        )),

        // -たり
        Transform("-たり", irregularVerbSuffixInflections("たり", emptySet(), setOf("v5")) + listOf(
            s("かったり", "い", listOf(), listOf("adj-i")),
            s("たり", "る", listOf(), listOf("v1")),
            s("いたり", "く", listOf(), listOf("v5")),
            s("いだり", "ぐ", listOf(), listOf("v5")),
            s("したり", "す", listOf(), listOf("v5")),
            s("ったり", "う", listOf(), listOf("v5")),
            s("ったり", "つ", listOf(), listOf("v5")),
            s("ったり", "る", listOf(), listOf("v5")),
            s("んだり", "ぬ", listOf(), listOf("v5")),
            s("んだり", "ぶ", listOf(), listOf("v5")),
            s("んだり", "む", listOf(), listOf("v5")),
            s("じたり", "ずる", listOf(), listOf("vz")),
            s("したり", "する", listOf(), listOf("vs")),
            s("為たり", "為る", listOf(), listOf("vs")),
            s("きたり", "くる", listOf(), listOf("vk")),
            s("来たり", "来る", listOf(), listOf("vk")),
            s("來たり", "來る", listOf(), listOf("vk"))
        )),

        // -て te-form
        Transform("-て", irregularVerbSuffixInflections("て", setOf("-て"), setOf("v5")) + listOf(
            s("くて", "い", listOf("-て"), listOf("adj-i")),
            s("て", "る", listOf("-て"), listOf("v1")),
            s("いて", "く", listOf("-て"), listOf("v5")),
            s("いで", "ぐ", listOf("-て"), listOf("v5")),
            s("して", "す", listOf("-て"), listOf("v5")),
            s("って", "う", listOf("-て"), listOf("v5")),
            s("って", "つ", listOf("-て"), listOf("v5")),
            s("って", "る", listOf("-て"), listOf("v5")),
            s("んで", "ぬ", listOf("-て"), listOf("v5")),
            s("んで", "ぶ", listOf("-て"), listOf("v5")),
            s("んで", "む", listOf("-て"), listOf("v5")),
            s("じて", "ずる", listOf("-て"), listOf("vz")),
            s("して", "する", listOf("-て"), listOf("vs")),
            s("為て", "為る", listOf("-て"), listOf("vs")),
            s("きて", "くる", listOf("-て"), listOf("vk")),
            s("来て", "来る", listOf("-て"), listOf("vk")),
            s("來て", "來る", listOf("-て"), listOf("vk")),
            s("まして", "ます", listOf(), listOf("-ます"))
        )),

        // -ず negative
        Transform("-ず", listOf(
            s("ず", "る", listOf(), listOf("v1")),
            s("かず", "く", listOf(), listOf("v5")),
            s("がず", "ぐ", listOf(), listOf("v5")),
            s("さず", "す", listOf(), listOf("v5")),
            s("たず", "つ", listOf(), listOf("v5")),
            s("なず", "ぬ", listOf(), listOf("v5")),
            s("ばず", "ぶ", listOf(), listOf("v5")),
            s("まず", "む", listOf(), listOf("v5")),
            s("らず", "る", listOf(), listOf("v5")),
            s("わず", "う", listOf(), listOf("v5")),
            s("ぜず", "ずる", listOf(), listOf("vz")),
            s("せず", "する", listOf(), listOf("vs")),
            s("為ず", "為る", listOf(), listOf("vs")),
            s("こず", "くる", listOf(), listOf("vk")),
            s("来ず", "来る", listOf(), listOf("vk")),
            s("來ず", "來る", listOf(), listOf("vk"))
        )),

        // -ぬ archaic negative
        Transform("-ぬ", listOf(
            s("ぬ", "る", listOf(), listOf("v1")),
            s("かぬ", "く", listOf(), listOf("v5")),
            s("がぬ", "ぐ", listOf(), listOf("v5")),
            s("さぬ", "す", listOf(), listOf("v5")),
            s("たぬ", "つ", listOf(), listOf("v5")),
            s("なぬ", "ぬ", listOf(), listOf("v5")),
            s("ばぬ", "ぶ", listOf(), listOf("v5")),
            s("まぬ", "む", listOf(), listOf("v5")),
            s("らぬ", "る", listOf(), listOf("v5")),
            s("わぬ", "う", listOf(), listOf("v5")),
            s("ぜぬ", "ずる", listOf(), listOf("vz")),
            s("せぬ", "する", listOf(), listOf("vs")),
            s("為ぬ", "為る", listOf(), listOf("vs")),
            s("こぬ", "くる", listOf(), listOf("vk")),
            s("来ぬ", "来る", listOf(), listOf("vk")),
            s("來ぬ", "來る", listOf(), listOf("vk"))
        )),

        // -ん negative contraction
        Transform("-ん", listOf(
            s("ん", "る", listOf("-ん"), listOf("v1")),
            s("かん", "く", listOf("-ん"), listOf("v5")),
            s("がん", "ぐ", listOf("-ん"), listOf("v5")),
            s("さん", "す", listOf("-ん"), listOf("v5")),
            s("たん", "つ", listOf("-ん"), listOf("v5")),
            s("なん", "ぬ", listOf("-ん"), listOf("v5")),
            s("ばん", "ぶ", listOf("-ん"), listOf("v5")),
            s("まん", "む", listOf("-ん"), listOf("v5")),
            s("らん", "る", listOf("-ん"), listOf("v5")),
            s("わん", "う", listOf("-ん"), listOf("v5")),
            s("ぜん", "ずる", listOf("-ん"), listOf("vz")),
            s("せん", "する", listOf("-ん"), listOf("vs")),
            s("為ん", "為る", listOf("-ん"), listOf("vs")),
            s("こん", "くる", listOf("-ん"), listOf("vk")),
            s("来ん", "来る", listOf("-ん"), listOf("vk")),
            s("來ん", "來る", listOf("-ん"), listOf("vk"))
        )),

        // -んばかり on the verge of
        Transform("-んばかり", listOf(
            s("んばかり", "る", listOf(), listOf("v1")),
            s("かんばかり", "く", listOf(), listOf("v5")),
            s("がんばかり", "ぐ", listOf(), listOf("v5")),
            s("さんばかり", "す", listOf(), listOf("v5")),
            s("たんばかり", "つ", listOf(), listOf("v5")),
            s("なんばかり", "ぬ", listOf(), listOf("v5")),
            s("ばんばかり", "ぶ", listOf(), listOf("v5")),
            s("まんばかり", "む", listOf(), listOf("v5")),
            s("らんばかり", "る", listOf(), listOf("v5")),
            s("わんばかり", "う", listOf(), listOf("v5")),
            s("ぜんばかり", "ずる", listOf(), listOf("vz")),
            s("せんばかり", "する", listOf(), listOf("vs")),
            s("為んばかり", "為る", listOf(), listOf("vs")),
            s("こんばかり", "くる", listOf(), listOf("vk")),
            s("来んばかり", "来る", listOf(), listOf("vk")),
            s("來んばかり", "來る", listOf(), listOf("vk"))
        )),

        // -んとする about to
        Transform("-んとする", listOf(
            s("んとする", "る", listOf("vs"), listOf("v1")),
            s("かんとする", "く", listOf("vs"), listOf("v5")),
            s("がんとする", "ぐ", listOf("vs"), listOf("v5")),
            s("さんとする", "す", listOf("vs"), listOf("v5")),
            s("たんとする", "つ", listOf("vs"), listOf("v5")),
            s("なんとする", "ぬ", listOf("vs"), listOf("v5")),
            s("ばんとする", "ぶ", listOf("vs"), listOf("v5")),
            s("まんとする", "む", listOf("vs"), listOf("v5")),
            s("らんとする", "る", listOf("vs"), listOf("v5")),
            s("わんとする", "う", listOf("vs"), listOf("v5")),
            s("ぜんとする", "ずる", listOf("vs"), listOf("vz")),
            s("せんとする", "する", listOf("vs"), listOf("vs")),
            s("為んとする", "為る", listOf("vs"), listOf("vs")),
            s("こんとする", "くる", listOf("vs"), listOf("vk")),
            s("来んとする", "来る", listOf("vs"), listOf("vk")),
            s("來んとする", "來る", listOf("vs"), listOf("vk"))
        )),

        // -む archaic volitional
        Transform("-む", listOf(
            s("む", "る", listOf(), listOf("v1")),
            s("かむ", "く", listOf(), listOf("v5")),
            s("がむ", "ぐ", listOf(), listOf("v5")),
            s("さむ", "す", listOf(), listOf("v5")),
            s("たむ", "つ", listOf(), listOf("v5")),
            s("なむ", "ぬ", listOf(), listOf("v5")),
            s("ばむ", "ぶ", listOf(), listOf("v5")),
            s("まむ", "む", listOf(), listOf("v5")),
            s("らむ", "る", listOf(), listOf("v5")),
            s("わむ", "う", listOf(), listOf("v5")),
            s("ぜむ", "ずる", listOf(), listOf("vz")),
            s("せむ", "する", listOf(), listOf("vs")),
            s("為む", "為る", listOf(), listOf("vs")),
            s("こむ", "くる", listOf(), listOf("vk")),
            s("来む", "来る", listOf(), listOf("vk")),
            s("來む", "來る", listOf(), listOf("vk"))
        )),

        // -ざる archaic negative
        Transform("-ざる", listOf(
            s("ざる", "る", listOf(), listOf("v1")),
            s("かざる", "く", listOf(), listOf("v5")),
            s("がざる", "ぐ", listOf(), listOf("v5")),
            s("さざる", "す", listOf(), listOf("v5")),
            s("たざる", "つ", listOf(), listOf("v5")),
            s("なざる", "ぬ", listOf(), listOf("v5")),
            s("ばざる", "ぶ", listOf(), listOf("v5")),
            s("まざる", "む", listOf(), listOf("v5")),
            s("らざる", "る", listOf(), listOf("v5")),
            s("わざる", "う", listOf(), listOf("v5")),
            s("ぜざる", "ずる", listOf(), listOf("vz")),
            s("せざる", "する", listOf(), listOf("vs")),
            s("為ざる", "為る", listOf(), listOf("vs")),
            s("こざる", "くる", listOf(), listOf("vk")),
            s("来ざる", "来る", listOf(), listOf("vk")),
            s("來ざる", "來る", listOf(), listOf("vk"))
        )),

        // -ねば if not / must
        Transform("-ねば", listOf(
            s("ねば", "る", listOf("-ば"), listOf("v1")),
            s("かねば", "く", listOf("-ば"), listOf("v5")),
            s("がねば", "ぐ", listOf("-ば"), listOf("v5")),
            s("さねば", "す", listOf("-ば"), listOf("v5")),
            s("たねば", "つ", listOf("-ば"), listOf("v5")),
            s("なねば", "ぬ", listOf("-ば"), listOf("v5")),
            s("ばねば", "ぶ", listOf("-ば"), listOf("v5")),
            s("まねば", "む", listOf("-ば"), listOf("v5")),
            s("らねば", "る", listOf("-ば"), listOf("v5")),
            s("わねば", "う", listOf("-ば"), listOf("v5")),
            s("ぜねば", "ずる", listOf("-ば"), listOf("vz")),
            s("せねば", "する", listOf("-ば"), listOf("vs")),
            s("為ねば", "為る", listOf("-ば"), listOf("vs")),
            s("こねば", "くる", listOf("-ば"), listOf("vk")),
            s("来ねば", "来る", listOf("-ば"), listOf("vk")),
            s("來ねば", "來る", listOf("-ば"), listOf("vk"))
        )),

        // -く adverbial form
        Transform("-く", listOf(
            s("く", "い", listOf("-く"), listOf("adj-i"))
        )),

        // causative
        Transform("causative", listOf(
            s("させる", "る", listOf("v1"), listOf("v1")),
            s("かせる", "く", listOf("v1"), listOf("v5")),
            s("がせる", "ぐ", listOf("v1"), listOf("v5")),
            s("させる", "す", listOf("v1"), listOf("v5")),
            s("たせる", "つ", listOf("v1"), listOf("v5")),
            s("なせる", "ぬ", listOf("v1"), listOf("v5")),
            s("ばせる", "ぶ", listOf("v1"), listOf("v5")),
            s("ませる", "む", listOf("v1"), listOf("v5")),
            s("らせる", "る", listOf("v1"), listOf("v5")),
            s("わせる", "う", listOf("v1"), listOf("v5")),
            s("じさせる", "ずる", listOf("v1"), listOf("vz")),
            s("ぜさせる", "ずる", listOf("v1"), listOf("vz")),
            s("させる", "する", listOf("v1"), listOf("vs")),
            s("為せる", "為る", listOf("v1"), listOf("vs")),
            s("せさせる", "する", listOf("v1"), listOf("vs")),
            s("為させる", "為る", listOf("v1"), listOf("vs")),
            s("こさせる", "くる", listOf("v1"), listOf("vk")),
            s("来させる", "来る", listOf("v1"), listOf("vk")),
            s("來させる", "來る", listOf("v1"), listOf("vk"))
        )),

        // short causative
        Transform("short causative", listOf(
            s("さす", "る", listOf("v5ss"), listOf("v1")),
            s("かす", "く", listOf("v5sp"), listOf("v5")),
            s("がす", "ぐ", listOf("v5sp"), listOf("v5")),
            s("さす", "す", listOf("v5ss"), listOf("v5")),
            s("たす", "つ", listOf("v5sp"), listOf("v5")),
            s("なす", "ぬ", listOf("v5sp"), listOf("v5")),
            s("ばす", "ぶ", listOf("v5sp"), listOf("v5")),
            s("ます", "む", listOf("v5sp"), listOf("v5")),
            s("らす", "る", listOf("v5sp"), listOf("v5")),
            s("わす", "う", listOf("v5sp"), listOf("v5")),
            s("じさす", "ずる", listOf("v5ss"), listOf("vz")),
            s("ぜさす", "ずる", listOf("v5ss"), listOf("vz")),
            s("さす", "する", listOf("v5ss"), listOf("vs")),
            s("為す", "為る", listOf("v5ss"), listOf("vs")),
            s("こさす", "くる", listOf("v5ss"), listOf("vk")),
            s("来さす", "来る", listOf("v5ss"), listOf("vk")),
            s("來さす", "來る", listOf("v5ss"), listOf("vk"))
        )),

        // imperative
        Transform("imperative", listOf(
            s("ろ", "る", listOf(), listOf("v1")),
            s("よ", "る", listOf(), listOf("v1")),
            s("え", "う", listOf(), listOf("v5")),
            s("け", "く", listOf(), listOf("v5")),
            s("げ", "ぐ", listOf(), listOf("v5")),
            s("せ", "す", listOf(), listOf("v5")),
            s("て", "つ", listOf(), listOf("v5")),
            s("ね", "ぬ", listOf(), listOf("v5")),
            s("べ", "ぶ", listOf(), listOf("v5")),
            s("め", "む", listOf(), listOf("v5")),
            s("れ", "る", listOf(), listOf("v5")),
            s("じろ", "ずる", listOf(), listOf("vz")),
            s("ぜよ", "ずる", listOf(), listOf("vz")),
            s("しろ", "する", listOf(), listOf("vs")),
            s("せよ", "する", listOf(), listOf("vs")),
            s("為ろ", "為る", listOf(), listOf("vs")),
            s("為よ", "為る", listOf(), listOf("vs")),
            s("こい", "くる", listOf(), listOf("vk")),
            s("来い", "来る", listOf(), listOf("vk")),
            s("來い", "來る", listOf(), listOf("vk"))
        )),

        // continuative (masu-stem)
        Transform("continuative", listOf(
            s("い", "いる", listOf(), listOf("v1d")),
            s("え", "える", listOf(), listOf("v1d")),
            s("き", "きる", listOf(), listOf("v1d")),
            s("ぎ", "ぎる", listOf(), listOf("v1d")),
            s("け", "ける", listOf(), listOf("v1d")),
            s("げ", "げる", listOf(), listOf("v1d")),
            s("じ", "じる", listOf(), listOf("v1d")),
            s("せ", "せる", listOf(), listOf("v1d")),
            s("ぜ", "ぜる", listOf(), listOf("v1d")),
            s("ち", "ちる", listOf(), listOf("v1d")),
            s("て", "てる", listOf(), listOf("v1d")),
            s("で", "でる", listOf(), listOf("v1d")),
            s("に", "にる", listOf(), listOf("v1d")),
            s("ね", "ねる", listOf(), listOf("v1d")),
            s("ひ", "ひる", listOf(), listOf("v1d")),
            s("び", "びる", listOf(), listOf("v1d")),
            s("へ", "へる", listOf(), listOf("v1d")),
            s("べ", "べる", listOf(), listOf("v1d")),
            s("み", "みる", listOf(), listOf("v1d")),
            s("め", "める", listOf(), listOf("v1d")),
            s("り", "りる", listOf(), listOf("v1d")),
            s("れ", "れる", listOf(), listOf("v1d")),
            s("い", "う", listOf(), listOf("v5")),
            s("き", "く", listOf(), listOf("v5")),
            s("ぎ", "ぐ", listOf(), listOf("v5")),
            s("し", "す", listOf(), listOf("v5")),
            s("ち", "つ", listOf(), listOf("v5")),
            s("に", "ぬ", listOf(), listOf("v5")),
            s("び", "ぶ", listOf(), listOf("v5")),
            s("み", "む", listOf(), listOf("v5")),
            s("り", "る", listOf(), listOf("v5")),
            s("き", "くる", listOf(), listOf("vk")),
            s("し", "する", listOf(), listOf("vs")),
            s("来", "来る", listOf(), listOf("vk")),
            s("來", "來る", listOf(), listOf("vk"))
        )),

        // negative
        Transform("negative", listOf(
            s("くない", "い", listOf("adj-i"), listOf("adj-i")),
            s("ない", "る", listOf("adj-i"), listOf("v1")),
            s("かない", "く", listOf("adj-i"), listOf("v5")),
            s("がない", "ぐ", listOf("adj-i"), listOf("v5")),
            s("さない", "す", listOf("adj-i"), listOf("v5")),
            s("たない", "つ", listOf("adj-i"), listOf("v5")),
            s("なない", "ぬ", listOf("adj-i"), listOf("v5")),
            s("ばない", "ぶ", listOf("adj-i"), listOf("v5")),
            s("まない", "む", listOf("adj-i"), listOf("v5")),
            s("らない", "る", listOf("adj-i"), listOf("v5")),
            s("わない", "う", listOf("adj-i"), listOf("v5")),
            s("じない", "ずる", listOf("adj-i"), listOf("vz")),
            s("しない", "する", listOf("adj-i"), listOf("vs")),
            s("為ない", "為る", listOf("adj-i"), listOf("vs")),
            s("こない", "くる", listOf("adj-i"), listOf("vk")),
            s("来ない", "来る", listOf("adj-i"), listOf("vk")),
            s("來ない", "來る", listOf("adj-i"), listOf("vk")),
            s("ません", "ます", listOf("-ません"), listOf("-ます"))
        )),

        // -さ nominalization
        Transform("-さ", listOf(
            s("さ", "い", listOf(), listOf("adj-i"))
        )),

        // passive
        Transform("passive", listOf(
            s("かれる", "く", listOf("v1"), listOf("v5")),
            s("がれる", "ぐ", listOf("v1"), listOf("v5")),
            s("される", "す", listOf("v1"), listOf("v5d", "v5sp")),
            s("たれる", "つ", listOf("v1"), listOf("v5")),
            s("なれる", "ぬ", listOf("v1"), listOf("v5")),
            s("ばれる", "ぶ", listOf("v1"), listOf("v5")),
            s("まれる", "む", listOf("v1"), listOf("v5")),
            s("われる", "う", listOf("v1"), listOf("v5")),
            s("られる", "る", listOf("v1"), listOf("v5")),
            s("じされる", "ずる", listOf("v1"), listOf("vz")),
            s("ぜされる", "ずる", listOf("v1"), listOf("vz")),
            s("される", "する", listOf("v1"), listOf("vs")),
            s("為れる", "為る", listOf("v1"), listOf("vs")),
            s("こられる", "くる", listOf("v1"), listOf("vk")),
            s("来られる", "来る", listOf("v1"), listOf("vk")),
            s("來られる", "來る", listOf("v1"), listOf("vk"))
        )),

        // -た past tense
        Transform("-た", irregularVerbSuffixInflections("た", setOf("-た"), setOf("v5")) + listOf(
            s("かった", "い", listOf("-た"), listOf("adj-i")),
            s("た", "る", listOf("-た"), listOf("v1")),
            s("いた", "く", listOf("-た"), listOf("v5")),
            s("いだ", "ぐ", listOf("-た"), listOf("v5")),
            s("した", "す", listOf("-た"), listOf("v5")),
            s("った", "う", listOf("-た"), listOf("v5")),
            s("った", "つ", listOf("-た"), listOf("v5")),
            s("った", "る", listOf("-た"), listOf("v5")),
            s("んだ", "ぬ", listOf("-た"), listOf("v5")),
            s("んだ", "ぶ", listOf("-た"), listOf("v5")),
            s("んだ", "む", listOf("-た"), listOf("v5")),
            s("じた", "ずる", listOf("-た"), listOf("vz")),
            s("した", "する", listOf("-た"), listOf("vs")),
            s("為た", "為る", listOf("-た"), listOf("vs")),
            s("きた", "くる", listOf("-た"), listOf("vk")),
            s("来た", "来る", listOf("-た"), listOf("vk")),
            s("來た", "來る", listOf("-た"), listOf("vk")),
            s("ました", "ます", listOf("-た"), listOf("-ます")),
            s("でした", "", listOf("-た"), listOf("-ません")),
            s("かった", "", listOf("-た"), listOf("-ません", "-ん"))
        )),

        // -ます polite
        Transform("-ます", listOf(
            s("ます", "る", listOf("-ます"), listOf("v1")),
            s("います", "う", listOf("-ます"), listOf("v5d")),
            s("きます", "く", listOf("-ます"), listOf("v5d")),
            s("ぎます", "ぐ", listOf("-ます"), listOf("v5d")),
            s("します", "す", listOf("-ます"), listOf("v5d", "v5s")),
            s("ちます", "つ", listOf("-ます"), listOf("v5d")),
            s("にます", "ぬ", listOf("-ます"), listOf("v5d")),
            s("びます", "ぶ", listOf("-ます"), listOf("v5d")),
            s("みます", "む", listOf("-ます"), listOf("v5d")),
            s("ります", "る", listOf("-ます"), listOf("v5d")),
            s("じます", "ずる", listOf("-ます"), listOf("vz")),
            s("します", "する", listOf("-ます"), listOf("vs")),
            s("為ます", "為る", listOf("-ます"), listOf("vs")),
            s("きます", "くる", listOf("-ます"), listOf("vk")),
            s("来ます", "来る", listOf("-ます"), listOf("vk")),
            s("來ます", "來る", listOf("-ます"), listOf("vk")),
            s("くあります", "い", listOf("-ます"), listOf("adj-i"))
        )),

        // potential
        Transform("potential", listOf(
            s("れる", "る", listOf("v1"), listOf("v1", "v5d")),
            s("える", "う", listOf("v1"), listOf("v5d")),
            s("ける", "く", listOf("v1"), listOf("v5d")),
            s("げる", "ぐ", listOf("v1"), listOf("v5d")),
            s("せる", "す", listOf("v1"), listOf("v5d")),
            s("てる", "つ", listOf("v1"), listOf("v5d")),
            s("ねる", "ぬ", listOf("v1"), listOf("v5d")),
            s("べる", "ぶ", listOf("v1"), listOf("v5d")),
            s("める", "む", listOf("v1"), listOf("v5d")),
            s("できる", "する", listOf("v1"), listOf("vs")),
            s("出来る", "する", listOf("v1"), listOf("vs")),
            s("これる", "くる", listOf("v1"), listOf("vk")),
            s("来れる", "来る", listOf("v1"), listOf("vk")),
            s("來れる", "來る", listOf("v1"), listOf("vk"))
        )),

        // potential or passive
        Transform("potential or passive", listOf(
            s("られる", "る", listOf("v1"), listOf("v1")),
            s("ざれる", "ずる", listOf("v1"), listOf("vz")),
            s("ぜられる", "ずる", listOf("v1"), listOf("vz")),
            s("せられる", "する", listOf("v1"), listOf("vs")),
            s("為られる", "為る", listOf("v1"), listOf("vs")),
            s("こられる", "くる", listOf("v1"), listOf("vk")),
            s("来られる", "来る", listOf("v1"), listOf("vk")),
            s("來られる", "來る", listOf("v1"), listOf("vk"))
        )),

        // volitional
        Transform("volitional", listOf(
            s("よう", "る", listOf(), listOf("v1")),
            s("おう", "う", listOf(), listOf("v5")),
            s("こう", "く", listOf(), listOf("v5")),
            s("ごう", "ぐ", listOf(), listOf("v5")),
            s("そう", "す", listOf(), listOf("v5")),
            s("とう", "つ", listOf(), listOf("v5")),
            s("のう", "ぬ", listOf(), listOf("v5")),
            s("ぼう", "ぶ", listOf(), listOf("v5")),
            s("もう", "む", listOf(), listOf("v5")),
            s("ろう", "る", listOf(), listOf("v5")),
            s("じよう", "ずる", listOf(), listOf("vz")),
            s("しよう", "する", listOf(), listOf("vs")),
            s("為よう", "為る", listOf(), listOf("vs")),
            s("こよう", "くる", listOf(), listOf("vk")),
            s("来よう", "来る", listOf(), listOf("vk")),
            s("來よう", "來る", listOf(), listOf("vk")),
            s("ましょう", "ます", listOf(), listOf("-ます")),
            s("かろう", "い", listOf(), listOf("adj-i"))
        )),

        // volitional slang
        Transform("volitional slang", listOf(
            s("よっか", "る", listOf(), listOf("v1")),
            s("おっか", "う", listOf(), listOf("v5")),
            s("こっか", "く", listOf(), listOf("v5")),
            s("ごっか", "ぐ", listOf(), listOf("v5")),
            s("そっか", "す", listOf(), listOf("v5")),
            s("とっか", "つ", listOf(), listOf("v5")),
            s("のっか", "ぬ", listOf(), listOf("v5")),
            s("ぼっか", "ぶ", listOf(), listOf("v5")),
            s("もっか", "む", listOf(), listOf("v5")),
            s("ろっか", "る", listOf(), listOf("v5")),
            s("じよっか", "ずる", listOf(), listOf("vz")),
            s("しよっか", "する", listOf(), listOf("vs")),
            s("為よっか", "為る", listOf(), listOf("vs")),
            s("こよっか", "くる", listOf(), listOf("vk")),
            s("来よっか", "来る", listOf(), listOf("vk")),
            s("來よっか", "來る", listOf(), listOf("vk")),
            s("ましょっか", "ます", listOf(), listOf("-ます"))
        )),

        // -まい negative volitional
        Transform("-まい", listOf(
            s("まい", "", listOf(), listOf("v")),
            s("まい", "る", listOf(), listOf("v1")),
            s("じまい", "ずる", listOf(), listOf("vz")),
            s("しまい", "する", listOf(), listOf("vs")),
            s("為まい", "為る", listOf(), listOf("vs")),
            s("こまい", "くる", listOf(), listOf("vk")),
            s("来まい", "来る", listOf(), listOf("vk")),
            s("來まい", "來る", listOf(), listOf("vk")),
            s("まい", "", listOf(), listOf("-ます"))
        )),

        // -おく in advance
        Transform("-おく", listOf(
            s("ておく", "て", listOf("v5"), listOf("-て")),
            s("でおく", "で", listOf("v5"), listOf("-て")),
            s("とく", "て", listOf("v5"), listOf("-て")),
            s("どく", "で", listOf("v5"), listOf("-て")),
            s("ないでおく", "ない", listOf("v5"), listOf("adj-i")),
            s("ないどく", "ない", listOf("v5"), listOf("adj-i"))
        )),

        // -いる progressive/perfect
        Transform("-いる", listOf(
            s("ている", "て", listOf("v1"), listOf("-て")),
            s("ておる", "て", listOf("v5"), listOf("-て")),
            s("てる", "て", listOf("v1p"), listOf("-て")),
            s("でいる", "で", listOf("v1"), listOf("-て")),
            s("でおる", "で", listOf("v5"), listOf("-て")),
            s("でる", "で", listOf("v1p"), listOf("-て")),
            s("とる", "て", listOf("v5"), listOf("-て")),
            s("ないでいる", "ない", listOf("v1"), listOf("adj-i"))
        )),

        // -き archaic attributive
        Transform("-き", listOf(
            s("き", "い", listOf(), listOf("adj-i"))
        )),

        // -げ appearance
        Transform("-げ", listOf(
            s("げ", "い", listOf(), listOf("adj-i")),
            s("気", "い", listOf(), listOf("adj-i"))
        )),

        // -がる to show signs of
        Transform("-がる", listOf(
            s("がる", "い", listOf("v5"), listOf("adj-i"))
        )),

        // -え slang i-adjective sound change
        Transform("-え", listOf(
            s("ねえ", "ない", listOf(), listOf("adj-i")),
            s("めえ", "むい", listOf(), listOf("adj-i")),
            s("みい", "むい", listOf(), listOf("adj-i")),
            s("ちぇえ", "つい", listOf(), listOf("adj-i")),
            s("ちい", "つい", listOf(), listOf("adj-i")),
            s("せえ", "すい", listOf(), listOf("adj-i")),
            s("ええ", "いい", listOf(), listOf("adj-i")),
            s("ええ", "わい", listOf(), listOf("adj-i")),
            s("ええ", "よい", listOf(), listOf("adj-i")),
            s("いぇえ", "よい", listOf(), listOf("adj-i")),
            s("うぇえ", "わい", listOf(), listOf("adj-i")),
            s("けえ", "かい", listOf(), listOf("adj-i")),
            s("げえ", "がい", listOf(), listOf("adj-i")),
            s("げえ", "ごい", listOf(), listOf("adj-i")),
            s("せえ", "さい", listOf(), listOf("adj-i")),
            s("めえ", "まい", listOf(), listOf("adj-i")),
            s("ぜえ", "ずい", listOf(), listOf("adj-i")),
            s("っぜえ", "ずい", listOf(), listOf("adj-i")),
            s("れえ", "らい", listOf(), listOf("adj-i")),
            s("ちぇえ", "ちゃい", listOf(), listOf("adj-i")),
            s("でえ", "どい", listOf(), listOf("adj-i")),
            s("れえ", "れい", listOf(), listOf("adj-i")),
            s("べえ", "ばい", listOf(), listOf("adj-i")),
            s("てえ", "たい", listOf(), listOf("adj-i")),
            s("ねぇ", "ない", listOf(), listOf("adj-i")),
            s("めぇ", "むい", listOf(), listOf("adj-i")),
            s("みぃ", "むい", listOf(), listOf("adj-i")),
            s("ちぃ", "つい", listOf(), listOf("adj-i")),
            s("せぇ", "すい", listOf(), listOf("adj-i")),
            s("けぇ", "かい", listOf(), listOf("adj-i")),
            s("げぇ", "がい", listOf(), listOf("adj-i")),
            s("げぇ", "ごい", listOf(), listOf("adj-i")),
            s("せぇ", "さい", listOf(), listOf("adj-i")),
            s("めぇ", "まい", listOf(), listOf("adj-i")),
            s("ぜぇ", "ずい", listOf(), listOf("adj-i")),
            s("っぜぇ", "ずい", listOf(), listOf("adj-i")),
            s("れぇ", "らい", listOf(), listOf("adj-i")),
            s("でぇ", "どい", listOf(), listOf("adj-i")),
            s("れぇ", "れい", listOf(), listOf("adj-i")),
            s("べぇ", "ばい", listOf(), listOf("adj-i")),
            s("てぇ", "たい", listOf(), listOf("adj-i"))
        )),

        // n-slang
        Transform("n-slang", listOf(
            s("んなさい", "りなさい", listOf(), listOf("-なさい")),
            s("らんない", "られない", listOf("adj-i"), listOf("adj-i")),
            s("んない", "らない", listOf("adj-i"), listOf("adj-i")),
            s("んなきゃ", "らなきゃ", listOf(), listOf("-ゃ")),
            s("んなきゃ", "れなきゃ", listOf(), listOf("-ゃ"))
        )),

        // imperative negative slang
        Transform("imperative negative slang", listOf(
            s("んな", "る", listOf(), listOf("v"))
        )),

        // kansai-ben negative
        Transform("kansai-ben negative", listOf(
            s("へん", "ない", listOf(), listOf("adj-i")),
            s("ひん", "ない", listOf(), listOf("adj-i")),
            s("せえへん", "しない", listOf(), listOf("adj-i")),
            s("へんかった", "なかった", listOf("-た"), listOf("-た")),
            s("ひんかった", "なかった", listOf("-た"), listOf("-た")),
            s("うてへん", "ってない", listOf(), listOf("adj-i"))
        )),

        // kansai-ben -て
        Transform("kansai-ben -て", listOf(
            s("うて", "って", listOf("-て"), listOf("-て")),
            s("おうて", "あって", listOf("-て"), listOf("-て")),
            s("こうて", "かって", listOf("-て"), listOf("-て")),
            s("ごうて", "がって", listOf("-て"), listOf("-て")),
            s("そうて", "さって", listOf("-て"), listOf("-て")),
            s("ぞうて", "ざって", listOf("-て"), listOf("-て")),
            s("とうて", "たって", listOf("-て"), listOf("-て")),
            s("どうて", "だって", listOf("-て"), listOf("-て")),
            s("のうて", "なって", listOf("-て"), listOf("-て")),
            s("ほうて", "はって", listOf("-て"), listOf("-て")),
            s("ぼうて", "ばって", listOf("-て"), listOf("-て")),
            s("もうて", "まって", listOf("-て"), listOf("-て")),
            s("ろうて", "らって", listOf("-て"), listOf("-て")),
            s("ようて", "やって", listOf("-て"), listOf("-て")),
            s("ゆうて", "いって", listOf("-て"), listOf("-て"))
        )),

        // kansai-ben -た
        Transform("kansai-ben -た", listOf(
            s("うた", "った", listOf("-た"), listOf("-た")),
            s("おうた", "あった", listOf("-た"), listOf("-た")),
            s("こうた", "かった", listOf("-た"), listOf("-た")),
            s("ごうた", "がった", listOf("-た"), listOf("-た")),
            s("そうた", "さった", listOf("-た"), listOf("-た")),
            s("ぞうた", "ざった", listOf("-た"), listOf("-た")),
            s("とうた", "たった", listOf("-た"), listOf("-た")),
            s("どうた", "だった", listOf("-た"), listOf("-た")),
            s("のうた", "なった", listOf("-た"), listOf("-た")),
            s("ほうた", "はった", listOf("-た"), listOf("-た")),
            s("ぼうた", "ばった", listOf("-た"), listOf("-た")),
            s("もうた", "まった", listOf("-た"), listOf("-た")),
            s("ろうた", "らった", listOf("-た"), listOf("-た")),
            s("ようた", "やった", listOf("-た"), listOf("-た")),
            s("ゆうた", "いった", listOf("-た"), listOf("-た"))
        )),

        // kansai-ben -たら
        Transform("kansai-ben -たら", listOf(
            s("うたら", "ったら", listOf(), listOf()),
            s("おうたら", "あったら", listOf(), listOf()),
            s("こうたら", "かったら", listOf(), listOf()),
            s("ごうたら", "がったら", listOf(), listOf()),
            s("そうたら", "さったら", listOf(), listOf()),
            s("ぞうたら", "ざったら", listOf(), listOf()),
            s("とうたら", "たったら", listOf(), listOf()),
            s("どうたら", "だったら", listOf(), listOf()),
            s("のうたら", "なったら", listOf(), listOf()),
            s("ほうたら", "はったら", listOf(), listOf()),
            s("ぼうたら", "ばったら", listOf(), listOf()),
            s("もうたら", "まったら", listOf(), listOf()),
            s("ろうたら", "らったら", listOf(), listOf()),
            s("ようたら", "やったら", listOf(), listOf()),
            s("ゆうたら", "いったら", listOf(), listOf())
        )),

        // kansai-ben -たり
        Transform("kansai-ben -たり", listOf(
            s("うたり", "ったり", listOf(), listOf()),
            s("おうたり", "あったり", listOf(), listOf()),
            s("こうたり", "かったり", listOf(), listOf()),
            s("ごうたり", "がったり", listOf(), listOf()),
            s("そうたり", "さったり", listOf(), listOf()),
            s("ぞうたり", "ざったり", listOf(), listOf()),
            s("とうたり", "たったり", listOf(), listOf()),
            s("どうたり", "だったり", listOf(), listOf()),
            s("のうたり", "なったり", listOf(), listOf()),
            s("ほうたり", "はったり", listOf(), listOf()),
            s("ぼうたり", "ばったり", listOf(), listOf()),
            s("もうたり", "まったり", listOf(), listOf()),
            s("ろうたり", "らったり", listOf(), listOf()),
            s("ようたり", "やったり", listOf(), listOf()),
            s("ゆうたり", "いったり", listOf(), listOf())
        )),

        // kansai-ben -く adjective stem
        Transform("kansai-ben -く", listOf(
            s("う", "く", listOf(), listOf("-く")),
            s("こう", "かく", listOf(), listOf("-く")),
            s("ごう", "がく", listOf(), listOf("-く")),
            s("そう", "さく", listOf(), listOf("-く")),
            s("とう", "たく", listOf(), listOf("-く")),
            s("のう", "なく", listOf(), listOf("-く")),
            s("ぼう", "ばく", listOf(), listOf("-く")),
            s("もう", "まく", listOf(), listOf("-く")),
            s("ろう", "らく", listOf(), listOf("-く")),
            s("よう", "よく", listOf(), listOf("-く")),
            s("しゅう", "しく", listOf(), listOf("-く"))
        )),

        // kansai-ben adjective -て
        Transform("kansai-ben adjective -て", listOf(
            s("うて", "くて", listOf("-て"), listOf("-て")),
            s("こうて", "かくて", listOf("-て"), listOf("-て")),
            s("ごうて", "がくて", listOf("-て"), listOf("-て")),
            s("そうて", "さくて", listOf("-て"), listOf("-て")),
            s("とうて", "たくて", listOf("-て"), listOf("-て")),
            s("のうて", "なくて", listOf("-て"), listOf("-て")),
            s("ぼうて", "ばくて", listOf("-て"), listOf("-て")),
            s("もうて", "まくて", listOf("-て"), listOf("-て")),
            s("ろうて", "らくて", listOf("-て"), listOf("-て")),
            s("ようて", "よくて", listOf("-て"), listOf("-て")),
            s("しゅうて", "しくて", listOf("-て"), listOf("-て"))
        )),

        // kansai-ben adjective negative
        Transform("kansai-ben adjective negative", listOf(
            s("うない", "くない", listOf("adj-i"), listOf("adj-i")),
            s("こうない", "かくない", listOf("adj-i"), listOf("adj-i")),
            s("ごうない", "がくない", listOf("adj-i"), listOf("adj-i")),
            s("そうない", "さくない", listOf("adj-i"), listOf("adj-i")),
            s("とうない", "たくない", listOf("adj-i"), listOf("adj-i")),
            s("のうない", "なくない", listOf("adj-i"), listOf("adj-i")),
            s("ぼうない", "ばくない", listOf("adj-i"), listOf("adj-i")),
            s("もうない", "まくない", listOf("adj-i"), listOf("adj-i")),
            s("ろうない", "らくない", listOf("adj-i"), listOf("adj-i")),
            s("ようない", "よくない", listOf("adj-i"), listOf("adj-i")),
            s("しゅうない", "しくない", listOf("adj-i"), listOf("adj-i"))
        ))
    )

    // Cache of all suffix rules for efficient matching
    private val allRules: List<Pair<Transform, SuffixRule>> by lazy {
        transforms.flatMap { transform ->
            transform.rules.map { rule -> transform to rule }
        }
    }

    /**
     * Get all possible deinflected variants of a word.
     * Uses BFS to apply transforms iteratively.
     */
    fun getVariants(word: String, maxDepth: Int = 10): List<Variant> {
        val results = mutableListOf<Variant>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<Variant, Int>>()

        // Start with the original word
        val initial = Variant(word, emptySet(), emptyList())
        queue.add(initial to 0)
        results.add(initial)
        seen.add(word)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()

            if (depth >= maxDepth) continue

            for ((transform, rule) in allRules) {
                if (!current.text.endsWith(rule.inflectedSuffix)) continue
                if (rule.inflectedSuffix.isEmpty()) continue

                // Check condition compatibility
                if (rule.conditionsIn.isNotEmpty() &&
                    current.conditions.isNotEmpty() &&
                    !conditionsMatch(current.conditions, rule.conditionsIn)) {
                    continue
                }

                // Apply the transform
                val newText = current.text.dropLast(rule.inflectedSuffix.length) + rule.baseSuffix

                if (newText.isEmpty()) continue
                if (newText in seen) continue

                seen.add(newText)

                val newVariant = Variant(
                    text = newText,
                    conditions = rule.conditionsOut,
                    path = current.path + transform.name
                )

                results.add(newVariant)
                queue.add(newVariant to depth + 1)
            }
        }

        return results
    }

    private fun conditionsMatch(current: Set<String>, required: Set<String>): Boolean {
        // Check if any current condition matches any required condition (including subconditions)
        for (req in required) {
            if (req in current) return true

            // Check subconditions
            val condition = conditions[req]
            if (condition != null) {
                for (sub in condition.subConditions) {
                    if (sub in current) return true
                }
            }
        }
        return false
    }

    /**
     * Check if a condition represents a dictionary form (base form of word)
     */
    fun isDictionaryForm(conditionSet: Set<String>): Boolean {
        return conditionSet.any { conditions[it]?.isDictionaryForm == true }
    }
}
