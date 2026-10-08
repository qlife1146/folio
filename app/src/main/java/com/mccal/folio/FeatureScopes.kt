package com.mccal.folio

/** Per-screen overrides for launcher appearance features. */
enum class FolioScreen(@androidx.annotation.StringRes val label: Int) { COVER(R.string.cover_screen), INNER(R.string.inner_screen) }

/** DEFAULT inherits the feature's main switch; ON/OFF force it on that screen. */
enum class ScopeValue(@androidx.annotation.StringRes val label: Int) { DEFAULT(R.string.default_choice), ON(R.string.on), OFF(R.string.off) }

internal object FeatureScopes {
    fun value(scopes: Map<String, Map<String, String>>, id: String, screen: FolioScreen): ScopeValue =
        scopes[id]?.get(screen.name)?.let { name -> ScopeValue.entries.firstOrNull { it.name == name } } ?: ScopeValue.DEFAULT

    /** Whether a feature is on for [screen]: an override wins, otherwise the main switch. */
    fun on(scopes: Map<String, Map<String, String>>, id: String, global: Boolean, screen: FolioScreen): Boolean =
        when (value(scopes, id, screen)) { ScopeValue.ON -> true; ScopeValue.OFF -> false; ScopeValue.DEFAULT -> global }

    fun set(scopes: Map<String, Map<String, String>>, id: String, screen: FolioScreen, value: ScopeValue): Map<String, Map<String, String>> {
        val current = scopes[id].orEmpty()
        val next = if (value == ScopeValue.DEFAULT) current - screen.name else current + (screen.name to value.name)
        return if (next.isEmpty()) scopes - id else scopes + (id to next)
    }
}

internal fun screenFor(wide: Boolean) = if (wide) FolioScreen.INNER else FolioScreen.COVER

