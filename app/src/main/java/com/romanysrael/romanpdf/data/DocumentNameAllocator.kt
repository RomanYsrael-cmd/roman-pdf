package com.romanysrael.romanpdf.data

/** Allocates human-readable names without ever using a title as an internal identity. */
object DocumentNameAllocator {
    fun allocate(desired: String, existingTitles: Collection<String>): String {
        val base = desired.trim().ifBlank { "Untitled" }
        val existing = existingTitles.map(String::trim).filter(String::isNotBlank).toSet()
        if (existing.none { it.equals(base, ignoreCase = true) }) return base

        var suffix = 1
        while (true) {
            val candidate = withSuffix(base, suffix)
            if (existing.none { it.equals(candidate, ignoreCase = true) }) return candidate
            suffix += 1
        }
    }

    fun withoutExtension(displayName: String): String {
        val trimmed = displayName.trim().ifBlank { "Image Document" }
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0) trimmed.substring(0, dot) else trimmed
    }

    fun suggestedImageTitle(firstDisplayName: String, pageCount: Int): String {
        val base = withoutExtension(firstDisplayName)
        return if (pageCount > 1) "$base ($pageCount pages)" else base
    }

    private fun withSuffix(name: String, suffix: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($suffix)${name.substring(dot)}"
        } else {
            "$name ($suffix)"
        }
    }
}
