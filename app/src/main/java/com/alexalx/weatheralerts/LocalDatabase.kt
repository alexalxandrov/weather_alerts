package com.alexalx.weatheralerts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "emergency_alerts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cities (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, country TEXT NOT NULL, region TEXT, lat REAL, lon REAL, region_code TEXT, radius INTEGER NOT NULL DEFAULT 300)")
        db.execSQL("CREATE TABLE alerts (id TEXT PRIMARY KEY, source_id TEXT, city TEXT, event TEXT, headline TEXT, description TEXT, severity TEXT, category TEXT, effective TEXT, expires TEXT, url TEXT, received_at INTEGER)")
        db.execSQL("CREATE TABLE probes (source_id TEXT PRIMARY KEY, status TEXT, http_code INTEGER, elapsed_ms INTEGER, checked_at INTEGER, detail TEXT)")
        db.execSQL("CREATE TABLE custom_feeds (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, url TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
            while (c.moveToNext()) out += AlertRecord(
                id=c.getString(0), sourceId=c.getString(1) ?: "", city=c.getString(2) ?: "",
                event=c.getString(3) ?: "", headline=c.getString(4) ?: "", description=c.getString(5) ?: "",
                severity=c.getString(6) ?: "Unknown", category=c.getString(7) ?: "Other",
                effective=c.getString(8) ?: "", expires=c.getString(9) ?: "", url=c.getString(10) ?: "",
                receivedAt=c.getLong(11)
            )
        }
        return out
    }

    fun putAlert(alert: AlertRecord): Boolean {
        val values = ContentValues().apply {
            put("id", alert.id); put("source_id", alert.sourceId); put("city", alert.city)
            put("event", alert.event); put("headline", alert.headline); put("description", alert.description)
            put("severity", alert.severity); put("category", alert.category); put("effective", alert.effective)
            put("expires", alert.expires); put("url", alert.url); put("received_at", alert.receivedAt)
        }
        return writableDatabase.insertWithOnConflict("alerts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

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
