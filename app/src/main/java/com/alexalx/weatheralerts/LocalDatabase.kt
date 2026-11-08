package com.alexalx.weatheralerts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "emergency_alerts.db", null, 2) {
    companion object {
        const val CACHE_LIMIT_BYTES = 10L * 1024L * 1024L
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCoreTables(db)
        createCacheTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE alerts ADD COLUMN issued_at TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE alerts ADD COLUMN status TEXT NOT NULL DEFAULT 'Actual'")
            createCacheTables(db)
        }
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cities (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, country TEXT NOT NULL, region TEXT, lat REAL, lon REAL, region_code TEXT, radius INTEGER NOT NULL DEFAULT 300)")
        db.execSQL("CREATE TABLE alerts (id TEXT PRIMARY KEY, source_id TEXT, city TEXT, event TEXT, headline TEXT, description TEXT, severity TEXT, category TEXT, effective TEXT, expires TEXT, url TEXT, received_at INTEGER, issued_at TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'Actual')")
        db.execSQL("CREATE TABLE probes (source_id TEXT PRIMARY KEY, status TEXT, http_code INTEGER, elapsed_ms INTEGER, checked_at INTEGER, detail TEXT)")
        db.execSQL("CREATE TABLE custom_feeds (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, url TEXT NOT NULL)")
    }

    private fun createCacheTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS alert_cache (id TEXT PRIMARY KEY, source_id TEXT, city TEXT, event TEXT, headline TEXT, description TEXT, severity TEXT, category TEXT, effective TEXT, expires TEXT, url TEXT, received_at INTEGER, issued_at TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'Actual', event_time INTEGER NOT NULL, cached_at INTEGER NOT NULL, cache_bytes INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_alert_cache_event_time ON alert_cache(event_time)")
        db.execSQL("CREATE TABLE IF NOT EXISTS alert_seen (id TEXT PRIMARY KEY, first_seen_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_alert_seen_first_seen ON alert_seen(first_seen_at)")
    }

    fun cities(): List<City> {
        val out = mutableListOf<City>()
        readableDatabase.rawQuery("SELECT * FROM cities ORDER BY name", null).use { c ->
            while (c.moveToNext()) out += City(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                name = c.getString(c.getColumnIndexOrThrow("name")),
                country = c.getString(c.getColumnIndexOrThrow("country")),
                region = c.getString(c.getColumnIndexOrThrow("region")) ?: "",
                latitude = if (c.isNull(c.getColumnIndexOrThrow("lat"))) null else c.getDouble(c.getColumnIndexOrThrow("lat")),
                longitude = if (c.isNull(c.getColumnIndexOrThrow("lon"))) null else c.getDouble(c.getColumnIndexOrThrow("lon")),
                regionCode = c.getString(c.getColumnIndexOrThrow("region_code")) ?: "",
                radiusKm = c.getInt(c.getColumnIndexOrThrow("radius"))
            )
        }
        return out
    }

    fun saveCity(city: City): Long {
        val values = ContentValues().apply {
            put("name", city.name); put("country", city.country); put("region", city.region)
            if (city.latitude == null) putNull("lat") else put("lat", city.latitude)
            if (city.longitude == null) putNull("lon") else put("lon", city.longitude)
            put("region_code", city.regionCode); put("radius", city.radiusKm)
        }
        return writableDatabase.insert("cities", null, values)
    }

    fun deleteCity(id: Long) { writableDatabase.delete("cities", "id=?", arrayOf(id.toString())) }

    fun alerts(limit: Int = 250): List<AlertRecord> {
        val out = mutableListOf<AlertRecord>()
        readableDatabase.rawQuery("SELECT * FROM alerts ORDER BY received_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += readAlert(c)
        }
        return out
    }

    fun activeAlerts(now: Long = System.currentTimeMillis(), limit: Int = 250): List<AlertRecord> {
        pruneCacheNow(now)
        val all = alerts(Int.MAX_VALUE)
        val active = all.filter { AlertFreshness.isActiveWithin24Hours(it, now) }
        val inactiveIds = all.filterNot { AlertFreshness.isActiveWithin24Hours(it, now) }.map { it.id }
        if (inactiveIds.isNotEmpty()) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                inactiveIds.forEach { db.delete("alerts", "id=?", arrayOf(it)) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return active.take(limit)
    }

    fun clearJournal() {
        writableDatabase.delete("alerts", null, null)
    }

    /**
     * Caches every source record, but puts only a newly-seen active record into the visible journal.
     * A cleared journal is intentionally not repopulated from cache or from an already-seen alert.
     *
     * Returns true only when a new active journal record is created and should notify the user.
     */
    fun ingestAlert(alert: AlertRecord, now: Long = System.currentTimeMillis()): Boolean {
        val db = writableDatabase
        val eventTime = AlertFreshness.eventTime(alert)
        db.beginTransaction()
        var notify = false
        try {
            if (eventTime >= now - AlertFreshness.WINDOW_MS && eventTime <= now + 5 * 60_000L) {
                val bytes = estimateBytes(alert)
                val values = alertValues(alert).apply {
                    put("event_time", eventTime)
                    put("cached_at", now)
                    put("cache_bytes", bytes)
                }
                db.insertWithOnConflict("alert_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }

            if (AlertFreshness.isActiveWithin24Hours(alert, now)) {
                val journalExists = exists(db, "alerts", alert.id)
                val seen = exists(db, "alert_seen", alert.id)

                if (journalExists) {
                    db.update("alerts", alertValues(alert), "id=?", arrayOf(alert.id))
                    if (!seen) markSeen(db, alert.id, now)
                } else if (!seen) {
                    markSeen(db, alert.id, now)
                    notify = db.insertWithOnConflict("alerts", null, alertValues(alert), SQLiteDatabase.CONFLICT_IGNORE) != -1L
                }
            }

            pruneCache(db, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return notify
    }

    private fun exists(db: SQLiteDatabase, table: String, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    private fun markSeen(db: SQLiteDatabase, id: String, now: Long) {
        db.insertWithOnConflict("alert_seen", null, ContentValues().apply {
            put("id", id); put("first_seen_at", now)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun alertValues(alert: AlertRecord): ContentValues = ContentValues().apply {
        put("id", alert.id); put("source_id", alert.sourceId); put("city", alert.city)
        put("event", alert.event); put("headline", alert.headline); put("description", alert.description)
        put("severity", alert.severity); put("category", alert.category); put("effective", alert.effective)
        put("expires", alert.expires); put("url", alert.url); put("received_at", alert.receivedAt)
        put("issued_at", alert.issuedAt); put("status", alert.status)
    }

    private fun estimateBytes(alert: AlertRecord): Int =
        256 + listOf(alert.id, alert.sourceId, alert.city, alert.event, alert.headline,
            alert.description, alert.severity, alert.category, alert.effective, alert.expires,
            alert.url, alert.issuedAt, alert.status).sumOf { it.toByteArray(Charsets.UTF_8).size }

    fun pruneCacheNow(now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            pruneCache(db, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun pruneCache(db: SQLiteDatabase, now: Long) {
        val cutoff = now - AlertFreshness.WINDOW_MS
        db.delete("alert_cache", "event_time < ? OR event_time > ?", arrayOf(cutoff.toString(), (now + 5 * 60_000L).toString()))
        db.delete("alert_seen", "first_seen_at < ?", arrayOf(cutoff.toString()))

        var size = cacheUsageBytes(db)
        while (size > CACHE_LIMIT_BYTES) {
            val oldest = db.rawQuery("SELECT id, cache_bytes FROM alert_cache ORDER BY event_time ASC, cached_at ASC LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
            } ?: break
            db.delete("alert_cache", "id=?", arrayOf(oldest.first))
            size -= oldest.second
        }
    }

    private fun cacheUsageBytes(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COALESCE(SUM(cache_bytes), 0) FROM alert_cache", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    fun cacheUsageBytes(): Long = cacheUsageBytes(readableDatabase)

    private fun readAlert(c: android.database.Cursor): AlertRecord = AlertRecord(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        sourceId = c.getString(c.getColumnIndexOrThrow("source_id")) ?: "",
        city = c.getString(c.getColumnIndexOrThrow("city")) ?: "",
        event = c.getString(c.getColumnIndexOrThrow("event")) ?: "",
        headline = c.getString(c.getColumnIndexOrThrow("headline")) ?: "",
        description = c.getString(c.getColumnIndexOrThrow("description")) ?: "",
        severity = c.getString(c.getColumnIndexOrThrow("severity")) ?: "Unknown",
        category = c.getString(c.getColumnIndexOrThrow("category")) ?: "Other",
        effective = c.getString(c.getColumnIndexOrThrow("effective")) ?: "",
        expires = c.getString(c.getColumnIndexOrThrow("expires")) ?: "",
        url = c.getString(c.getColumnIndexOrThrow("url")) ?: "",
        receivedAt = c.getLong(c.getColumnIndexOrThrow("received_at")),
        issuedAt = c.getString(c.getColumnIndexOrThrow("issued_at")) ?: "",
        status = c.getString(c.getColumnIndexOrThrow("status")) ?: "Actual"
    )

    fun saveProbe(p: ProbeResult) {
        val values = ContentValues().apply {
            put("source_id", p.sourceId); put("status", p.status); if (p.httpCode == null) putNull("http_code") else put("http_code", p.httpCode)
            put("elapsed_ms", p.elapsedMs); put("checked_at", p.checkedAt); put("detail", p.detail)
        }
        writableDatabase.insertWithOnConflict("probes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun probes(): Map<String, ProbeResult> {
        val out = mutableMapOf<String, ProbeResult>()
        readableDatabase.rawQuery("SELECT * FROM probes", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = ProbeResult(
                c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getInt(2),
                c.getLong(3), c.getLong(4), c.getString(5) ?: ""
            )
        }
        return out
    }

    fun customFeeds(): List<CustomFeed> {
        val out = mutableListOf<CustomFeed>()
        readableDatabase.rawQuery("SELECT id,name,url FROM custom_feeds ORDER BY name", null).use { c ->
            while (c.moveToNext()) out += CustomFeed(c.getLong(0), c.getString(1), c.getString(2))
        }
        return out
    }

    fun addCustomFeed(name: String, url: String) {
        writableDatabase.insert("custom_feeds", null, ContentValues().apply { put("name", name); put("url", url) })
    }

    fun deleteCustomFeed(id: Long) { writableDatabase.delete("custom_feeds", "id=?", arrayOf(id.toString())) }
}
