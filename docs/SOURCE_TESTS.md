# Source diagnostics and validation

## User-device observations (2026-11-08)

The first on-device source test gave the following results:

| Source | Reported result | Interpretation / action |
|---|---|---|
| USGS | HTTP 200, JSON | Reachable. GeoJSON feed is a valid data endpoint. |
| NOAA SWPC | HTTP 200, JSON | Reachable. JSON array must be parsed as a top-level array. |
| GDACS | HTTP 200, JSON | Reachable. Event response schema must be parsed defensively. |
| NOAA NHC | HTTP 200, XML | Reachable. RSS/XML parser needed. |
| Tsunami PAAQ/PHEB | HTTP 200, XML | Reachable. Atom/CAP content should be parsed as XML. |
| JMA | HTTP 200, JSON | Reachable. Region code must match the configured Japanese area. |
| MeteoAlarm root | HTTP 200, HTML | Not a usable feed. The directory page is not an alert feed; switch to a country-specific Atom URL. |
| EONET GeoJSON URL | HTTP 200, RSS MIME | Response did not match the GeoJSON parser expectation. Switch to the documented Events JSON API. |
| EMSC legacy RSS | HTTP 404, HTML | Endpoint is invalid/retired for this app. Switch to official SeismicPortal FDSN service. |
| NWS API | timeout around 18.5 seconds | Network path did not respond in the test environment. This is not evidence that there are no active alerts. Use a short bounded diagnostic timeout and retain explicit TIMEOUT state. |
| Nominatim | HTTP 200, JSON | Geocoder is reachable. It is not an alert source. |
| FEMA IPAWS | setup required | No anonymous endpoint polling. Access requires FEMA IPAWS approval/agreement. |

## Current source endpoints

| Source | Current test/runtime endpoint | Expected format | Coverage / caveat |
|---|---|---|---|
| NWS | https://api.weather.gov/alerts/active?point=25.7617,-80.1918 (diagnostic); runtime uses each selected city point | JSON-LD / GeoJSON | USA. Uses official NWS watches, warnings, advisories and statements. |
| USGS | https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson | GeoJSON | Worldwide earthquake catalogue; city radius filter. |
| EMSC SeismicPortal | https://www.seismicportal.eu/fdsnws/event/1/query?format=json&limit=100&orderby=time | JSON | Worldwide EMSC event catalogue. FDSN service supports time, geographic and magnitude constraints. |
| NASA EONET | https://eonet.gsfc.nasa.gov/api/v3/events?status=open&limit=500 | JSON | Worldwide curated natural-event metadata; not a civil-protection warning authority. |
| GDACS | https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH | JSON | Worldwide disaster events; the schema may change. |
| NOAA SWPC | https://services.swpc.noaa.gov/products/alerts.json | JSON | Worldwide space-weather bulletins. |
| NOAA NHC | https://www.nhc.noaa.gov/index-at.xml | RSS/XML | Atlantic and eastern Pacific tropical cyclone products. |
| US Tsunami Warning Centers | https://www.tsunami.gov/events/xml/PAAQAtom.xml and PHEBAtom.xml | Atom/XML/CAP-linked | Official bulletins for their warning regions. |
| JMA | https://www.jma.go.jp/bosai/warning/data/warning/{areaCode}.json | JSON | Japan. The area code is manually configured per city; 130000 is Tokyo. |
| MeteoAlarm | https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-{country-slug} | Atom | Country-specific feeds. The diagnostic source uses Germany as a representative test; runtime maps the manually selected city country to its feed. |
| Nominatim | https://nominatim.openstreetmap.org/search | JSON | City-name geocoding only; not an alert service. Respect the public usage policy and rate limit. |
| FEMA IPAWS | FEMA All-Hazards Information Feed | CAP | Requires access approval/agreement. Deliberately disabled until credentials/authorization are available. |

## Diagnostic classifications

