package com.ybapps.mimagi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

data class NearbyDevice(val address: String, val name: String, val rssi: Int)
data class SavedDevice(val address: String, val name: String)

class MainActivity : ComponentActivity() {
    private var onFound: ((NearbyDevice) -> Unit)? = null
    private var classicReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var pendingListener: ((NearbyDevice) -> Unit)? = null
    private var onScanStatus: ((String) -> Unit)? = null
    private val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = permissions.all { result[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        val listener = pendingListener
        pendingListener = null
        if (granted && listener != null) {
            startScan(listener, onScanStatus ?: {})
        } else if (!granted) {
            onScanStatus?.invoke("לא ניתנה הרשאת Bluetooth. אפשר לאשר אותה בהגדרות האפליקציה.")
        }
    }

    private fun hasPermissions(): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    private fun startScan(listener: (NearbyDevice) -> Unit, status: (String) -> Unit) {
        onScanStatus = status
        if (!hasPermissions()) {
            pendingListener = listener
            permissionLauncher.launch(permissions)
            status("מבקש הרשאת Bluetooth…")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { status("במכשיר הזה לא נמצא מתאם Bluetooth."); return }
        if (!adapter.isEnabled) { status("הפעל Bluetooth במכשיר ונסה שוב."); return }
        bluetoothAdapter = adapter
        onFound = listener
        try { classicReceiver?.let { if (receiverRegistered) unregisterReceiver(it) } } catch (_: Exception) {}
        receiverRegistered = false
        classicReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> { status("סריקת Bluetooth רגילה התחילה. מחפש מכשירים גלויים…"); return }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { status("הסריקה הסתיימה. לחץ שוב כדי לסרוק מחדש."); return }
                    BluetoothDevice.ACTION_FOUND -> Unit
                    else -> return
                }
                try {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        val address = device.address
                        val name = device.name ?: "מכשיר Bluetooth"
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        onFound?.invoke(NearbyDevice(address, name, rssi))
                    }
                } catch (_: SecurityException) {
                    status("אין הרשאה לקרוא פרטי Bluetooth. אשר הרשאת מכשירים בקרבת מקום.")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(classicReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(classicReceiver, filter)
        receiverRegistered = true
        try {
            if (adapter.isDiscovering) {
                status("סריקה רגילה כבר פעילה — מאזין למכשירים שנמצאו.")
            } else if (adapter.startDiscovery()) {
                status("סריקה רגילה הופעלה. מכשירים צריכים להיות גלויים כדי להופיע.")
            } else {
                status("Android לא הצליח להתחיל סריקה רגילה. בדוק ש-Bluetooth פועל, שההרשאות אושרו ושאין סריקה אחרת פעילה.")
            }
        } catch (e: SecurityException) {
            status("שגיאת הרשאה בסריקה רגילה: ${e.message ?: "Android חסם את הסריקה"}.")
        } catch (e: Exception) {
            status("שגיאה בסריקה רגילה: ${e.javaClass.simpleName}: ${e.message ?: "ללא פירוט"}.")
        }
    }
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { bluetoothAdapter?.let { if (it.isDiscovering) it.cancelDiscovery() } } catch (_: Exception) {}
        try { classicReceiver?.let { if (receiverRegistered) unregisterReceiver(it) } } catch (_: Exception) {}
        receiverRegistered = false
        classicReceiver = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF7DD3FC),
                secondary = androidx.compose.ui.graphics.Color(0xFF34D399),
                background = androidx.compose.ui.graphics.Color(0xFF0B1220),
                surface = androidx.compose.ui.graphics.Color(0xFF132238),
                onSurface = androidx.compose.ui.graphics.Color(0xFFF2F7FC),
                onBackground = androidx.compose.ui.graphics.Color(0xFFF2F7FC),
                surfaceVariant = androidx.compose.ui.graphics.Color(0xFF20344B),
                error = androidx.compose.ui.graphics.Color(0xFFFF7885)
            ), shapes = androidx.compose.material3.Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))) {
                MiMagiScreen({ listener, status -> startScan(listener, status) }, { stopScan() })
            }
        }
    }
    override fun onDestroy() { stopScan(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiMagiScreen(startScan: ((NearbyDevice) -> Unit, (String) -> Unit) -> Unit, stopScan: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mimagi", Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(loadSaved(prefs)) }
    var nearby by remember { mutableStateOf(listOf<NearbyDevice>()) }
    var scanning by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "both") ?: "both") }
    var banner by remember { mutableStateOf<String?>(null) }
    var scanStatus by remember { mutableStateOf("לחץ על התחלת סריקה כדי לחפש מכשירי Bluetooth רגילים.") }
    var selected by remember { mutableStateOf<NearbyDevice?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var lastAlert by remember { mutableStateOf("") }
    var backgroundEnabled by remember { mutableStateOf(prefs.getBoolean("background_enabled", false)) }
    var editingPerson by remember { mutableStateOf<SavedDevice?>(null) }
    var editName by remember { mutableStateOf("") }
    var pendingSoundAddress by remember { mutableStateOf<String?>(null) }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val address = pendingSoundAddress
        val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (address != null && uri != null) prefs.edit().putString("sound_$address", uri.toString()).apply()
        pendingSoundAddress = null
    }

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

    Scaffold(containerColor = Color(0xFF08111F), topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF08111F), titleContentColor = Color.White), title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Color(0xFF155E75), Color(0xFF123B54))), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF58D7F5), modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("מי מגיע?", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                Text("SMART PRESENCE", color = Color(0xFF58D7F5), style = MaterialTheme.typography.labelSmall)
            }
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
                    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF12364B), Color(0xFF102337))), RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("BLUETOOTH RADAR", color = Color(0xFF58D7F5), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text("מי בסביבה שלך?", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("מצא מכשירים קרובים ושמור אותם בשם אישי כדי לזהות אותם בפעם הבאה.", color = Color(0xFFC2D2E2), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            if (scanning) { scanning = false; stopScan() }
                            else {
                                nearby = emptyList(); scanning = true
                                startScan({ device ->
                                    nearby = (nearby.filterNot { it.address == device.address } + device).sortedByDescending { it.rssi }
                                    val known = saved.firstOrNull { it.address == device.address }
                                    if (known != null && lastAlert != known.address) { lastAlert = known.address; alert(known.name) }
                                }, { message -> scanStatus = message })
                            }
                        }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF58D7F5), contentColor = Color(0xFF06202B))) {
                            Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(8.dp))
                            Text(if (scanning) "עצור סריקה" else "התחל סריקה")
                        }
                        val isScanError = scanStatus.contains("שגיאה") || scanStatus.contains("נכשל") || scanStatus.contains("חסרה") || scanStatus.contains("חסם") || scanStatus.contains("לא התחילה")
                        Card(colors = CardDefaults.cardColors(containerColor = if (isScanError) Color(0xFF3A202B) else Color(0xFF102F36)), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (isScanError) Color(0xFF9B4655) else Color(0xFF235D58))) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                                Text(if (isScanError) "!" else "●", color = if (isScanError) Color(0xFFFF8792) else Color(0xFF65E0B5), fontWeight = FontWeight.Black)
                                Spacer(Modifier.width(9.dp))
                                Column {
                                    Text(if (isScanError) "פרטי שגיאה" else "מצב הסריקה", fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(scanStatus, style = MaterialTheme.typography.bodySmall, color = if (isScanError) Color(0xFFFFC2C8) else Color(0xFFB9D4D5))
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (backgroundEnabled) {
                                    context.stopService(Intent(context, PresenceService::class.java))
                                    backgroundEnabled = false
                                    prefs.edit().putBoolean("background_enabled", false).apply()
                                } else {
                                    val needed = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
                                    if (ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED) {
                                        ContextCompat.startForegroundService(context, Intent(context, PresenceService::class.java))
                                        backgroundEnabled = true
                                        prefs.edit().putBoolean("background_enabled", true).apply()
                                    } else banner = "צריך לאשר הרשאת Bluetooth/מיקום כדי להפעיל מעקב ברקע"
                                }
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text(if (backgroundEnabled) "כבה התרעות ברקע" else "הפעל התרעות ברקע") }
                        Text("המעקב ממשיך גם כשהאפליקציה ברקע, בכפוף למגבלות Android ו-Bluetooth.", style = MaterialTheme.typography.bodySmall)
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
            if (nearby.isEmpty()) item { Text("עדיין לא נמצאו מכשירים. כדי שמכשיר יופיע, יש להפעיל בו מצב גלוי ל-Bluetooth.", style = MaterialTheme.typography.bodySmall) }
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
                        TextButton(onClick = { editingPerson = person; editName = person.name }) { Text("עריכה") }
                        TextButton(onClick = {
                            pendingSoundAddress = person.address
                            val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "בחר צליל עבור ${person.name}")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            }
                            soundPicker.launch(pick)
                        }) { Text("צליל") }
                        TextButton(onClick = { save(saved.filterNot { it.address == person.address }); prefs.edit().remove("sound_${person.address}").apply() }) { Text("מחיקה") }
                    }
                }
            }
            item { Text("חשוב: הסריקה הרגילה מזהה מכשירים שנמצאים במצב גלוי. כתובת המכשיר עשויה להשתנות, ולכן זיהוי חוזר אינו מובטח.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    editingPerson?.let { person ->
        AlertDialog(
            onDismissRequest = { editingPerson = null },
            title = { Text("עריכת שם") },
            text = { OutlinedTextField(editName, { editName = it }, label = { Text("שם שיופיע בהתרעה") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                save(saved.map { if (it.address == person.address) it.copy(name = editName.trim().ifBlank { person.name }) else it })
                editingPerson = null
            }) { Text("שמור") } },
            dismissButton = { TextButton(onClick = { editingPerson = null }) { Text("ביטול") } }
        )
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
