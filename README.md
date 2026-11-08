# Emergency Alert Aggregator

Android-only, local-first emergency alert monitor. The app has no developer-operated server, no account, no map and requests no device-location permission. The user manually configures cities and coordinates.

## Current implementation

- Manual city list with optional latitude/longitude, radius and regional code (e.g. JMA area code).
- Direct Android HTTP polling of public alert/data endpoints.
- Source diagnostics performed from the phone: concurrent HTTP probes, HTTP status, payload validation, MIME type, elapsed time, DNS/TLS/timeout errors. HTML pages no longer count as successful alert feeds.
- NWS point alerts, USGS global earthquake feed, EMSC FDSN JSON service, NASA EONET Events API, JMA warnings, and generic JSON/RSS/Atom/CAP/XML feed adapters.
- Additional configured feeds can be added by HTTPS URL.
- Local SQLite history and event de-duplication.
- Category-based Android notification channels and local test notifications.
- Background polling scheduled through WorkManager (Android may delay work).

## Important limitations

- This is an internet-feed reader, not the US Wireless Emergency Alerts (WEA) cell-broadcast system. It cannot subscribe to Miami/New York WEA from Russia.
- With no server or push gateway, alerts are received only when Android executes the app's scheduled work or the user starts a check. Doze, battery optimization, network outages and provider throttling can delay delivery.
- No app can guarantee updates when every configured source is unreachable from the user's network.
- FEMA IPAWS access requires approval/registration and is not polled without credentials.
- Global event sources have different coverage and are not a complete worldwide civil-protection service. Unavailable or unsupported categories are not silently represented as covered.
- Coordinates are user supplied; the app never requests GPS or network location permissions. No map is included.

## Build

The GitHub Actions workflow builds a debug APK and publishes it as a workflow artifact. To build locally with Android SDK 35, JDK 17 and Gradle 8.9:

    gradle --no-daemon assembleDebug

APK output: app/build/outputs/apk/debug/app-debug.apk.

## First run

1. Open Cities and add a city. Miami and New York presets are provided.
2. Supply latitude/longitude to enable NWS and distance-filtered feeds.
3. Open Sources and run Test all on the actual phone/network.
4. Open Settings and test notification channels.
5. Run Check now to poll sources.

Source diagnostics on the target phone are authoritative for availability from that phone's network. A successful DNS/TCP connection does not guarantee that a feed contains alerts for a city.


## 0.2 feedback fixes

- Added explicit Android edge-to-edge insets for the status bar, navigation bar and Scaffold content. The app header should no longer render underneath the notification shade/status bar.
- Source diagnostics now run four endpoints concurrently with bounded 9-second call timeout.
- The diagnostic result checks payload shape and MIME type. HTTP 200 with HTML is displayed as a wrong-format response rather than ONLINE.
- The EONET adapter now uses the documented JSON Events API and reads its geometry records.
- EMSC now uses the official SeismicPortal FDSN JSON endpoint rather than the retired/invalid RSS URL.
- The MeteoAlarm diagnostic probes an actual country Atom feed; runtime monitoring chooses the country feed matching each manually entered city.
- NWS diagnostic probes a Miami point instead of downloading the nationwide active-alert index.
