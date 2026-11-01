package com.ybapps.mimagi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

data class NearbyDevice(val address: String, val name: String, val rssi: Int)
data class SavedDevice(val address: String, val name: String)

class MainActivity : ComponentActivity() {
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var onFound: ((NearbyDevice) -> Unit)? = null
    private val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) +
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun hasPermissions() = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    private fun startScan(listener: (NearbyDevice) -> Unit) {
        if (!hasPermissions()) { permissionLauncher.launch(permissions); return }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner
        onFound = listener
        callback?.let { scanner?.stopScan(it) }
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try { result.device.name } catch (_: SecurityException) { null }
                    ?: result.scanRecord?.deviceName ?: "מכשיר Bluetooth"
                onFound?.invoke(NearbyDevice(result.device.address, name, result.rssi))
            }
        }
        scanner?.startScan(callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { callback?.let { scanner?.stopScan(it) } } catch (_: Exception) { }
        callback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF7DD3FC),
                secondary = androidx.compose.ui.graphics.Color(0xFF34D399),
                background = androidx.compose.ui.graphics.Color(0xFF0B1220),
                surface = androidx.compose.ui.graphics.Color(0xFF162235)
            )) {
                MiMagiScreen({ startScan(it) }, { stopScan() })
            }
        }
    }
    override fun onDestroy() { stopScan(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiMagiScreen(startScan: ((NearbyDevice) -> Unit) -> Unit, stopScan: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mimagi", Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(loadSaved(prefs)) }
    var nearby by remember { mutableStateOf(listOf<NearbyDevice>()) }
    var scanning by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "both") ?: "both") }
    var banner by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<NearbyDevice?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var lastAlert by remember { mutableStateOf("") }

    fun save(list: List<SavedDevice>) {
        saved = list
        prefs.edit().putString("saved", JSONArray().apply {
            list.forEach { put(JSONObject().put("address", it.address).put("name", it.name)) }
        }.toString()).apply()
    }
    fun alert(name: String) {
        banner = "$name מגיע!"
        if (mode != "screen") {
            try { RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() } catch (_: Exception) { }
        }
        if (mode != "screen" && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                android.app.NotificationChannel("mimagi", "התרעות מי מגיע", android.app.NotificationManager.IMPORTANCE_HIGH))
            val notification = android.app.Notification.Builder(context, "mimagi")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("מי מגיע?").setContentText("$name מגיע!").setAutoCancel(true).build()
            manager.notify(name.hashCode(), notification)
        }
    }

    Scaffold(topBar = { TopAppBar(title = {
        Column {
            Text("מי מגיע?", fontWeight = FontWeight.Bold)
            Text("מזהים מכשירי Bluetooth בסביבה", style = MaterialTheme.typography.labelSmall)
        }
    }, actions = { Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(14.dp)) }) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item {
                banner?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔔  $message", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { banner = null }) { Text("סגור") }
                        }
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("סריקה בסביבה", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("בחר מכשיר שנמצא ושמור אותו בשם אישי.")
                        Button(onClick = {
                            if (scanning) { scanning = false; stopScan() }
                            else {
                                nearby = emptyList(); scanning = true
                                startScan { device ->
                                    nearby = (nearby.filterNot { it.address == device.address } + device).sortedByDescending { it.rssi }
                                    val known = saved.firstOrNull { it.address == device.address }
                                    if (known != null && lastAlert != known.address) { lastAlert = known.address; alert(known.name) }
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(8.dp))
                            Text(if (scanning) "עצור סריקה" else "התחל סריקה")
                        }
                        Text(if (scanning) "הסריקה פעילה כל עוד האפליקציה פתוחה" else "הסריקה כבויה", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("סוג התרעה", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        listOf("both" to "מסך + קול", "screen" to "מסך בלבד", "sound" to "קול בלבד").forEach { (value, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(mode == value, onClick = { mode = value; prefs.edit().putString("mode", value).apply() })
                                Text(label)
                            }
                        }
                    }
                }
            }
            item { Text("מכשירים שנמצאו (${nearby.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (nearby.isEmpty()) item { Text("עדיין לא נמצאו מכשירים. ודא שמכשירים בסביבה משדרים BLE.", style = MaterialTheme.typography.bodySmall) }
            items(nearby, key = { it.address }) { device ->
                Card(onClick = { selected = device; nameInput = device.name.takeUnless { it == "מכשיר Bluetooth" } ?: "" }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text("אות: ${device.rssi} dBm • ${device.address}", style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(Icons.Default.PersonAdd, "שמור")
                    }
                }
            }
            item { Text("אנשים שמורים (${saved.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(saved, key = { it.address }) { person ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("👤")
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(person.name, fontWeight = FontWeight.Bold)
                            Text(person.address, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { save(saved.filterNot { it.address == person.address }) }) { Text("מחיקה") }
                    }
                }
            }
            item { Text("חשוב: לא כל טלפון משדר BLE באופן שניתן לגלות. כתובת המכשיר עשויה להשתנות, ולכן זיהוי חוזר אינו מובטח.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    selected?.let { device ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("שמירת מכשיר") },
            text = {
                Column {
                    Text("איזה שם להציג כשהמכשיר יזוהה?")
                    OutlinedTextField(nameInput, { nameInput = it }, label = { Text("שם, למשל דני") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    save(saved.filterNot { it.address == device.address } + SavedDevice(device.address, nameInput.trim().ifBlank { device.name }))
                    selected = null
                }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("ביטול") } }
        )
    }
}

private fun loadSaved(prefs: android.content.SharedPreferences): List<SavedDevice> = try {
    val array = JSONArray(prefs.getString("saved", "[]"))
    (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        SavedDevice(item.getString("address"), item.getString("name"))
    }
} catch (_: Exception) { emptyList() }
