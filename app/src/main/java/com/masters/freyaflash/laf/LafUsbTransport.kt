package com.masters.freyaflash.laf

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Transporte USB para LAF usando android.hardware.usb.UsbManager.
 *
 * El LM-K410 en Download se enumera como VID 0x1004 PID 0x6000, con la
 * interfaz LAF en IF2 (255/255/255), bulk OUT 0x02 / IN 0x83. Verificado
 * en vivo el 2026-11-18: la Pad lo detecta por UsbManager (app USB Info)
 * y el A71 lo ve por lsusb como 1004:6000.
 *
 * OJO: en la Pad el Termux no puede hablar USB crudo (libusb -1), por eso
 * este transporte usa UsbManager y no libusb ni termux-usb. Es el camino
 * que hace posible flashear desde Android.
 *
 * Solo lectura en esta version: HELO. No hay WRTE/ERSE todavia, y no se
 * escribe nada en el telefono sin respaldo.
 *
 * El intercambio HELO no debe correr en el hilo principal: bulkTransfer
 * bloquea hasta el timeout (5 s).
 */
class LafUsbTransport(private val context: Context) {

    companion object {
        const val VID_LG = 0x1004
        const val PID_LAF_DOWNLOAD = 0x6000
        const val LAF_INTERFACE_NUMBER = 2
        const val EP_OUT_ADDRESS = 0x02
        const val EP_IN_ADDRESS = 0x83
        const val TIMEOUT_MS = 5000
        const val DRAIN_TIMEOUT_MS = 500
        const val ACTION_USB_PERMISSION = "com.masters.freyaflash.USB_PERMISSION"
    }

    sealed class HeloResult {
        data class Ok(
            val arg1: Int,
            val arg2: Int,
            val arg3: Int,
            val arg4: Int,
            val crc: Int
        ) : HeloResult()

        data class Fail(val reason: String) : HeloResult()
    }

    fun usbManager(): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Busca el LM-K410 en Download (1004:6000) entre los USB conectados. */
    fun findLafDevice(): UsbDevice? {
        return usbManager().deviceList.values.firstOrNull {
            it.vendorId == VID_LG && it.productId == PID_LAF_DOWNLOAD
        }
    }

    fun hasPermission(device: UsbDevice): Boolean =
        usbManager().hasPermission(device)

    /**
     * Pide permiso USB. La respuesta llega como broadcast con
     * [ACTION_USB_PERMISSION] (ver MainActivity).
     * En Android 12+ el PendingIntent tiene que ser mutable: el sistema
     * le agrega el dispositivo y el resultado.
     */
    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager().requestPermission(device, intent)
    }

    /** Abre el dispositivo y reclama la interfaz LAF (IF2). Null si falla. */
    fun open(device: UsbDevice): LafConnection? {
        val connection = usbManager().openDevice(device) ?: return null
        val iface = findLafInterface(device) ?: run { connection.close(); return null }
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            return null
        }
        val epOut = findBulkEndpoint(iface, UsbConstants.USB_DIR_OUT, EP_OUT_ADDRESS)
            ?: run { connection.close(); return null }
        val epIn = findBulkEndpoint(iface, UsbConstants.USB_DIR_IN, EP_IN_ADDRESS)
            ?: run { connection.close(); return null }
        return LafConnection(connection, iface, epOut, epIn)
    }

    private fun findLafInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == LAF_INTERFACE_NUMBER) return iface
        }
        return null
    }

    private fun findBulkEndpoint(
        iface: UsbInterface,
        direction: Int,
        address: Int
    ): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == direction && ep.address == address
            ) {
                return ep
            }
        }
        return null
    }

    /**
     * Sesion LAF abierta. Usar con `use { }` para que se cierre sola.
     * Implementa Closeable (no AutoCloseable) para que `use` compile.
     */
    class LafConnection(
        private val connection: UsbDeviceConnection,
        private val iface: UsbInterface,
        private val epOut: UsbEndpoint,
        private val epIn: UsbEndpoint
    ) : java.io.Closeable {

        /**
         * Drena datos viejos del endpoint IN antes de mandar HELO.
         * Timeout corto; que no haya nada es lo normal. Devuelve los
         * bytes drenados.
         */
        private fun drain(): Int {
            val buf = ByteArray(LafProtocol.MAX_PACKET)
            var drained = 0
            repeat(3) {
                val n = connection.bulkTransfer(epIn, buf, buf.size, DRAIN_TIMEOUT_MS)
                if (n > 0) {
                    drained += n
                } else {
                    return drained
                }
            }
            return drained
        }

        /**
         * Manda HELO y espera la respuesta. Solo HELO, sin escritura
         * al telefono. No llamar desde el hilo principal.
         */
        fun exchangeHelo(onLog: (String) -> Unit = {}): HeloResult {
            val drained = drain()
            onLog("DRAIN: $drained bytes viejos en 0x83")
            val helo = LafProtocol.buildHeloRequest()
            onLog("HELO packet: ${LafProtocol.bytesToHex(helo)}")
            val written = connection.bulkTransfer(epOut, helo, helo.size, TIMEOUT_MS)
            if (written != helo.size) {
                return HeloResult.Fail(
                    "WRITE fallo en 0x02: bulkTransfer devolvio $written de ${helo.size}"
                )
            }
            onLog("WRITE OK: $written bytes enviados a 0x02")
            val buf = ByteArray(LafProtocol.MAX_PACKET)
            val n = connection.bulkTransfer(epIn, buf, buf.size, TIMEOUT_MS)
            if (n < LafProtocol.HEADER_LEN) {
                return HeloResult.Fail(
                    "READ fallo en 0x83: bulkTransfer devolvio $n (timeout o error)"
                )
            }
            val header = try {
                LafProtocol.parseHeader(buf)
            } catch (e: IllegalArgumentException) {
                return HeloResult.Fail("READ: header invalido (${e.message})")
            }
            if (header.cmd != LafProtocol.CMD_HELO) {
                return HeloResult.Fail(
                    "READ: cmd inesperado '${header.cmd}', se esperaba HELO"
                )
            }
            val headerBytes = buf.copyOfRange(0, LafProtocol.HEADER_LEN)
            if (!LafProtocol.validateTrailer(headerBytes)) {
                return HeloResult.Fail("READ: trailer invalido en la respuesta HELO")
            }
            if (!LafProtocol.isKnownHeloResponse(header)) {
                return HeloResult.Fail(
                    "READ: respuesta HELO con args desconocidos " +
                        "(arg1=${header.args[0]} arg2=${header.args[1]})"
                )
            }
            // El CRC de la respuesta solo se registra: el telefono no lo
            // calcula con la formula de los requests (ver LafProtocol).
            val crcOk = LafProtocol.validatePacket(headerBytes)
            onLog(
                "CRC respuesta: 0x${header.crc.toString(16)} " +
                    if (crcOk) "(valido)" else "(no sigue la formula del request, solo se registra)"
            )
            return HeloResult.Ok(
                header.args[0], header.args[1], header.args[2], header.args[3], header.crc
            )
        }

        override fun close() {
            try {
                connection.releaseInterface(iface)
            } catch (_: Exception) {
            }
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
    }
}
