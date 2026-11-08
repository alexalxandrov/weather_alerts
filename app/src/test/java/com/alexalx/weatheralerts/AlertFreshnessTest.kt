package com.alexalx.weatheralerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlertFreshnessTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()

    private fun alert(
        issuedAt: String,
        effective: String = issuedAt,
        expires: String = "",
        status: String = "Actual",
        source: String = "nws",
        event: String = "Tornado Warning",
        description: String = ""
    ) = AlertRecord(
        id = "id",
        sourceId = source,
        city = "Miami",
        event = event,
        headline = event,
        description = description,
        effective = effective,
        expires = expires,
        issuedAt = issuedAt,
        status = status,
        receivedAt = now
    )

    @Test fun acceptsAnAlertIssuedWithinLast24Hours() {
        val issued = Instant.ofEpochMilli(now - 23 * 60 * 60 * 1000L).toString()
        assertTrue(AlertFreshness.isActiveWithin24Hours(alert(issued), now))
    }

    @Test fun rejectsAnAlertOlderThan24Hours() {
        val issued = Instant.ofEpochMilli(now - 25 * 60 * 60 * 1000L).toString()
        assertFalse(AlertFreshness.isActiveWithin24Hours(alert(issued), now))
    }

    @Test fun rejectsExpiredAlerts() {
        val issued = Instant.ofEpochMilli(now - 60 * 60 * 1000L).toString()
        val expires = Instant.ofEpochMilli(now - 60_000L).toString()
        assertFalse(AlertFreshness.isActiveWithin24Hours(alert(issued, expires = expires), now))
    }

    @Test fun rejectsFutureEffectiveAlerts() {
        val issued = Instant.ofEpochMilli(now - 60_000L).toString()
        val effective = Instant.ofEpochMilli(now + 20 * 60_000L).toString()
        assertFalse(AlertFreshness.isActiveWithin24Hours(alert(issued, effective = effective), now))
    }

    @Test fun rejectsCancelledMessages() {
        val issued = Instant.ofEpochMilli(now - 60_000L).toString()
        assertFalse(AlertFreshness.isActiveWithin24Hours(alert(issued, status = "Cancel"), now))
    }

    @Test fun rejectsRecordsWithoutAnIssueOrEffectiveTimestamp() {
        assertFalse(AlertFreshness.isActiveWithin24Hours(alert(issuedAt = "", effective = ""), now))
    }

    @Test fun rejectsSwpcMessageWithNoActiveWarning() {
        val issued = Instant.ofEpochMilli(now - 60_000L).toString()
        assertFalse(
            AlertFreshness.isActiveWithin24Hours(
                alert(issued, source = "swpc", event = "ALTK04", description = "Active Warning: NO"),
                now
            )
        )
    }

    @Test fun parsesNoaaUtcDateFormat() {
        val parsed = AlertFreshness.parseTime("2026 Nov 08 1130 UTC")
        assertEquals(Instant.parse("2026-11-08T11:30:00Z").toEpochMilli(), parsed)
    }

    @Test fun decodesSpaceWeatherProductCodes() {
        assertTrue(AlertEngine.spaceWeatherTitle("ALTK04").contains("K-index 4"))
        assertTrue(AlertEngine.spaceWeatherTitle("WATA30").contains("G2"))
        assertTrue(AlertEngine.spaceWeatherTitle("ALTEF3").contains("electron flux", ignoreCase = true))
        assertTrue(AlertEngine.spaceWeatherTitle("ALTTP2").contains("Type II"))
        assertEquals("Moderate", AlertEngine.swpcSeverity("WATA30"))
    }
}
