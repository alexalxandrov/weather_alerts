package com.alexalx.weatheralerts

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val receivedAt: Long = System.currentTimeMillis(),
    val issuedAt: String = "",
    val status: String = "Actual"
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

object AlertFreshness {
    const val WINDOW_MS: Long = 24L * 60L * 60L * 1000L
    private const val FUTURE_TOLERANCE_MS = 5L * 60L * 1000L

    fun parseTime(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null

        raw.toLongOrNull()?.let { numeric ->
            return if (numeric >= 100_000_000_000L) numeric else numeric * 1000L
        }

        val candidates = listOf(
            { Instant.parse(raw).toEpochMilli() },
            { OffsetDateTime.parse(raw).toInstant().toEpochMilli() },
            { ZonedDateTime.parse(raw).toInstant().toEpochMilli() },
            { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() },
            {
                val formatter = DateTimeFormatter.ofPattern("yyyy MMM dd HHmm 'UTC'", Locale.US)
                java.time.LocalDateTime.parse(raw, formatter).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            },
            {
                val formatter = DateTimeFormatter.ofPattern("yyyy MMM dd HH:mm 'UTC'", Locale.US)
                java.time.LocalDateTime.parse(raw, formatter).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }
        )
        candidates.forEach { parse ->
            val result = runCatching { parse() }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    fun eventTime(alert: AlertRecord): Long =
        parseTime(alert.issuedAt) ?: parseTime(alert.effective) ?: 0L

    fun isActiveWithin24Hours(alert: AlertRecord, now: Long = System.currentTimeMillis()): Boolean {
        val issued = eventTime(alert)
        if (issued < now - WINDOW_MS || issued > now + FUTURE_TOLERANCE_MS) return false

        val effective = parseTime(alert.effective)
        if (effective != null && effective > now + FUTURE_TOLERANCE_MS) return false

        val expires = parseTime(alert.expires)
        if (expires != null && expires <= now) return false

        val state = alert.status.lowercase(Locale.ROOT)
        if (state in setOf("cancel", "cancelled", "canceled", "expired", "test", "exercise", "system")) return false

        val body = (alert.event + " " + alert.headline + " " + alert.description).lowercase(Locale.ROOT)
        if (body.contains("active warning: no") || body.contains("message cancelled") ||
            body.contains("message canceled") || body.contains("this alert has been cancelled") ||
            body.contains("this alert has been canceled") || body.contains("warning has ended")) return false

        if (alert.sourceId == "swpc" && alert.event.uppercase(Locale.ROOT).startsWith("SUMMARY")) return false
        return true
    }
}
