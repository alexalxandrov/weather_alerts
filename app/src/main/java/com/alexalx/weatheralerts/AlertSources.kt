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
        SourceSpec("nws", "US National Weather Service", "https://api.weather.gov/alerts/active?point=25.7617,-80.1918", "JSON-LD / GeoJSON", "United States", "Probe uses Miami coordinates; live monitoring queries each manually configured city point."),
        SourceSpec("usgs", "USGS Earthquakes", "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson", "GeoJSON", "Worldwide", "Earthquakes; events are distance-filtered against configured cities."),
        SourceSpec("eonet", "NASA EONET", "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&limit=500", "JSON", "Worldwide", "Curated natural events: wildfires, storms, volcanoes, sea/lake ice and more."),
        SourceSpec("gdacs", "GDACS", "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH", "JSON", "Worldwide", "Global disaster alerts; event payload formats can change."),
        SourceSpec("swpc", "NOAA Space Weather Prediction Center", "https://services.swpc.noaa.gov/products/alerts.json", "JSON", "Worldwide", "Solar flares, geomagnetic storms, radiation storms and radio blackouts."),
        SourceSpec("nhc", "NOAA National Hurricane Center", "https://www.nhc.noaa.gov/index-at.xml", "RSS / XML", "Atlantic and eastern Pacific", "Tropical cyclone products and advisories."),
        SourceSpec("tsunami_paaq", "US Tsunami Warning Center — Alaska", "https://www.tsunami.gov/events/xml/PAAQAtom.xml", "Atom / CAP", "US / Alaska / Canada region", "Official tsunami bulletins."),
        SourceSpec("tsunami_pheb", "US Tsunami Warning Center — Pacific", "https://www.tsunami.gov/events/xml/PHEBAtom.xml", "Atom / CAP", "Pacific and connected warning regions", "Official tsunami bulletins."),
        SourceSpec("jma_tokyo", "Japan Meteorological Agency — Tokyo", "https://www.jma.go.jp/bosai/warning/data/warning/130000.json", "JSON", "Japan (Tokyo test area)", "JMA warning/advisory JSON. Configure JMA area/prefecture code on a city."),
        SourceSpec("meteoalarm", "MeteoAlarm country feed (Germany probe)", "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-germany", "Atom", "Supported European countries", "Country-specific Atom feeds. The selected country feed is polled for each configured city; legacy RSS feeds were sunset in January 2026."),
        SourceSpec("emsc", "EMSC SeismicPortal FDSN", "https://www.seismicportal.eu/fdsnws/event/1/query?format=json&limit=100&orderby=time", "JSON", "Worldwide", "Official FDSN event catalogue. Supports time, region, magnitude and depth filters."),
        SourceSpec("fema_ipaws", "FEMA IPAWS All-Hazards", "https://www.fema.gov/emergency-managers/practitioners/integrated-public-alert-warning-system/technology-developers/all-hazards-information-feed", "CAP", "United States", "Requires approved access / agreement; no credential-free polling is attempted.", true)
    )
}

data class HttpResult(val code: Int, val body: String, val contentType: String, val elapsedMs: Long)

object AlertNetwork {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(true).build()

