package com.alexalx.weatheralerts

data class City(
    val id: Long = 0,
    val name: String,
    val country: String,
    val region: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val regionCode: String = "",
    val radiusKm: Int = 300
)

data class AlertRecord(
    val id: String,
    val sourceId: String,
    val city: String,
    val event: String,
    val headline: String,
    val description: String,
    val severity: String = "Unknown",
    val category: String = "Other",
    val effective: String = "",
    val expires: String = "",
    val url: String = "",
    val receivedAt: Long = System.currentTimeMillis()
)

data class SourceSpec(
    val id: String,
    val title: String,
    val endpoint: String,
    val format: String,
    val coverage: String,
    val notes: String,
    val requiresSetup: Boolean = false
)

data class ProbeResult(
    val sourceId: String,
    val status: String,
    val httpCode: Int?,
    val elapsedMs: Long,
    val checkedAt: Long = System.currentTimeMillis(),
    val detail: String = ""
)

data class CustomFeed(val id: Long = 0, val name: String, val url: String)
