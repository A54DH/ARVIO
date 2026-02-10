package com.arflix.tv.data.model

import java.io.Serializable

enum class CatalogSourceType {
    PREINSTALLED,
    TRAKT,
    MDBLIST
}

data class CatalogConfig(
    val id: String,
    val title: String,
    val sourceType: CatalogSourceType,
    val sourceUrl: String? = null,
    val sourceRef: String? = null,
    val isPreinstalled: Boolean = false
) : Serializable

data class CatalogValidationResult(
    val isValid: Boolean,
    val normalizedUrl: String? = null,
    val sourceType: CatalogSourceType? = null,
    val error: String? = null
)

