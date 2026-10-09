package com.mccal.folio

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Cross-script app-name search, using the Hangul pronunciation data already on the device. */
internal object AppPronunciation {
    private val transliterator by lazy {
        runCatching { android.icu.text.Transliterator.getInstance("Hangul-Latin; Latin-ASCII; Any-Lower") }.getOrNull()
    }
    private val cache = ConcurrentHashMap<String, String>()
    private val initials = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val vowels = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val finals = arrayOf("", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lb", "ls", "lt", "lp", "lh", "m", "p", "ps", "t", "t", "ng", "t", "t", "k", "t", "p", "t")

    /** Modern Hangul fallback when the device cannot load or apply its ICU transform. */
    private fun romanizeHangul(text: String): String = buildString {
        for (letter in text) {
            if (letter in '\uAC00'..'\uD7A3') {
                val syllable = letter.code - 0xAC00
                append(initials[syllable / 588])
                append(vowels[syllable % 588 / 28])
                append(finals[syllable % 28])
            } else append(letter)
        }
    }

    private fun hasHangul(text: String) = text.any {
        Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL
    }

    private fun hasLatin(text: String) = text.any {
        Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
    }

    private fun key(text: String): String {
        val composed = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (!hasHangul(composed)) return Normalizer.normalize(composed, Normalizer.Form.NFKD)
        return cache.getOrPut(composed) {
            val transformed = transliterator?.let { latin ->
                runCatching { synchronized(latin) { latin.transliterate(composed) } }.getOrNull()
            }
            transformed?.takeIf { it.isNotBlank() && !hasHangul(it) }
                ?: romanizeHangul(composed)
        }
    }

    /** Native names rank first. Pronunciation matches use no fuzzy subsequences or initials. */
    fun score(label: String, query: String): Int? {
        if (!(hasHangul(query) && hasLatin(label) || hasLatin(query) && hasHangul(label))) return null
        val queryKey = key(query)
        if (queryKey.any { it.isLetter() && it !in 'a'..'z' }) return null
        val q = queryKey.filter { it in 'a'..'z' || it.isDigit() }
        if (q.length < 2) return null
        val words = key(label).split(' ', '-', '.', '_', '·')
            .map { word -> word.filter { it in 'a'..'z' || it.isDigit() } }.filter(String::isNotEmpty)
        val text = words.joinToString("")
        return when {
            text == q -> 0
            text.startsWith(q) -> 1
            words.any { it.startsWith(q) } -> 2
            text.contains(q) -> 3
            else -> null
        }
    }

    fun prepare(label: String) { key(label) }
}
