package com.example.cpen321application

import java.net.URI

/** Only extracts a token. API requests always use our configured backend, never the pasted host. */
internal fun invitationTokenFromLink(value: String): String? {
    val text = value.trim()
    val tokenPattern = Regex("[A-Za-z0-9_-]{43}")
    if (tokenPattern.matches(text)) return text
    if (text.length > 2048) return null
    return runCatching {
        val uri = URI(text)
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val token = when {
            uri.scheme.equals("ledger", true) && uri.host.equals("invite", true) && uri.port == -1 -> uri.rawPath?.removePrefix("/")
            uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawPath?.startsWith("/invite/") == true -> uri.rawPath.removePrefix("/invite/")
            else -> null
        }
        token?.takeIf { tokenPattern.matches(it) }
    }.getOrNull()
}
