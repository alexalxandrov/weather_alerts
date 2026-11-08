package com.alexalx.weatheralerts

import android.content.Context
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.*

object SourceCatalog {
    val all = listOf(
        SourceSpec("nominatim", "OpenStreetMap Nominatim geocoder", "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=New%20York", "JSON", "Worldwide", "Manual city-name lookup only; no device location. Observe the public service usage policy."),
        SourceSpec("nws", "US National Weather Service", "https://api.weather.gov/alerts/active?status=actual", "JSON-LD / GeoJSON", "United States", "Watches, warnings, advisories and statements. Point queries are made per manually configured city."),
        SourceSpec("usgs", "USGS Earthquakes", "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson", "GeoJSON", "Worldwide", "Earthquakes; events are distance-filtered against configured cities."),
        SourceSpec("eonet", "NASA EONET", "https://eonet.gsfc.nasa.gov/api/v3/events/geojson?status=open&limit=500", "GeoJSON", "Worldwide", "Curated natural events: wildfires, storms, volcanoes, sea/lake ice and more."),
        SourceSpec("gdacs", "GDACS", "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH", "JSON", "Worldwide", "Global disaster alerts; event payload formats can change."),
        SourceSpec("swpc", "NOAA Space Weather Prediction Center", "https://services.swpc.noaa.gov/products/alerts.json", "JSON", "Worldwide", "Solar flares, geomagnetic storms, radiation storms and radio blackouts."),
        SourceSpec("nhc", "NOAA National Hurricane Center", "https://www.nhc.noaa.gov/index-at.xml", "RSS / XML", "Atlantic and eastern Pacific", "Tropical cyclone products and advisories."),
        SourceSpec("tsunami_paaq", "US Tsunami Warning Center — Alaska", "https://www.tsunami.gov/events/xml/PAAQAtom.xml", "Atom / CAP", "US / Alaska / Canada region", "Official tsunami bulletins."),
        SourceSpec("tsunami_pheb", "US Tsunami Warning Center — Pacific", "https://www.tsunami.gov/events/xml/PHEBAtom.xml", "Atom / CAP", "Pacific and connected warning regions", "Official tsunami bulletins."),
        SourceSpec("jma_tokyo", "Japan Meteorological Agency — Tokyo", "https://www.jma.go.jp/bosai/warning/data/warning/130000.json", "JSON", "Japan (Tokyo test area)", "JMA warning/advisory JSON. Configure JMA area/prefecture code on a city."),
        SourceSpec("meteoalarm", "MeteoAlarm / MeteoGate", "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-europe", "API / Atom / OGC EDR", "33 European countries", "Public access is advertised; real-time MQTT and queryable warnings may require API setup depending on interface."),
        SourceSpec("emsc", "EMSC Seismic Portal", "https://www.emsc-csem.org/service/rss/rss.php?typ=emsc", "RSS / XML", "Worldwide", "Earthquake information feed; secondary to USGS."),
        SourceSpec("fema_ipaws", "FEMA IPAWS All-Hazards", "https://www.fema.gov/emergency-managers/practitioners/integrated-public-alert-warning-system/technology-developers/all-hazards-information-feed", "CAP", "United States", "Requires approved access / agreement; no credential-free polling is attempted.", true)
    )
}

data class HttpResult(val code: Int, val body: String, val contentType: String, val elapsedMs: Long)

object AlertNetwork {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(true).build()

    fun get(url: String, accept: String = "*/*"): HttpResult {
        val request = Request.Builder().url(url)
            .header("User-Agent", "EmergencyAlertAggregator/0.1 (Android personal alert monitor)")
            .header("Accept", accept).header("Cache-Control", "no-cache").get().build()
        val started = System.nanoTime()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return HttpResult(response.code, body.take(2_000_000), response.header("Content-Type").orEmpty(),
                (System.nanoTime() - started) / 1_000_000)
        }
    }
}


data class CityMatch(val displayName: String, val latitude: Double, val longitude: Double)

object CityGeocoder {
    @Volatile private var lastRequestAt = 0L

    fun search(query: String): List<CityMatch> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        synchronized(this) {
            val wait = 1000L - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) Thread.sleep(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        val encoded = java.net.URLEncoder.encode(clean, Charsets.UTF_8.name())
        val result = AlertNetwork.get(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=" + encoded,
            "application/json"
        )
        if (result.code !in 200..299) return emptyList()
        val array = JSONArray(result.body)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            CityMatch(item.optString("display_name"), lat, lon)
        }
    }
}

