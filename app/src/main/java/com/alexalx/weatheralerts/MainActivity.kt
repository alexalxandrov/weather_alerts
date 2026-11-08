package com.alexalx.weatheralerts

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.context = applicationContext
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        AlertNotifier.ensureChannels(getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
        MonitorScheduler.schedule(applicationContext)
        setContent { EaaApp() }
    }
}

private val appColors = darkColorScheme(
    primary = Color(0xFF83B7FF), secondary = Color(0xFF75D6C1),
    background = Color(0xFF0B1118), surface = Color(0xFF141E29),
    surfaceVariant = Color(0xFF202D3B), onBackground = Color(0xFFE6EDF5),
    onSurface = Color(0xFFE6EDF5)
)

@Composable
private fun EaaApp() {
    val context = LocalContext.current
    val db = remember { LocalDatabase(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Alerts") }
    var showCityDialog by remember { mutableStateOf(false) }
    var showFeedDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready · manual locations only") }
    val cities = remember { mutableStateListOf<City>().apply { addAll(db.cities()) } }
    val alerts = remember { mutableStateListOf<AlertRecord>().apply { addAll(db.activeAlerts()) } }
    var cacheBytes by remember { mutableStateOf(db.cacheUsageBytes()) }
    val monitorPrefs = remember { context.getSharedPreferences(MonitorScheduler.PREFS, android.content.Context.MODE_PRIVATE) }
    var lastAutoRun by remember { mutableStateOf(monitorPrefs.getLong(MonitorScheduler.KEY_LAST_RUN, 0L)) }
    var lastAutoNew by remember { mutableStateOf(monitorPrefs.getInt(MonitorScheduler.KEY_LAST_NEW, 0)) }
    var lastAutoError by remember { mutableStateOf(monitorPrefs.getString(MonitorScheduler.KEY_LAST_ERROR, "").orEmpty()) }
    val probes = remember { mutableStateMapOf<String, ProbeResult>().apply { putAll(db.probes()) } }
    val feeds = remember { mutableStateListOf<CustomFeed>().apply { addAll(db.customFeeds()) } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        statusMessage = if (granted) "Notifications enabled" else "Android notification permission denied"
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun reload() {
        cities.clear(); cities.addAll(db.cities())
        alerts.clear(); alerts.addAll(db.activeAlerts())
        cacheBytes = db.cacheUsageBytes()
        lastAutoRun = monitorPrefs.getLong(MonitorScheduler.KEY_LAST_RUN, 0L)
        lastAutoNew = monitorPrefs.getInt(MonitorScheduler.KEY_LAST_NEW, 0)
        lastAutoError = monitorPrefs.getString(MonitorScheduler.KEY_LAST_ERROR, "").orEmpty()
        probes.clear(); probes.putAll(db.probes())
        feeds.clear(); feeds.addAll(db.customFeeds())
    }

    // Refresh the visible journal/cache after WorkManager has updated them in the background.
    LaunchedEffect(tab, isWorking) {
        if (!isWorking) {
            while (true) {
                delay(30_000)
                if (!isWorking) reload()
            }
        }
    }


    MaterialTheme(colorScheme = appColors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("EMERGENCY ALERT", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text("Aggregator", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        AssistChip(onClick = {}, label = { Text("LOCAL ONLY") })
                    }
                    Text(statusMessage, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    listOf("Alerts", "Cities", "Sources", "Settings").forEach { key ->
                        NavigationBarItem(selected = tab == key, onClick = { tab = key },
                            icon = { Text(when (key) { "Alerts" -> "◉"; "Cities" -> "⌖"; "Sources" -> "⇄"; else -> "⚙" }) },
                            label = { Text(key) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                when (tab) {
                    "Alerts" -> AlertsScreen(alerts, cities, isWorking,
                        onClear = { db.clearJournal(); reload(); statusMessage = "Journal cleared · source cache retained" },
                        onRefresh = {
                            scope.launch {
                                isWorking = true
                                statusMessage = "Polling configured sources…"
                                val count = withContext(Dispatchers.IO) { AlertEngine.sync(context) }
                                reload()
                                statusMessage = "Completed · " + count + " new active alerts · " + DateFormat.getTimeInstance().format(Date())
                                isWorking = false
                            }
                        },
                        onOpen = { url ->
                            if (url.startsWith("http")) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        })
                    "Cities" -> CitiesScreen(cities, { showCityDialog = true }, { db.deleteCity(it); reload() })
                    "Sources" -> SourcesScreen(probes, feeds, isWorking,
                        onTest = {
                            scope.launch {
                                isWorking = true
                                statusMessage = "Testing all public endpoints from this phone…"
                                withContext(Dispatchers.IO) { SourceDiagnostics.runAll(context) }
                                reload()
                                statusMessage = "Source tests completed · " + DateFormat.getTimeInstance().format(Date())
                                isWorking = false
                            }
                        },
                        onAddFeed = { showFeedDialog = true },
                        onDeleteFeed = { db.deleteCustomFeed(it); reload() })
                    else -> SettingsScreen(
                        cacheBytes = cacheBytes,
                        lastAutoRun = lastAutoRun,
                        lastAutoNew = lastAutoNew,
                        lastAutoError = lastAutoError,
                        onTestSound = { AlertNotifier.test(context, it) },
                        onRefresh = {
                            val previousRun = lastAutoRun
                            MonitorScheduler.runNow(context)
                            statusMessage = "One-time automatic worker queued"
                            scope.launch {
                                var elapsed = 0
                                while (elapsed < 240) {
                                    delay(1000)
                                    val run = withContext(Dispatchers.IO) {
                                        monitorPrefs.getLong(MonitorScheduler.KEY_LAST_RUN, 0L)
                                    }
                                    if (run > previousRun) {
                                        reload()
                                        val lastError = monitorPrefs.getString(MonitorScheduler.KEY_LAST_ERROR, "").orEmpty()
                                        statusMessage = if (lastError.isBlank()) {
                                            "Background worker completed · " +
                                                monitorPrefs.getInt(MonitorScheduler.KEY_LAST_NEW, 0) + " new active alerts"
                                        } else {
                                            "Background worker error · " + lastError
                                        }
                                        break
                                    }
                                    elapsed++
                                }
                            }
                        })
                }
            }
        }
    }

    if (showCityDialog) CityDialog(
        onDismiss = { showCityDialog = false },
        onSave = { city -> db.saveCity(city); reload(); showCityDialog = false; statusMessage = "Added " + city.name }
    )
    if (showFeedDialog) FeedDialog(
        onDismiss = { showFeedDialog = false },
        onSave = { name, url -> db.addCustomFeed(name, url); reload(); showFeedDialog = false; statusMessage = "Custom feed added" }
    )
}

@Composable
private fun ScreenHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        trailing?.invoke()
    }
}

@Composable
private fun AlertsScreen(
    alerts: List<AlertRecord>,
    cities: List<City>,
    isWorking: Boolean,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp)) {
        ScreenHeader("Active alert journal", cities.size.toString() + " manually selected locations") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                Button(onClick = onRefresh, enabled = !isWorking) {
                    Text(if (isWorking) "Checking…" else "Check now")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Only new, active records issued within the last 24 hours. Expired, future and older records are hidden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(Modifier.height(10.dp))
        if (cities.isEmpty()) {
            EmptyCard("No monitored cities", "Open Cities and add a place manually. Device location is never requested.")
        } else if (alerts.isEmpty()) {
            EmptyCard("No new active alerts", "The journal is empty or has been cleared. Previously seen events remain in the local cache and will not be re-added.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
            items(alerts, key = { it.id }) { alert ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(alert.url) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(alert.category.uppercase(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(alert.severity, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(alert.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(alert.city + " · " + alert.sourceId, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        if (alert.description.isNotBlank()) {
                            Text(alert.description, style = MaterialTheme.typography.bodySmall, maxLines = 7)
                        }
                        if (alert.url.isNotBlank()) {
                            Text("Open source ↗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear active alert journal?") },
            text = { Text("The visible journal will be cleared. The separate 10 MB cache and seen-event IDs will be retained, so existing alerts will not return after the next refresh.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear journal") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CitiesScreen(cities: List<City>, onAdd: () -> Unit, onDelete: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenHeader("Monitored cities", "No device location · manual configuration") {
            Button(onClick = onAdd) { Text("+ Add") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Add a place with a name and country; coordinates enable point/radius-based sources.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cities, key = { it.id }) { city ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(city.name, fontWeight = FontWeight.Bold)
                            Text(listOf(city.region, city.country).filter { it.isNotBlank() }.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall)
                            val coordinateText = if (city.latitude != null && city.longitude != null)
                                city.latitude.toString() + ", " + city.longitude.toString() + " · " + city.radiusKm + " km radius"
                                else "No coordinates: only name-matched feeds"
                            Text(coordinateText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                            if (city.regionCode.isNotBlank()) Text("Regional source code: " + city.regionCode, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onDelete(city.id) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesScreen(probes: Map<String, ProbeResult>, feeds: List<CustomFeed>, isWorking: Boolean,
                          onTest: () -> Unit, onAddFeed: () -> Unit, onDeleteFeed: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenHeader("Source diagnostics", "Tests run from this Android device") {
            Button(onClick = onTest, enabled = !isWorking) { Text(if (isWorking) "Testing…" else "Test all") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Tests make real HTTP GET requests. HTTP 403 means the host answered but denied this client; unreachable means DNS, TLS, timeout or routing failed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(SourceCatalog.all, key = { it.id }) { source ->
                val p = probes[source.id]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            val label = if (source.requiresSetup) "SETUP REQUIRED" else p?.status ?: "NOT TESTED"
                            AssistChip(onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                        }
                        Text(source.coverage + " · " + source.format, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text(source.notes, style = MaterialTheme.typography.bodySmall)
                        Text(source.endpoint, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        if (p != null) {
                            val detail = "HTTP " + (p.httpCode?.toString() ?: "—") + " · " + p.elapsedMs + " ms · " +
                                DateFormat.getTimeInstance().format(Date(p.checkedAt)) + "\n" + p.detail
                            Text(detail, style = MaterialTheme.typography.labelSmall,
                                color = if (p.status.startsWith("ONLINE")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                ScreenHeader("Custom feeds", "Add official RSS, Atom or CAP/XML endpoints")
                Button(onClick = onAddFeed, modifier = Modifier.fillMaxWidth()) { Text("+ Add custom feed") }
            }
            items(feeds, key = { "feed_" + it.id }) { feed ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(feed.name, fontWeight = FontWeight.SemiBold)
                            Text(feed.url, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        }
                        TextButton(onClick = { onDeleteFeed(feed.id) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    cacheBytes: Long,
    lastAutoRun: Long,
    lastAutoNew: Int,
    lastAutoError: String,
    onTestSound: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val categories = listOf("Severe Storm", "Tsunami", "Earthquake", "Flood", "Wildfire",
        "Volcano", "Radiological / Nuclear", "Chemical", "Health", "Landslide", "Avalanche",
        "Winter Weather", "Extreme Heat", "Extreme Cold", "Air Quality", "Space Weather", "Public Safety", "Weather", "Other")
    var selected by remember { mutableStateOf(categories.first()) }
    val cacheMb = cacheBytes.toDouble() / (1024.0 * 1024.0)
    val lastRunLabel = if (lastAutoRun > 0) DateFormat.getDateTimeInstance().format(Date(lastAutoRun)) else "Not run yet"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings & tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        EmptyCard("Local-only design", "No account, no developer server, no map and no location permission. Sources are polled directly from this phone.")

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Automatic monitoring", fontWeight = FontWeight.Bold)
                Text("WorkManager periodic check is scheduled at the Android minimum interval of 15 minutes. Android may defer execution during Doze or battery restrictions.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Last background run: " + lastRunLabel, style = MaterialTheme.typography.bodySmall)
                Text("New active alerts from last run: " + lastAutoNew, style = MaterialTheme.typography.bodySmall)
                if (lastAutoError.isNotBlank()) {
                    Text("Last worker error: " + lastAutoError, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Test automatic worker now") }
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Local alert cache", fontWeight = FontWeight.Bold)
                Text(String.format(java.util.Locale.US, "%.2f MB / 10 MB", cacheMb),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(
                    progress = (cacheBytes.toFloat() / (10f * 1024f * 1024f)).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Cache retains and refreshes seen alert records for up to 24 hours. Older entries are removed automatically; the cache is capped at 10 MB. Clearing the journal does not clear this cache.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notification sound test", fontWeight = FontWeight.Bold)
                Text("A local test only. It does not contact emergency authorities or represent a real alert.",
                    style = MaterialTheme.typography.bodySmall)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { selected = item; expanded = false })
                        }
                    }
                }
                Button(onClick = { onTestSound(selected) }, modifier = Modifier.fillMaxWidth()) { Text("Send test notification") }
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Important limitations", fontWeight = FontWeight.Bold)
                Text("• No remote WEA cell-broadcast reception. This app reads internet feeds, not US carrier broadcasts.\n• No server means no push while Android stops the app or the phone has no network.\n• Some providers require credentials, regional codes or approval.\n• This is not a replacement for local official life-safety alerts.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CityDialog(onDismiss: () -> Unit, onSave: (City) -> Unit) {
    var name by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("300") }
    var error by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<CityMatch>>(emptyList()) }
    val searchScope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add monitored location") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { name="Miami"; region="Florida"; country="USA"; lat="25.7617"; lon="-80.1918" }, label = { Text("Miami preset") })
                    AssistChip(onClick = { name="New York"; region="New York"; country="USA"; lat="40.7128"; lon="-74.0060" }, label = { Text("New York preset") })
                }
                OutlinedTextField(name, { name=it }, label={Text("City / place name *")}, singleLine=true)
                OutlinedTextField(country, { country=it }, label={Text("Country *")}, singleLine=true)
                OutlinedTextField(region, { region=it }, label={Text("State / province / region")}, singleLine=true)
                Button(onClick = {
                    searchScope.launch {
                        searching = true
                        searchMessage = "Searching place names…"
                        val query = listOf(name, region, country).filter { it.isNotBlank() }.joinToString(", ")
                        val found = withContext(Dispatchers.IO) { CityGeocoder.search(query) }
                        matches = found
                        searchMessage = if (found.isEmpty()) "No results or geocoder unavailable. You can enter coordinates manually."
                            else "Choose a result to fill coordinates."
                        searching = false
                    }
                }, enabled = !searching && name.isNotBlank() && country.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (searching) "Searching…" else "Find city coordinates (internet)")
                }
                if (searchMessage.isNotBlank()) Text(searchMessage, style = MaterialTheme.typography.bodySmall)
                matches.forEach { match ->
                    TextButton(onClick = {
                        lat = match.latitude.toString()
                        lon = match.longitude.toString()
                        searchMessage = match.displayName
                        matches = emptyList()
                    }) { Text(match.displayName, maxLines = 2) }
                }
                OutlinedTextField(lat, { lat=it }, label={Text("Latitude")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                OutlinedTextField(lon, { lon=it }, label={Text("Longitude")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                OutlinedTextField(radius, { radius=it }, label={Text("Nearby event radius, km")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(code, { code=it }, label={Text("Optional regional code (e.g. JMA 130000)")}, singleLine=true)
                Text("No GPS or device location is used. Coordinates are entered or pasted by you. Coordinates enable NWS and distance-filtered global sources.",
                    style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color=MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val latitude = lat.toDoubleOrNull()
                val longitude = lon.toDoubleOrNull()
                if (name.isBlank() || country.isBlank()) error = "City and country are required."
                else if ((lat.isNotBlank() || lon.isNotBlank()) && (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0))
                    error = "Enter valid latitude and longitude."
                else onSave(City(name=name.trim(), country=country.trim(), region=region.trim(),
                    latitude=latitude, longitude=longitude, regionCode=code.trim(), radiusKm=(radius.toIntOrNull() ?: 300).coerceIn(1, 3000)))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick=onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FeedDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom alert feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name=it }, label={Text("Feed name")}, singleLine=true)
                OutlinedTextField(url, { url=it }, label={Text("HTTPS RSS / Atom / CAP URL")}, singleLine=true)
                Text("Only add trusted sources. The app fetches this URL directly from your phone and parses standard RSS, Atom or CAP/XML entries.",
                    style=MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color=MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (name.isBlank() || !url.startsWith("https://")) error = "Enter a name and an HTTPS URL."
            else onSave(name.trim(), url.trim())
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick=onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
        }
    }
}
