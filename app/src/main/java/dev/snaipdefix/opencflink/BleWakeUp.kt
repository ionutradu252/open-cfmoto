package dev.snaipdefix.opencflink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Drives the BLE wake-up handshake that unlocks the bike's PXC server.
 *
 * Sequence (all wrapped in CmdBaseHead-style frames + tiny protobuf, see [BleProtocol]):
 *   1. Scan: by MAC if known (like the official app), else match advertised UUID / name
 *   2. GATT connect → discoverServices → requestMtu(185)
 *   3. Subscribe to NOTIFY characteristic (write 0x0100 to CCCD)
 *   4. Write encode(0x5A, encryptValue) to WRITE characteristic
 *   5. Receive encode(0x5B, "<32-hex challenge>") on NOTIFY
 *      → AES-256-ECB-decrypt → 8-digit ASCII plaintext
 *   6. Write encode(0x5C, plaintext_8_digits) to WRITE characteristic
 *   7. Receive encode(0x5D, [08 00]) → success → invoke onUnlocked()
 */
@SuppressLint("MissingPermission")
class BleWakeUp(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onUnlocked: () -> Unit,
    private val onFailed: (String) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner = btManager.adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var done = false
    private var notifyAcc = ByteArray(0)

    fun start() {
        if (scanner == null) { fail("BluetoothLeScanner unavailable"); return }

        // the official app scans by MAC (it knows it from pairing). If we have the MAC,
        // do the same, most robust. Otherwise scan unfiltered and match by advertised
        // service UUID or name, logging every candidate so the MAC can be recovered.
        val mac = BleSecrets.BIKE_BLE_MAC
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        if (mac != null) {
            log("[BLE] scanning for MAC $mac …")
            val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
            scanner.startScan(listOf(filter), settings, scanCallback)
        } else {
            log("[BLE] no MAC set — scanning ALL devices, matching by service UUID / name hint")
            log("[BLE] hints=${BleSecrets.NAME_HINTS}  serviceUuid=${BleProtocol.SERVICE_UUID}")
            scanner.startScan(null, settings, scanCallback)
        }
        handler.postDelayed({
            if (gatt == null && !done) {
                try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
                fail("BLE scan timeout (20s) — no matching device found")
            }
        }, 20_000)
    }

    fun stop() {
        done = true
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun fail(reason: String) {
        if (done) return
        done = true
        log("[BLE] FAILED: $reason")
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        handler.post { onFailed(reason) }
    }

    private fun success() {
        if (done) return
        done = true
        log("[BLE] *** wake-up complete — bike PXC server unlocked ***")
        handler.post { onUnlocked() }
        // keep gatt open so the bike's session stays alive while TCP/MotoPlay runs
    }

    private val seen = HashSet<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val advUuids = result.scanRecord?.serviceUuids
            val advName = result.scanRecord?.deviceName ?: dev.name
            if (seen.add(dev.address)) {
                log("[BLE] candidate ${dev.address} name=$advName rssi=${result.rssi} adv=$advUuids")
            }

            val matches = when {
                BleSecrets.BIKE_BLE_MAC != null ->
                    dev.address.equals(BleSecrets.BIKE_BLE_MAC, ignoreCase = true)
                advUuids?.any { it.uuid == BleProtocol.SERVICE_UUID } == true -> true
                advName != null && BleSecrets.NAME_HINTS.any {
                    advName.contains(it, ignoreCase = true)
                } -> true
                else -> false
            }
            if (!matches) return

            log("[BLE] >>> MATCH ${dev.address} name=$advName — connecting")
            try { scanner?.stopScan(this) } catch (_: Exception) {}
            connectTo(dev)
        }
        override fun onScanFailed(errorCode: Int) {
            fail("scan failed code=$errorCode")
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        log("[BLE] connecting to ${device.address} …")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("[BLE] connState status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("connectGatt status=$status"); return }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("[BLE] connected; discovering services")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!done) fail("disconnected mid-handshake")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("svc discovery status=$status"); return }
            log("[BLE] services discovered; requesting MTU 185 (matches official app)")
            if (!g.requestMtu(185)) fail("requestMtu failed")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("[BLE] MTU = $mtu (status=$status)")
            val svc = g.getService(BleProtocol.SERVICE_UUID)
            if (svc == null) { fail("service not present"); return }
            val notifyChar = svc.getCharacteristic(BleProtocol.NOTIFY_CHAR_UUID)
            if (notifyChar == null) { fail("notify char not present"); return }
            if (!g.setCharacteristicNotification(notifyChar, true)) {
                fail("setCharacteristicNotification(true) returned false"); return
            }
            val cccd = notifyChar.getDescriptor(BleProtocol.CCCD_UUID)
            if (cccd == null) { fail("CCCD descriptor not present"); return }
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
            if (!ok) fail("writeDescriptor (enable notify) failed")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("CCCD write status=$status"); return }
            if (d.uuid != BleProtocol.CCCD_UUID) return
            log("[BLE] notifications enabled; sending AUTH_PKG (cmd 0x5A)")
            sendAuthPkg(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            log("[BLE] write status=$status cmd=${BleProtocol.bytesToHex(c.value).take(6)}…")
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("characteristic write status=$status"); return }
        }

        // Notifications: pre-API33 hook
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic,
        ) {
            super.onCharacteristicChanged(g, c)
            handleNotification(g, c.value)
        }

        // Notifications: API33+ hook
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleNotification(g, value)
        }
    }

    private fun sendAuthPkg(g: BluetoothGatt) {
        val frame = BleProtocol.encodeFrame(BleProtocol.CMD_AUTH_PKG, BleSecrets.encryptValueBytes)
        log("[BLE] -> ${BleProtocol.bytesToHex(frame)}")
        writeFrame(g, frame) { ok ->
            if (!ok) fail("auth pkg write submit failed")
        }
    }

    private fun handleNotification(g: BluetoothGatt, data: ByteArray) {
        // BLE may chunk notifications; accumulate until a complete frame appears.
        notifyAcc += data
        log("[BLE] <- chunk(${data.size}) acc=${BleProtocol.bytesToHex(notifyAcc)}")

        while (notifyAcc.size >= 7) {
            val parsed = BleProtocol.decodeFrame(notifyAcc) ?: return
            val (cmd, payload) = parsed
            // Frame total = 2 magic + 1 cmd + 2 len + payload + 1 crc + 1 end = 7 + payloadLen
            val payloadLen = (notifyAcc[3].toInt() and 0xFF) or ((notifyAcc[4].toInt() and 0xFF) shl 8)
            val totalLen = 7 + payloadLen
            notifyAcc = notifyAcc.copyOfRange(totalLen, notifyAcc.size)
            onFrame(g, cmd, payload)
        }
    }

    private fun onFrame(g: BluetoothGatt, cmd: Byte, payload: ByteArray) {
        log("[BLE] <- cmd=0x${"%02x".format(cmd.toInt() and 0xFF)} payload(${payload.size})=${BleProtocol.bytesToHex(payload)}")
        when (cmd) {
            BleProtocol.CMD_CHALLENGE -> handleChallenge(g, payload)
            BleProtocol.CMD_ACK       -> {
                log("[BLE] cmd 0x5D ACK received — handshake done")
                success()
            }
            else -> log("[BLE] (ignoring unexpected cmd)")
        }
    }

    private fun handleChallenge(g: BluetoothGatt, protoPayload: ByteArray) {
        val challengeAscii = BleProtocol.unwrapProtoBytes1(protoPayload)
            ?: run { fail("0x5B: bad protobuf framing"); return }
        // the bike sends the 16-byte ciphertext as 32 hex ASCII chars.
        val asciiStr = String(challengeAscii, Charsets.US_ASCII)
        log("[BLE]   challenge ascii=$asciiStr (len=${challengeAscii.size})")
        val challengeBytes = try { BleProtocol.hexToBytes(asciiStr) } catch (e: Exception) {
            fail("0x5B: hex decode failed: $e"); return
        }
        if (challengeBytes.size != 16) {
            fail("0x5B: expected 16-byte challenge, got ${challengeBytes.size}"); return
        }
        val plain = try { BleProtocol.aesEcbDecrypt(challengeBytes, BleSecrets.keyBytes) } catch (e: Exception) {
            fail("0x5B: AES decrypt failed: $e"); return
        }
        val plainStr = String(plain, Charsets.US_ASCII)
        log("[BLE]   decrypted plaintext='$plainStr' (${plain.size} bytes)")
        // Reply with the same string in cmd 0x5C
        val reply = BleProtocol.encodeFrame(BleProtocol.CMD_RESPONSE, plain)
        log("[BLE] -> ${BleProtocol.bytesToHex(reply)}")
        writeFrame(g, reply) { ok ->
            if (!ok) fail("response write submit failed")
        }
    }

    private fun writeFrame(g: BluetoothGatt, frame: ByteArray, after: (Boolean) -> Unit) {
        val svc = g.getService(BleProtocol.SERVICE_UUID) ?: run { after(false); return }
        val w = svc.getCharacteristic(BleProtocol.WRITE_CHAR_UUID) ?: run { after(false); return }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(w, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                w.value = frame
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(w)
            }
        }
        after(ok)
    }
}