object SourceDiagnostics {
    fun runAll(context: Context): List<ProbeResult> {
        val db = LocalDatabase(context)
        return SourceCatalog.all.filterNot { it.requiresSetup }.map { spec ->
            val start = System.nanoTime()
            val result = try {
                val http = AlertNetwork.get(spec.endpoint)
                val status = when {
                    http.code in 200..299 -> "ONLINE"
                    http.code in 300..399 -> "REDIRECT"
                    http.code == 401 || http.code == 403 -> "REACHABLE / ACCESS DENIED"
                    else -> "HTTP ERROR"
                }
                ProbeResult(spec.id, status, http.code, http.elapsedMs,
                    detail = "${http.contentType.ifBlank { "unknown content type" }} · ${http.body.length} chars")
            } catch (e: Exception) {
                ProbeResult(spec.id, "UNREACHABLE", null,
                    (System.nanoTime() - start) / 1_000_000,
                    detail = (e.message ?: e.javaClass.simpleName).take(180))
            }
            db.saveProbe(result)
            result
        }
    }
}

object AlertEngine {
    fun sync(context: Context): Int {
        AppContextHolder.context = context.applicationContext
        val db = LocalDatabase(context)
        val cities = db.cities()
        var created = 0
        if (cities.isEmpty()) return 0

        cities.filter { it.latitude != null && it.longitude != null }.forEach { city ->
            try {
                val url = "https://api.weather.gov/alerts/active?point=${city.latitude},${city.longitude}"
                val body = AlertNetwork.get(url, "application/geo+json, application/ld+json, application/json").body
                created += parseNws(body, city, db)
            } catch (_: Exception) { }
        }

        try {
            val body = AlertNetwork.get("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson",
                "application/geo+json, application/json").body
            created += parseUsgs(body, cities, db)
        } catch (_: Exception) { }

        try {
            val body = AlertNetwork.get("https://eonet.gsfc.nasa.gov/api/v3/events/geojson?status=open&limit=500",
                "application/geo+json, application/json").body
            created += parseEonet(body, cities, db)
        } catch (_: Exception) { }

        cities.filter { it.regionCode.isNotBlank() }.forEach { city ->
            try {
                val code = city.regionCode.trim()
                val body = AlertNetwork.get("https://www.jma.go.jp/bosai/warning/data/warning/$code.json", "application/json").body
                created += parseJma(body, city, db)
            } catch (_: Exception) { }
        }

        val globalFeeds = listOf(
            "gdacs" to "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH",
            "swpc" to "https://services.swpc.noaa.gov/products/alerts.json",
            "nhc" to "https://www.nhc.noaa.gov/index-at.xml",
            "tsunami_paaq" to "https://www.tsunami.gov/events/xml/PAAQAtom.xml",
            "tsunami_pheb" to "https://www.tsunami.gov/events/xml/PHEBAtom.xml",
            "meteoalarm" to "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-europe",
            "emsc" to "https://www.emsc-csem.org/service/rss/rss.php?typ=emsc"
        )
        globalFeeds.forEach { (source, url) ->
            try {
                val response = AlertNetwork.get(url)
                if (response.code in 200..299 && response.body.isNotBlank()) {
                    val records = if (response.body.trimStart().startsWith("<")) parseXml(source, response.body, cities)
                                 else parseGenericJson(source, response.body, cities)
                    records.forEach { if (db.putAlert(it)) { created++; AlertNotifier.show(context, it) } }
                }
            } catch (_: Exception) { }
        }

        db.customFeeds().forEach { feed ->
            try {
                val response = AlertNetwork.get(feed.url)
                if (response.code in 200..299 && response.body.isNotBlank()) {
                    parseXml("custom_${feed.id}", response.body, cities, feed.name)
                        .forEach { if (db.putAlert(it)) { created++; AlertNotifier.show(context, it) } }
                }
            } catch (_: Exception) { }
        }
        return created
    }

