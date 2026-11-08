# Source survey and test plan

Date of survey: 2026-11-08.

This report separates endpoint/documentation inspection from live tests on the user's Android phone. The assistant environment could not resolve external hosts with its shell DNS, and the web retrieval layer rejects some XML/GeoJSON content types or receives access-denied responses. This is not evidence that the same endpoints are blocked in Russia. The app includes on-device GET probes because only the phone can measure the user's actual DNS, TLS, routing and HTTP access.

## Source matrix

| Source | Data / categories | Endpoint used by app | Survey result from this environment | Notes |
|---|---|---|---|---|
| NWS API | US watches, warnings, advisories, statements; point based | api.weather.gov/alerts/active | Endpoint content type was not rendered by the browser tool; NWS API/CAP/Atom documentation verified | Requires User-Agent; point queries are per city with manually entered coordinates. |
| USGS | Worldwide earthquakes | earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson | all_hour GeoJSON returned a valid FeatureCollection in the web retrieval environment | App currently pulls all_day, then filters by city radius. |
| NASA EONET | Natural events: wildfires, severe storms, volcanoes and other event categories | eonet.gsfc.nasa.gov/api/v3/events/geojson | Current v3 API documentation verified; direct feed content-type was not accepted by the retrieval layer | EONET is a curated event catalogue, not a life-safety warning authority. |
| GDACS | Global disaster event alerts | gdacs.org/gdacsapi/api/events/geteventlist/SEARCH | Endpoint returned JSON content in web retrieval; exact current payload should be confirmed by on-device probe | Parsing is deliberately defensive because the provider schema may change. |
| NOAA SWPC | Space weather, geomagnetic storms, solar radiation and radio blackouts | services.swpc.noaa.gov/products/alerts.json | Official page confirms JSON feed; retrieval tool received HTTP 403 | A phone-side 403 should be treated as access denial, not no alerts. |
| NOAA NHC | Atlantic/eastern Pacific tropical cyclone products | nhc.noaa.gov/index-at.xml | Retrieval tool received HTTP 403 | NHC feed uses RSS/XML. |
| Tsunami Warning Centers | Tsunami watches, advisories and warnings | tsunami.gov/events/xml/PAAQAtom.xml and PHEBAtom.xml | XML content type was unsupported by retrieval layer, so body parsing was not verified | Official Atom/CAP feeds. |
| JMA | Japan weather warnings/advisories | jma.go.jp/bosai/warning/data/warning/130000.json | Valid JSON warning response returned for test code 130000 | Configure the appropriate local JMA area/prefecture code in each Japanese city. |
| MeteoAlarm / MeteoGate | Weather warnings across 33 European member countries | feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-europe | Official feed directory lists country-specific Atom feeds and an Europe aggregate Atom feed; legacy RSS feeds were sunset on 2026-01-14 | City matching is based on warning text; inspect locality coverage. MeteoGate also offers public OGC EDR access. |
| EMSC | Worldwide seismic information | emsc-csem.org/service/rss/rss.php?typ=emsc | Not validated | Secondary earthquake source; USGS is the primary implemented source. |
| FEMA IPAWS | US public emergency messages including some WEA-originated messages | Official All-Hazards Information Feed | Not polled | Requires registration/approval and agreement; deliberately marked setup-required. |

## On-device test protocol

Run Sources → Test all on the target phone. For each endpoint record:
- DNS and TLS success (implicitly measured by request completion),
- HTTP status,
- response Content-Type,
- body length,
- elapsed time,
- exception text for failures.

Interpretation:
- ONLINE: HTTP 2xx and an endpoint answered.
- REDIRECT: HTTP 3xx; verify redirect destination.
- REACHABLE / ACCESS DENIED: 401/403; host answered but denied this request.
- HTTP ERROR: other HTTP error.
- UNREACHABLE: DNS/TLS/timeout/routing or client error.

Do not classify an HTTP 200 as proof that alert records parse, cover the selected city, or represent every hazard type. Verify a parsed record and its sent/effective/expires metadata.

## Notification tests

Settings contains local notification test channels for every built-in category. These only test Android notification permissions, channel sound/vibration and lock-screen display. They do not create or request a real emergency alert.

## Data formats and limitations

- JSON / JSON-LD / GeoJSON: parsed with org.json.
- RSS / Atom / CAP: basic XML entry parsing; providers with deeply nested or provider-specific schemas may need a dedicated adapter.
- NWS: alert event, headline, description, severity, effective/expiry and source URL are retained.
- USGS: magnitude, location, depth, time and source URL are retained.
- EONET: event title, description and coordinates are used for radius matching.
- JMA: regional code and headline text are retained.
- Generic feeds are associated with a city only when the item text contains its city/region/country. This avoids assigning an unrelated global alert to every city, but may miss messages with abbreviated place names.

## Known implementation gaps

- FEMA IPAWS feed integration is not enabled until the user obtains access.
- NHC, SWPC, MeteoAlarm, GDACS and EMSC feed schemas require validation against the device-side responses; diagnostics supplies actual status but not complete schema conformance.
- No official provider offers an all-hazards, globally complete feed. Custom HTTPS RSS/Atom/CAP feeds are supported as an extension point.
- Android WorkManager's periodic minimum is 15 minutes and execution can be deferred. This is not equivalent to carrier WEA delivery or real-time push.