    fun get(
        url: String,
        accept: String = "*/*",
        connectTimeoutSeconds: Long = 8,
        readTimeoutSeconds: Long = 10,
        callTimeoutSeconds: Long = 12
    ): HttpResult {
        val request = Request.Builder().url(url)
            .header("User-Agent", "EmergencyAlertAggregator/0.2 (Android personal alert monitor; contact: project repository)")
            .header("Accept", accept).header("Cache-Control", "no-cache").get().build()
        val started = System.nanoTime()
        val requestClient = client.newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        requestClient.newCall(request).execute().use { response ->
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
    private fun classifyPayload(spec: SourceSpec, http: HttpResult): String {
        if (http.code == 204) return "ONLINE · NO DATA"
        if (http.code !in 200..299) return when (http.code) {
            401, 403 -> "REACHABLE / ACCESS DENIED"
            404 -> "NOT FOUND"
            429 -> "RATE LIMITED"
            in 500..599 -> "PROVIDER ERROR"
            else -> "HTTP ERROR"
        }
        val body = http.body.trimStart()
        val mime = http.contentType.lowercase()
        val valid = when (spec.id) {
            "nominatim" -> body.startsWith("[") && runCatching { JSONArray(body) }.isSuccess
            "nws", "usgs", "eonet", "gdacs", "swpc", "jma" -> {
                val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                when (spec.id) {
                    "usgs" -> parsed is JSONObject && parsed.optJSONArray("features") != null
                    "eonet" -> parsed is JSONObject && (parsed.optJSONArray("events") != null || parsed.optJSONArray("features") != null)
                    "swpc" -> parsed is JSONArray || parsed is JSONObject
                    "gdacs" -> parsed is JSONObject || parsed is JSONArray
                    else -> parsed is JSONObject
                }
            }
            "nhc", "tsunami_paaq", "tsunami_pheb", "meteoalarm" ->
                (body.startsWith("<") || mime.contains("xml") || mime.contains("atom") || mime.contains("rss")) &&
                    !mime.contains("html") && !body.substring(0, minOf(500, body.length)).lowercase().contains("<html")
            "emsc" -> {
                val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                parsed is JSONObject || parsed is JSONArray
            }
            else -> body.isNotBlank()
        }
        if (!valid) {
            val looksHtml = mime.contains("html") || body.take(300).lowercase().contains("<html")
            return if (looksHtml) "WRONG FORMAT / HTML PAGE" else "WRONG FORMAT / UNEXPECTED PAYLOAD"
        }
        return "ONLINE · VALID PAYLOAD"
    }

    private fun testOne(context: Context, spec: SourceSpec): ProbeResult {
        val start = System.nanoTime()
        return try {
            val http = AlertNetwork.get(spec.endpoint, when (spec.format.lowercase()) {
                "json", "geojson", "json-ld / geojson" -> "application/json, application/geo+json, application/ld+json"
                "atom" -> "application/atom+xml, application/xml, text/xml"
                "rss / xml", "atom / cap" -> "application/atom+xml, application/rss+xml, application/xml, text/xml"
                else -> "*/*"
            }, connectTimeoutSeconds = 5, readTimeoutSeconds = 7, callTimeoutSeconds = 9)
            ProbeResult(spec.id, classifyPayload(spec, http), http.code, http.elapsedMs,
                detail = http.contentType.ifBlank { "unknown content type" } + " · " + http.body.length + " chars")
        } catch (e: Exception) {
            val message = (e.message ?: e.javaClass.simpleName).take(180)
            val status = when {
                e is java.net.SocketTimeoutException || message.contains("timeout", true) -> "TIMEOUT"
                e is java.net.UnknownHostException -> "DNS ERROR"
                e is javax.net.ssl.SSLException -> "TLS ERROR"
                else -> "UNREACHABLE"
            }
            ProbeResult(spec.id, status, null, (System.nanoTime() - start) / 1_000_000, detail = message)
        }
    }

    fun runAll(context: Context): List<ProbeResult> {
        val db = LocalDatabase(context)
        val specs = SourceCatalog.all.filterNot { it.requiresSetup }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        return try {
            val tasks = specs.map { spec -> java.util.concurrent.Callable { testOne(context, spec) } }
            executor.invokeAll(tasks).mapNotNull { future ->
                runCatching { future.get() }.getOrNull()?.also { db.saveProbe(it) }
            }
        } finally {
            executor.shutdownNow()
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
            val body = AlertNetwork.get("https://eonet.gsfc.nasa.gov/api/v3/events?status=open&limit=500",
                "application/json").body
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
            "emsc" to "https://www.seismicportal.eu/fdsnws/event/1/query?format=json&limit=100&orderby=time"
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
        // MeteoAlarm exposes actively maintained Atom feeds per country, not one universal Europe Atom feed.
        meteoAlarmCountryFeeds(cities).forEach { (country, slug, url) ->
            try {
                val response = AlertNetwork.get(url, "application/atom+xml, application/xml, */*")
                if (response.code in 200..299 && response.body.isNotBlank()) {
                    val countryCities = cities.filter { it.country.equals(country, true) }
                    parseXml("meteoalarm_" + slug, response.body, countryCities)
                        .forEach { if (db.putAlert(it)) { created++; AlertNotifier.show(context, it) } }
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

    private fun meteoAlarmCountryFeeds(cities: List<City>): List<Triple<String, String, String>> {
        val aliases = mapOf(
            "andorra" to "andorra", "austria" to "austria", "belgium" to "belgium",
            "bosnia and herzegovina" to "bosnia-herzegovina", "bosnia & herzegovina" to "bosnia-herzegovina",
            "bulgaria" to "bulgaria", "croatia" to "croatia", "cyprus" to "cyprus",
            "czech republic" to "czechia", "czechia" to "czechia", "denmark" to "denmark",
            "estonia" to "estonia", "finland" to "finland", "france" to "france",
            "germany" to "germany", "greece" to "greece", "hungary" to "hungary",
            "iceland" to "iceland", "ireland" to "ireland", "israel" to "israel",
            "italy" to "italy", "latvia" to "latvia", "lithuania" to "lithuania",
            "luxembourg" to "luxembourg", "malta" to "malta", "moldova" to "moldova",
            "republic of moldova" to "moldova", "montenegro" to "montenegro",
            "netherlands" to "netherlands", "the netherlands" to "netherlands",
            "north macedonia" to "republic-of-north-macedonia", "norway" to "norway",
            "poland" to "poland", "portugal" to "portugal", "romania" to "romania",
            "serbia" to "serbia", "slovakia" to "slovakia", "slovenia" to "slovenia",
            "spain" to "spain", "sweden" to "sweden", "switzerland" to "switzerland",
            "ukraine" to "ukraine", "united kingdom" to "united-kingdom",
            "great britain" to "united-kingdom",
            "united kingdom of great britain and northern ireland" to "united-kingdom"
        )
        return cities.mapNotNull { city ->
            val slug = aliases[city.country.trim().lowercase()] ?: return@mapNotNull null
            Triple(city.country, slug, "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-" + slug)
        }.distinctBy { it.second }
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
        val root = JSONObject(text)
        val events = root.optJSONArray("events") ?: root.optJSONArray("features") ?: return 0
        var count = 0
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val p = event.optJSONObject("properties") ?: event
            val title = p.optString("title", "Natural event")
            val description = p.optString("description")
            val category = classify(title + " " + description)
            val coordinates = mutableListOf<Pair<Double, Double>>()

            // EONET Events API uses a list of geometry records; GeoJSON output uses a geometry object.
            val geometryList = event.optJSONArray("geometry")
            if (geometryList != null) {
                for (g in 0 until geometryList.length()) {
                    val geometryRecord = geometryList.optJSONObject(g) ?: continue
                    collectCoordinatesFromValue(geometryRecord.opt("coordinates"), coordinates)
                    collectCoordinates(geometryRecord.optJSONObject("geometry"), coordinates)
                }
            }
            collectCoordinates(event.optJSONObject("geometry"), coordinates)

            cities.forEach { city ->
                val near = if (city.latitude != null && city.longitude != null) {
                    coordinates.any {
                        distanceKm(it.second, it.first, city.latitude!!, city.longitude!!) <= max(300, city.radiusKm * 2)
                    }
                } else {
                    (title + " " + description).contains(city.name, true)
                }
                if (near) {
                    val eventId = event.optString("id", p.optString("id", title))
                    val url = p.optString("link").ifBlank { event.optString("link") }
                    val record = AlertRecord(
                        stableId("eonet", city.name + "|" + eventId), "eonet", city.name,
                        title, title, description, "Unknown", category,
                        effective = p.optString("date").ifBlank { p.optString("closed") },
                        url = url
                    )
                    if (db.putAlert(record)) {
                        count++
                        AlertNotifier.show(AppContextHolder.context ?: return count, record)
                    }
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
                        else if (active) {
                            field = tag
                            if (tag == "link") {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrBlank()) fields["link"] = href
                            }
                        }
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

    private fun collectCoordinatesFromValue(value: Any?, out: MutableList<Pair<Double, Double>>) {
        when (value) {
            is JSONArray -> {
                if (value.length() >= 2 && value.opt(0) is Number && value.opt(1) is Number) {
                    out.add(value.optDouble(0) to value.optDouble(1))
                } else {
                    for (i in 0 until value.length()) collectCoordinatesFromValue(value.opt(i), out)
                }
            }
            is JSONObject -> {
                if (value.has("coordinates")) collectCoordinatesFromValue(value.opt("coordinates"), out)
                else if (value.has("longitude") && value.has("latitude")) {
                    out.add(value.optDouble("longitude") to value.optDouble("latitude"))
                }
            }
        }
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