    private fun parseNws(text: String, city: City, db: LocalDatabase): Int {
        val features = JSONObject(text).optJSONArray("features") ?: return 0
        var count = 0
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            val event = p.optString("event", "Weather alert")
            val headline = p.optString("headline").ifBlank { event }
            val r = AlertRecord(stableId("nws", f.optString("id", headline + p.optString("sent"))),
                "nws", city.name, event, headline, p.optString("description").ifBlank { p.optString("instruction") },
                p.optString("severity", "Unknown"), classify(event + " " + headline),
                p.optString("effective"), p.optString("expires"), p.optString("web").ifBlank { f.optString("id") })
            if (db.putAlert(r)) { count++; AlertNotifier.show(AppContextHolder.context ?: return count, r) }
        }
        return count
    }

    private fun parseUsgs(text: String, cities: List<City>, db: LocalDatabase): Int {
        val features = JSONObject(text).optJSONArray("features") ?: return 0
        var count = 0
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0); val lat = coords.optDouble(1)
            cities.filter { it.latitude != null && it.longitude != null &&
                distanceKm(lat, lon, it.latitude!!, it.longitude!!) <= it.radiusKm }.forEach { city ->
                val title = p.optString("title", "Earthquake")
                val magnitude = p.optDouble("mag", 0.0)
                val r = AlertRecord(stableId("usgs", f.optString("id", title)), "usgs", city.name,
                    "Earthquake M $magnitude", title,
                    "Magnitude: $magnitude · ${p.optString("place")} · Depth: ${coords.optDouble(2)} km",
                    if (magnitude >= 6.0) "Severe" else "Moderate", "Earthquake",
                    effective = p.optLong("time").toString(), url = p.optString("url"))
                if (db.putAlert(r)) { count++; AlertNotifier.show(AppContextHolder.context ?: return count, r) }
            }
        }
        return count
    }

    private fun parseEonet(text: String, cities: List<City>, db: LocalDatabase): Int {
        val features = JSONObject(text).optJSONArray("features") ?: return 0
        var count = 0
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val title = p.optString("title", "Natural event")
            val category = classify(title + " " + p.optString("description"))
            val coords = mutableListOf<Pair<Double, Double>>()
            collectCoordinates(f.optJSONObject("geometry"), coords)
            cities.forEach { city ->
                val near = if (city.latitude != null && city.longitude != null)
                    coords.any { distanceKm(it.second, it.first, city.latitude!!, city.longitude!!) <= max(300, city.radiusKm * 2) }
                else title.contains(city.name, true)
                if (near) {
                    val r = AlertRecord(stableId("eonet", f.optString("id", title)), "eonet", city.name,
                        title, title, p.optString("description"), "Unknown", category, url = p.optString("link"))
                    if (db.putAlert(r)) { count++; AlertNotifier.show(AppContextHolder.context ?: return count, r) }
                }
            }
        }
        return count
    }

    private fun parseJma(text: String, city: City, db: LocalDatabase): Int {
        val o = JSONObject(text)
        val headline = o.optString("headlineText")
        if (headline.isBlank() || headline.contains("発表警報・注意報はなし")) return 0
        val content = headline + " " + o.optString("reportDatetime")
        val r = AlertRecord(stableId("jma", city.regionCode + content), "jma_tokyo", city.name,
            "JMA warning/advisory", headline, content, "Unknown", classify(content),
            effective = o.optString("reportDatetime"), url = "https://www.jma.go.jp/bosai/warning/")
        return if (db.putAlert(r)) { AlertNotifier.show(AppContextHolder.context ?: return 0, r); 1 } else 0
    }

    private fun parseGenericJson(source: String, text: String, cities: List<City>): List<AlertRecord> {
        val out = mutableListOf<AlertRecord>()
        val array = try {
            when (val value = JSONTokener(text).nextValue()) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("features") ?: value.optJSONArray("events") ?:
                    value.optJSONArray("eventlist") ?: value.optJSONArray("items") ?: value.optJSONArray("data")
                else -> null
            }
        } catch (_: Exception) { null } ?: return out
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val p = item.optJSONObject("properties") ?: item
            val title = p.optString("title")
                .ifBlank { p.optString("headline") }
                .ifBlank { p.optString("event") }
                .ifBlank { p.optString("product_id") }
                .ifBlank { p.optString("type") }
                .ifBlank { p.optString("message") }
            if (title.isBlank()) continue
            val description = p.optString("description")
                .ifBlank { p.optString("summary") }
                .ifBlank { p.optString("message") }
            val place = p.optString("areaDesc").ifBlank { p.optString("country") }
            val textForMatch = title + " " + description + " " + place
            val coordinates = mutableListOf<Pair<Double, Double>>()
            collectCoordinates(item.optJSONObject("geometry"), coordinates)
            val matched = cities.filter { city ->
                val textMatch = textForMatch.contains(city.name, true) ||
                    (city.region.isNotBlank() && textForMatch.contains(city.region, true)) ||
                    (city.country.isNotBlank() && textForMatch.contains(city.country, true))
                val geoMatch = city.latitude != null && city.longitude != null &&
                    coordinates.any { distanceKm(it.second, it.first, city.latitude!!, city.longitude!!) <= city.radiusKm }
                source == "swpc" || textMatch || geoMatch
            }
            matched.forEach { city ->
                val rawId = item.optString("id").ifBlank { p.optString("product_id") }.ifBlank { title + description }
                val url = p.optString("url").ifBlank { p.optString("link") }
                val effective = p.optString("effective").ifBlank { p.optString("sent") }.ifBlank { p.optString("issue_datetime") }
                out += AlertRecord(stableId(source, city.name + "|" + rawId), source, city.name,
                    p.optString("event").ifBlank { title }, title, description,
                    p.optString("severity", "Unknown"), classify(title + " " + description),
                    effective, p.optString("expires"), url)
            }
        }
        return out
    }

    private fun parseXml(source: String, xml: String, cities: List<City>, displayName: String = ""): List<AlertRecord> {
        val out = mutableListOf<AlertRecord>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var active = false
        var field = ""
        val fields = linkedMapOf<String, String>()
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        if (tag in listOf("item", "entry", "alert")) { active = true; fields.clear() }
                        else if (active) field = tag
                    }
                    XmlPullParser.TEXT -> if (active && field.isNotBlank())
                        fields[field] = (fields[field].orEmpty() + " " + parser.text).trim()
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase()
                        if (active && tag in listOf("item", "entry", "alert")) {
                            val title = fields["headline"].orEmpty().ifBlank { fields["title"].orEmpty() }.ifBlank { fields["event"].orEmpty() }
                            val body = fields["description"].orEmpty().ifBlank { fields["summary"].orEmpty() }
                            val area = fields["areadesc"].orEmpty()
                            val link = fields["link"].orEmpty().ifBlank { fields["id"].orEmpty() }
                            val text = "$title $body $area"
                            if (title.isNotBlank()) cities.filter { c -> text.contains(c.name, true) ||
                                (c.region.isNotBlank() && text.contains(c.region, true)) ||
                                (c.country.isNotBlank() && text.contains(c.country, true)) }.forEach { city ->
                                out += AlertRecord(stableId(source, fields["id"].orEmpty().ifBlank { link.ifBlank { title } }),
                                    source, city.name, fields["event"].orEmpty().ifBlank { title }, title, body,
                                    fields["severity"].orEmpty().ifBlank { "Unknown" }, classify(text),
                                    fields["effective"].orEmpty(), fields["expires"].orEmpty(), link)
                            }
                            active = false; fields.clear(); field = ""
                        } else if (active && field == tag) field = ""
                    }
                }
                parser.next()
            }
        } catch (_: Exception) { }
        return out
    }

    private fun collectCoordinates(geometry: JSONObject?, out: MutableList<Pair<Double, Double>>) {
        if (geometry == null) return
        fun walk(value: Any?) {
            when (value) {
                is JSONArray -> if (value.length() >= 2 && value.opt(0) is Number && value.opt(1) is Number)
                    out += value.optDouble(0) to value.optDouble(1)
                else if (value is JSONArray) for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(geometry.opt("coordinates"))
    }

    fun classify(text: String): String {
        val s = text.lowercase()
        return when {
            listOf("tornado", "hurricane", "typhoon", "cyclone", "tropical storm").any { it in s } -> "Severe Storm"
            "tsunami" in s -> "Tsunami"
            listOf("earthquake", "seismic").any { it in s } -> "Earthquake"
            listOf("flood", "inundation", "storm surge").any { it in s } -> "Flood"
            listOf("wildfire", "wild fire", "forest fire", "fire weather").any { it in s } -> "Wildfire"
            listOf("volcano", "volcanic", "eruption").any { it in s } -> "Volcano"
            listOf("radiological", "radiation", "nuclear").any { it in s } -> "Radiological / Nuclear"
            listOf("chemical", "hazmat", "hazardous material", "toxic").any { it in s } -> "Chemical"
            listOf("biological", "epidemic", "outbreak", "disease").any { it in s } -> "Health"
            listOf("landslide", "mudslide").any { it in s } -> "Landslide"
            "avalanche" in s -> "Avalanche"
            listOf("snow", "blizzard", "ice storm", "winter storm").any { it in s } -> "Winter Weather"
            "heat" in s -> "Extreme Heat"
            listOf("cold", "freeze", "frost").any { it in s } -> "Extreme Cold"
            listOf("air quality", "smoke", "particulate").any { it in s } -> "Air Quality"
            listOf("geomagnetic", "solar flare", "space weather", "radio blackout").any { it in s } -> "Space Weather"
            listOf("evacuation", "shelter in place", "civil emergency", "amber alert").any { it in s } -> "Public Safety"
            listOf("wind", "thunderstorm", "storm", "fog", "dust").any { it in s } -> "Weather"
            else -> "Other"
        }
    }

    private fun stableId(source: String, raw: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$source|$raw".toByteArray())
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2-lat1); val dLon = Math.toRadians(lon2-lon1)
        val a = sin(dLat/2).pow(2.0) + cos(Math.toRadians(lat1))*cos(Math.toRadians(lat2))*sin(dLon/2).pow(2.0)
        return 2*r*asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

object AppContextHolder { var context: Context? = null }
