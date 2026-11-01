package com.ybapps.mimagi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

class PresenceService : Service() {
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val present = ConcurrentHashMap.newKeySet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val monitor = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            savedPeople().forEach { person ->
                val seen = lastSeen[person.first] ?: 0L
                if (person.first in present && now - seen > 45_000L) {
                    present.remove(person.first)
                    alert(person.second, false, person.first)
                }
            }
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(77, NotificationCompat.Builder(this, "mimagi_presence")
            .setSmallIcon(R.drawable.ic_mimagi_status)
            .setContentTitle("מי מגיע? פעיל")
            .setContentText("מחפש את המכשירים ששמרת")
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build())
        startBleScan()
        handler.postDelayed(monitor, 10_000L)
    }

    private fun savedPeople(): List<Pair<String, String>> = try {
        val a = JSONArray(getSharedPreferences("mimagi", Context.MODE_PRIVATE).getString("saved", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).getString("address") to a.getJSONObject(i).getString("name") }
    } catch (_: Exception) { emptyList() }

    @Suppress("MissingPermission")
    private fun startBleScan() {
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { stopSelf(); return }
        scanner = adapter.bluetoothLeScanner
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val person = savedPeople().firstOrNull { it.first == address } ?: return
                lastSeen[address] = System.currentTimeMillis()
                if (present.add(address)) alert(person.second, true, address)
            }
        }
        try { scanner?.startScan(callback) } catch (_: SecurityException) { stopSelf() }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("mimagi_presence", "מעקב מי מגיע?", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel("mimagi_alerts", "התרעות מי מגיע?", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        }
    }

    private fun alert(name: String, arrived: Boolean, address: String) {
        val prefs = getSharedPreferences("mimagi", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "both") ?: "both"
        val message = if (arrived) "$name מגיע!" else "$name התרחק"
        if (mode != "sound") {
            val notification = NotificationCompat.Builder(this, "mimagi_alerts")
                .setSmallIcon(R.drawable.ic_mimagi_status).setContentTitle("מי מגיע?")
                .setContentText(message).setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).build()
            getSystemService(NotificationManager::class.java).notify(address.hashCode(), notification)
        }
        if (mode != "screen") {
            val uri = prefs.getString("sound_$address", null)?.let { android.net.Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            try { RingtoneManager.getRingtone(this, uri)?.play() } catch (_: Exception) { }
        }
    }

    @Suppress("MissingPermission")
    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        try { callback?.let { scanner?.stopScan(it) } } catch (_: Exception) { }
        getSharedPreferences("mimagi", Context.MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
