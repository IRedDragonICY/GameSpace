package com.ireddragonicy.gamespace.gamebar

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R

/**
 * Bluetooth devices page (rendered inline inside the panel, mirroring
 * PingDetailsPanel / TunerScreen) inspired by the system QS bluetooth panel:
 * a master on/off switch plus a list of paired devices that can be tapped
 * to connect or disconnect.
 *
 * NOTE: must NOT use AlertDialog/Dialog — the panel is hosted in an overlay
 * ComposeView (service context) whose window token is null, so window-based
 * Compose dialogs crash with BadTokenException.
 */
@Composable
fun BluetoothDevicesPanel(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Header: title + close on one 32dp row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCompat(
                R.drawable.materialsymbols_ic_bluetooth_rounded_filled,
                contentDescription = null,
                tint = LocalPanelAccent.current,
                size = 18.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "BLUETOOTH",
                color = PanelTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                modifier = Modifier.weight(1f),
            )
            IconCompat(
                R.drawable.materialsymbols_ic_close_rounded_filled,
                contentDescription = "Close",
                tint = PanelTheme.TextDim,
                size = 20.dp,
                modifier = Modifier.clickable { onClose() },
            )
        }
        BluetoothDevicesContent()
    }
}

private data class BtDeviceRow(
    val device: BluetoothDevice,
    val connected: Boolean,
)

@Composable
private fun BluetoothDevicesContent() {
    val context = LocalContext.current
    val adapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    var isEnabled by remember { mutableStateOf(runCatching { adapter?.isEnabled ?: false }.getOrDefault(false)) }
    var devices by remember { mutableStateOf<List<BtDeviceRow>>(emptyList()) }

    fun markConnected(deviceId: String, connected: Boolean) {
        connectedRef.put(deviceId, connected)
        devices = devices.map { row ->
            if (row.device.address == deviceId) row.copy(connected = connected) else row
        }
    }

    fun syncDevices() {
        val bonded = runCatching { adapter?.bondedDevices?.toList() }
            .getOrDefault(emptyList())
            .orEmpty()
        devices = bonded
            .map { device -> BtDeviceRow(device, isDeviceConnected(device)) }
            .sortedWith(compareByDescending<BtDeviceRow> { it.connected }.thenBy { it.device.name })
    }

    fun toggleDeviceConnection(row: BtDeviceRow) {
        runCatching {
            val methodName = if (row.connected) "disconnect" else "connect"
            row.device.javaClass.getMethod(methodName).invoke(row.device)
        }
    }

    DisposableEffect(adapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR
                        )
                        isEnabled = state == BluetoothAdapter.STATE_ON
                        if (!isEnabled) connectedRef.clear()
                        syncDevices()
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )?.let {
                            markConnected(it.address, true)
                        }
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )?.let {
                            markConnected(it.address, false)
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> syncDevices()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        runCatching { context.registerReceiver(receiver, filter) }
        isEnabled = runCatching { adapter?.isEnabled ?: false }.getOrDefault(false)
        syncDevices()
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Column(
        modifier = Modifier.height(320.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ── Master switch ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .clickable {
                    runCatching {
                        if (isEnabled) adapter?.disable() else adapter?.enable()
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Use Bluetooth", color = PanelTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isEnabled) "On" else "Off",
                    color = LocalPanelAccent.current,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { on ->
                    runCatching { if (on) adapter?.enable() else adapter?.disable() }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LocalPanelAccent.current,
                    checkedTrackColor = LocalPanelAccent.current.copy(alpha = 0.3f),
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "PAIRED DEVICES",
            color = PanelTheme.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No paired devices", color = PanelTheme.TextDim, fontSize = 12.sp)
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) {
                        Text("Pair a device", color = LocalPanelAccent.current, fontSize = 11.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(devices, key = { it.device.address }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggleDeviceConnection(row) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconCompat(
                            R.drawable.materialsymbols_ic_bluetooth_rounded_filled,
                            contentDescription = null,
                            tint = if (row.connected) LocalPanelAccent.current else PanelTheme.TextDim,
                            size = 18.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.device.name ?: row.device.address,
                                color = PanelTheme.TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (row.connected) "Connected" else "Not connected",
                                color = if (row.connected) LocalPanelAccent.current else PanelTheme.TextDim,
                                fontSize = 10.sp,
                            )
                        }
                        Text(
                            text = if (row.connected) "Disconnect" else "Connect",
                            color = if (row.connected) PanelTheme.TextDim else LocalPanelAccent.current,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private val connectedRef = android.util.ArrayMap<String, Boolean>()

private fun isDeviceConnected(device: BluetoothDevice): Boolean =
    connectedRef[device.address] ?: runCatching {
        device.javaClass.getMethod("isConnected").invoke(device) as? Boolean
    }.getOrDefault(false) ?: false

@Composable
private fun IconCompat(
    resId: Int,
    contentDescription: String?,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Icon(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}