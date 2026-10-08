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
            val latin = transliterator ?: return@getOrPut ""
            runCatching { synchronized(latin) { latin.transliterate(composed) } }.getOrDefault("")
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
