package com.github.blarc.sops.intellij.plugin

/**
 * Detects SOPS content from the metadata written into an encrypted file.
 *
 * This intentionally validates the metadata shape only. It does not invoke SOPS or attempt to
 * decrypt the file.
 */
class SopsDetector {

    fun isSopsContent(content: String): Boolean {
        val metadataStart = SOPS_METADATA_START.find(content)
        if (metadataStart != null) {
            val metadata = content.substring(metadataStart.range.first)
            if (SOPS_LAST_MODIFIED.containsMatchIn(metadata) &&
                SOPS_MAC.containsMatchIn(metadata) &&
                SOPS_VERSION.containsMatchIn(metadata)
            ) {
                return true
            }
        }

        val iniMetadataStart = SOPS_INI_METADATA_START.find(content)
        if (iniMetadataStart != null) {
            val metadata = content.substring(iniMetadataStart.range.first)
            if (SOPS_INI_LAST_MODIFIED.containsMatchIn(metadata) &&
                SOPS_INI_MAC.containsMatchIn(metadata) &&
                SOPS_INI_VERSION.containsMatchIn(metadata)
            ) {
                return true
            }
        }

        // ENV files flatten metadata keys with the `sops_` prefix.
        return SOPS_FLAT_LAST_MODIFIED.containsMatchIn(content) &&
            SOPS_FLAT_MAC.containsMatchIn(content) &&
            SOPS_FLAT_VERSION.containsMatchIn(content)
    }

    private companion object {
        private val SOPS_METADATA_START = Regex(
            """(?m)(?:^|[\{\r\n])[\t ]*(?:\"sops\"|sops)[\t ]*:[\t ]*(?:\{[\t ]*|$)"""
        )
        private val SOPS_LAST_MODIFIED = Regex(
            """(?:^|[\{\r\n,])[\t ]*(?:\"lastmodified\"|lastmodified)[\t ]*:"""
        )
        private val SOPS_MAC = Regex(
            """(?:^|[\{\r\n,])[\t ]*(?:\"mac\"|mac)[\t ]*:[\t ]*\"?ENC\[AES256_GCM,"""
        )
        private val SOPS_VERSION = Regex(
            """(?:^|[\{\r\n,])[\t ]*(?:\"version\"|version)[\t ]*:[\t ]*\"?\d+(?:\.\d+)*"""
        )
        private val SOPS_INI_METADATA_START = Regex("""(?m)^[\t ]*\[[\t ]*sops[\t ]*][\t ]*$""")
        private val SOPS_FLAT_LAST_MODIFIED = Regex("""(?m)^[\t ]*sops_lastmodified[\t ]*=""")
        private val SOPS_FLAT_MAC = Regex(
            """(?m)^[\t ]*sops_mac[\t ]*=[\t ]*\"?ENC\[AES256_GCM,"""
        )
        private val SOPS_FLAT_VERSION = Regex(
            """(?m)^[\t ]*sops_version[\t ]*=[\t ]*\"?\d+(?:\.\d+)*"""
        )
        private val SOPS_INI_LAST_MODIFIED = Regex("""(?m)^[\t ]*lastmodified[\t ]*=""")
        private val SOPS_INI_MAC = Regex("""(?m)^[\t ]*mac[\t ]*=[\t ]*\"?ENC\[AES256_GCM,""")
        private val SOPS_INI_VERSION = Regex("""(?m)^[\t ]*version[\t ]*=[\t ]*\"?\d+(?:\.\d+)*""")
    }
}
