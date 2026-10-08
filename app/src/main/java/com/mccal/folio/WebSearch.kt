package com.mccal.folio

/** Where a typed query can go. Google uses the "Web" filter (udm=14), which omits AI Overviews. */
internal enum class WebSearchTarget(val label: String, private val prefix: String) {
    GOOGLE("Google", "https://www.google.com/search?udm=14&q="),
    DUCKDUCKGO("DuckDuckGo", "https://noai.duckduckgo.com/?q="),
    CHATGPT("Ask ChatGPT", "https://chatgpt.com/?q="),
    CLAUDE("Ask Claude", "https://claude.ai/new?q="),
    PERPLEXITY("Perplexity", "https://www.perplexity.ai/search?q=");

    fun uri(query: String): android.net.Uri = android.net.Uri.parse(prefix + android.net.Uri.encode(query.trim()))
}

internal fun openWebSearch(context: android.content.Context, target: WebSearchTarget, query: String, onStarted: () -> Unit = {}) {
    // A plain https link: the matching app opens it if installed and verified, otherwise the browser.
    runCatching {
        AppSecurity.startActivity(context, android.content.Intent(android.content.Intent.ACTION_VIEW, target.uri(query))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), onStarted = onStarted)
    }
}

internal fun openPlayStoreSearch(context: android.content.Context, query: String) {
    val text = query.trim().takeIf { it.isNotEmpty() } ?: return
    val uri = android.net.Uri.parse("https://play.google.com/store/search").buildUpon()
        .appendQueryParameter("q", text).appendQueryParameter("c", "apps").build()
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { AppSecurity.startActivity(context, android.content.Intent(intent).setPackage("com.android.vending")) }.isFailure) {
        runCatching { AppSecurity.startActivity(context, intent) }
    }
}
