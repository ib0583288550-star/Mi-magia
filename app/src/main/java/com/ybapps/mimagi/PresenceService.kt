package com.ybapps.mimagi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var adapter: BluetoothAdapter? = null
    private var receiver: BroadcastReceiver? = null
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val present = ConcurrentHashMap.newKeySet<String>()
    private val handler = Handler(Looper.getMainLooper())

    private val monitor = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            savedPeople().forEach { person ->
                val seen = lastSeen[person.first] ?: 0L
                if (person.first in present && now - seen > 90_000L) {
                    present.remove(person.first)
                    alert(person.second, false, person.first)
                }
            }
            handler.postDelayed(this, 10_000L)
        }
    }

    private val restartDiscovery = object : Runnable {
        override fun run() {
            try {
                val bt = adapter
                if (bt != null && bt.isEnabled && !bt.isDiscovering) bt.startDiscovery()
            } catch (_: SecurityException) { stopSelf() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(77, NotificationCompat.Builder(this, "mimagi_presence")
            .setSmallIcon(R.drawable.ic_mimagi_status)
            .setContentTitle("מי מגיע? פעיל")
            .setContentText("מחפש מכשירי Bluetooth רגילים")
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build())
        startClassicScan()
        handler.postDelayed(monitor, 10_000L)
    }

    private fun savedPeople(): List<Pair<String, String>> = try {
        val a = JSONArray(getSharedPreferences("mimagi", Context.MODE_PRIVATE).getString("saved", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).getString("address") to a.getJSONObject(i).getString("name") }
    } catch (_: Exception) { emptyList() }

    @Suppress("MissingPermission")
    private fun startClassicScan() {
        val scanPermission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        val connectPermission = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (ContextCompat.checkSelfPermission(this, scanPermission) != PackageManager.PERMISSION_GRANTED || !connectPermission) {
            stopSelf(); return
        }
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) { stopSelf(); return }
        adapter = bt
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> try {
                        val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null) {
                            val address = device.address
                            val person = savedPeople().firstOrNull { it.first == address } ?: return
                            lastSeen[address] = System.currentTimeMillis()
                            if (present.add(address)) alert(person.second, true, address)
                        }
                    } catch (_: SecurityException) { }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> handler.postDelayed(restartDiscovery, 2_000L)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
        try { bt.startDiscovery() } catch (_: SecurityException) { stopSelf() }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel("mimagi_presence", "מעקב מי מגיע?", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(android.app.NotificationChannel("mimagi_alerts", "התרעות מי מגיע?", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
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
        handler.removeCallbacks(restartDiscovery)
        try { adapter?.let { if (it.isDiscovering) it.cancelDiscovery() } } catch (_: Exception) { }
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) { }
        getSharedPreferences("mimagi", Context.MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