- ONLINE · VALID PAYLOAD: HTTP 2xx and the response has the expected JSON/XML structure.
- ONLINE · NO DATA: HTTP 204. Request succeeded; no records matched.
- WRONG FORMAT / HTML PAGE: HTTP 2xx, but response is an HTML website/directory rather than a machine feed.
- WRONG FORMAT / UNEXPECTED PAYLOAD: HTTP 2xx, but JSON/XML shape is not what the adapter expects.
- REACHABLE / ACCESS DENIED: HTTP 401/403.
- NOT FOUND: HTTP 404.
- RATE LIMITED: HTTP 429.
- PROVIDER ERROR: HTTP 5xx.
- TIMEOUT: call exceeded the bounded request time.
- DNS ERROR: hostname resolution failed.
- TLS ERROR: TLS handshake/certificate issue.
- UNREACHABLE: other network/client exception.

HTTP 200 alone is not success. The result must have the expected format before the app says the source is a valid feed.

## Runtime behavior

- Four source probes are performed concurrently.
- Diagnostic requests use 5-second connect, 7-second read and 9-second call timeouts. This prevents one blocked source from holding the entire diagnostic screen for a long time.
- NWS requests use manually supplied city coordinates, not device location. NWS point-alert requests are documented by the service; county-based and zone-based UGC semantics matter for broad regional monitoring.
- MeteoAlarm uses country-specific Atom feeds selected by the configured city country. The feed directory itself is not treated as an alerts feed.
- EONET uses its JSON Events API; event geometry records are checked against manually supplied coordinates/radius.
- EMSC uses the documented FDSN event service with JSON output, not the older EMSC RSS URL.
- A timeout or access error is distinct from an empty feed. The UI must not imply that the area is safe when a source cannot be reached.
- Android WorkManager may delay background jobs. The app does not receive US WEA cell broadcast messages for remote locations.

## References

- NWS API: https://www.weather.gov/documentation/services-web-api
- NWS geolocation guide: https://www.weather.gov/media/documentation/docs/NWS_Geolocation.pdf
- EONET v3 API documentation: https://eonet.gsfc.nasa.gov/docs/v3
- EMSC FDSN API documentation: https://www.seismicportal.eu/fdsnws/event/1/redoc
- MeteoAlarm country feed directory: https://feeds.meteoalarm.org/
- USGS real-time feeds: https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php


## Current-alert rules and cache behavior (0.3.0)

- An event needs a parseable issue/event/effective timestamp no older than 24 hours. Missing timestamps are treated as unknown and excluded; receipt time is not substituted.
- Future effective alerts, already-expired records, cancellations/test messages, SWPC summaries and SWPC bulletins with "Active Warning: NO" are excluded from the visible journal and notifications.
- The journal is a separate table from the source cache and seen-event IDs. It only receives the first active appearance of an event ID.
- Refreshing an event updates its cache row. If the event already exists in the journal, its displayed information is updated without another notification.
- Clearing the journal removes only visible journal rows. The cache and seen IDs remain, so the same still-active event cannot repopulate the cleared journal.
- The cache expires records after 24 hours and prunes oldest entries until estimated payload storage is no greater than 10 MB. Cleanup runs during sync and active-journal loads.
- On upgrade from database version 1, existing journal IDs are inserted into seen history before the user can clear the upgraded journal.
- Both manual sync and the WorkManager background worker call the same AlertEngine.sync() ingestion path. The worker records its last execution time, newly added active count and last error in local preferences.
- WorkManager schedules a periodic run with a 15-minute minimum interval. Android battery optimization/Doze may defer it; this is not guaranteed 15-minute delivery.

## NOAA Space Weather decoding

The SWPC feed is parsed separately from generic JSON. Summary products are not presented as active alerts. Common codes receive readable titles:
- ALTKxx / WARKxx: observed or expected geomagnetic K-index threshold.
- WATA20 / 30 / 50 / 99: predicted G1 / G2 / G3 / G4-or-greater geomagnetic storm watch.
- ALTEF3: energetic electron flux alert (satellite charging risk).
- ALTTP2: Type II solar radio emission (possible CME/shock).
- ALTP* / WARP*: solar proton/radiation-storm alert or warning.
- Other alert/watch/warning codes use the readable NOAA bulletin description line and retain the raw code in the details.

The original NOAA bulletin text is retained as event detail. The issue time is used for the 24-hour freshness test; explicit end/expiry times and the "Active Warning" field are applied when present.

## 0.3 unit test cases

The test suite covers fresh vs older-than-24-hour events, expiration, future effective time, cancellations, missing timestamps, NOAA date parsing and SWPC code descriptions.
