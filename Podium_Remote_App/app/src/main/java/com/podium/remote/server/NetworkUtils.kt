package com.podium.remote.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Finds the local IP address for the active network interface.
     * Prioritizes hotspot interfaces (ap0, softap, wlan1, etc.) then Wi-Fi.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // 1st Priority: Look for Hotspot / AP interfaces (ap0, softap, rndis)
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.contains("ap") || name.contains("tether") || name.contains("rndis")) {
                    val ip = getIpv4FromInterface(intf)
                    if (ip != null) return ip
                }
            }

            // 2nd Priority: Look for wlan interfaces
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.contains("wlan")) {
                    val ip = getIpv4FromInterface(intf)
                    if (ip != null) return ip
                }
            }

            // 3rd Priority: Any active non-loopback IPv4 interface
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback) {
                    val ip = getIpv4FromInterface(intf)
                    if (ip != null) return ip
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "192.168.43.1" // Fallback
    }

    private fun getIpv4FromInterface(intf: NetworkInterface): String? {
        try {
            if (!intf.isUp || intf.isLoopback) return null
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress ?: continue
                    if (!host.startsWith("127.")) {
                        return host
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Generates a high-contrast QR code Bitmap for instant camera scanning.
     */
    fun generateQrBitmap(content: String, size: Int = 320): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Returns free internal storage space in GB.
     */
    fun getFreeStorageGb(): Double {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            val gb = bytesAvailable.toDouble() / (1024 * 1024 * 1024)
            Math.round(gb * 10.0) / 10.0
        } catch (e: Exception) {
            -1.0
        }
    }

    /**
     * Returns current battery percentage (0-100).
     */
    fun getBatteryLevel(context: Context): Int {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                (level * 100 / scale)
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }
}
