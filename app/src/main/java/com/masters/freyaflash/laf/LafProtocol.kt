package com.masters.freyaflash.laf

/**
 * Protocolo LAF de LG, portado de los scripts Python verificados en vivo
 * (helo_fix.py, info_kilo_fix.py) contra el LM-K410 real.
 *
 * Verificado:
 * - HELO OK en IF2 el 2026-11-08 y de nuevo el 2026-11-18, desde el A71
 *   como host (Termux root, fd directo a /dev/bus/usb/001/002).
 * - Paquete HELO: 48454c4f01000001000000000000000000000000000000005cb10000b7bab3b0
 * - Respuesta HELO: arg1=1, arg2=0x01000016, CRC 0x55e9.
 *
 * Formato del paquete LAF (32 bytes de header + body):
 * - [0..3]   comando ASCII ("HELO", "INFO", ...)
 * - [4..19]  4 args, uint32 little-endian cada uno
 * - [20..23] body_len, uint32 little-endian
 * - [24..27] CRC16, uint32 little-endian (mitad alta en cero)
 * - [28..31] trailer: comando con los bits invertidos
 * El CRC16 es LSB-first, poly 0x8408, init 0xFFFF, xorout 0xFFFF
 * (CRC-16/X25), calculado sobre el header con el CRC en cero + body.
 * Esa formula esta probada para los paquetes que NOSOTROS mandamos
 * (el HELO que construimos da CRC 0xb15c, verificado en vivo).
 *
 * OJO — el CRC de las RESPUESTAS no sigue esa formula: la respuesta
 * HELO real del LM-K410 trae CRC 0x55e9 y crc16(header con CRC en cero)
 * da 0x0ad9 (comprobado el 2026-11-18 con el Python verificado; se
 * probaron 8 coberturas y variantes y ninguna da 0x55e9). helo_fix.py
 * nunca valido el CRC de las respuestas, solo lo registro. Por eso
 * las respuestas NO se rechazan por CRC: se validan por comando,
 * trailer invertido y los args conocidos (arg1=1, arg2=0x01000016).
 * El CRC de la respuesta solo se registra en el log.
 *
 * Regla de lectura (la del EOVERFLOW 75): pedir siempre 512 bytes por
 * bulk, nunca 32. El telefono manda respuestas en paquetes de 512.
 *
 * Esta clase es Kotlin puro, sin dependencias de Android: la autoprueba
 * [selfTest] corre sin USB y sin telefono.
 */
object LafProtocol {

    const val MAX_PACKET = 512
    const val HEADER_LEN = 32

    const val CMD_HELO = "HELO"

    /** arg1 del HELO verificado en vivo. */
    const val HELO_ARG1 = 0x01000001

    /** Paquete HELO verificado el 2026-11-08 y de nuevo el 2026-11-18. */
    const val HELO_PACKET_HEX =
        "48454c4f01000001000000000000000000000000000000005cb10000b7bab3b0"
    const val HELO_PACKET_CRC = 0xb15c

    /** Respuesta HELO verificada en vivo. */
    const val HELO_RESPONSE_HEX =
        "48454c4f0100000016000001000080002000000000000000e9550000b7bab3b0"
    const val HELO_RESPONSE_CRC = 0x55e9
    const val HELO_RESPONSE_ARG2 = 0x01000016

    /**
     * CRC16 LSB-first (poly 0x8408, init 0xFFFF, xorout 0xFFFF).
     * Traduccion directa del crc16() de helo_fix.py.
     */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xffff
        val end = offset + length
        for (i in offset until end) {
            crc = crc xor (data[i].toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 1) != 0) {
                    ((crc ushr 1) xor 0x8408)
                } else {
                    (crc ushr 1)
                }
            }
        }
        return (crc xor 0xffff) and 0xffff
    }

    /**
     * Arma un paquete LAF: header de 32 bytes + body.
     * Traduccion directa de make_request() de helo_fix.py.
     */
    fun buildRequest(
        cmd: String,
        args: IntArray = intArrayOf(),
        body: ByteArray = byteArrayOf()
    ): ByteArray {
        require(cmd.length == 4) { "cmd debe ser 4 caracteres ASCII" }
        val cmdBytes = cmd.toByteArray(Charsets.US_ASCII)
        val header = ByteArray(HEADER_LEN) // ceros
        cmdBytes.copyInto(header, 0)
        for (i in args.indices) {
            putLe32(header, 4 + 4 * i, args[i])
        }
        putLe32(header, 0x14, body.size)
        // Trailer en 0x1c: comando con los bits invertidos.
        for (i in 0 until 4) {
            header[0x1c + i] = (cmdBytes[i].toInt() xor 0xff).toByte()
        }
        // CRC sobre header (CRC todavia en cero) + body.
        val full = header + body
        val crc = crc16(full)
        putLe32(header, 0x18, crc)
        return header + body
    }

    /** HELO verificado: arg1 = 0x01000001, sin body. */
    fun buildHeloRequest(): ByteArray = buildRequest(CMD_HELO, intArrayOf(HELO_ARG1))

    data class LafHeader(
        val cmd: String,
        val args: IntArray,
        val bodyLen: Int,
        val crc: Int
    )

    /** Parsea los primeros 32 bytes de un paquete como header LAF. */
    fun parseHeader(packet: ByteArray): LafHeader {
        require(packet.size >= HEADER_LEN) { "paquete menor a 32 bytes" }
        val cmd = packet.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        val args = IntArray(4) { getLe32(packet, 4 + 4 * it) }
        val bodyLen = getLe32(packet, 0x14)
        val crc = getLe32(packet, 0x18)
        return LafHeader(cmd, args, bodyLen, crc)
    }

    /**
     * Valida CRC (header con CRC en cero + body) y trailer invertido.
     * [headerAndBody] es el paquete completo (header + body, sin relleno).
     * PROBADO solo para los paquetes que nosotros construimos (requests):
     * el HELO que armamos da CRC 0xb15c. NO usar para rechazar respuestas
     * del telefono: su CRC no sigue esta formula (ver nota arriba).
     */
    fun validatePacket(headerAndBody: ByteArray): Boolean {
        if (headerAndBody.size < HEADER_LEN) return false
        val storedCrc = getLe32(headerAndBody, 0x18)
        val zeroed = headerAndBody.copyOf()
        for (i in 0x18 until 0x1c) zeroed[i] = 0
        if (crc16(zeroed) != storedCrc) return false
        return validateTrailer(headerAndBody)
    }

    /** Trailer: bytes 28-31 son el comando con los bits invertidos. */
    fun validateTrailer(packet: ByteArray): Boolean {
        if (packet.size < HEADER_LEN) return false
        for (i in 0 until 4) {
            if (packet[0x1c + i] != (packet[i].toInt() xor 0xff).toByte()) {
                return false
            }
        }
        return true
    }

    /**
     * Chequeo de una respuesta HELO real: comando, trailer y los args
     * conocidos del LM-K410 (arg1=1, arg2=0x01000016, sin body).
     * El CRC de la respuesta solo se registra, no se exige.
     */
    fun isKnownHeloResponse(header: LafHeader): Boolean {
        return header.cmd == CMD_HELO &&
            header.args[0] == 1 &&
            header.args[1] == HELO_RESPONSE_ARG2 &&
            header.bodyLen == 0
    }

    fun putLe32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xff).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xff).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xff).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    fun getLe32(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xff) or
            (((buf[offset + 1].toInt() and 0xff) shl 8)) or
            (((buf[offset + 2].toInt() and 0xff) shl 16)) or
            (((buf[offset + 3].toInt() and 0xff) shl 24))
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 0x10) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex de largo impar" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /**
     * Autoprueba sin USB: verifica los vectores reales del LM-K410.
     * Igual que `helo_fix.py --self-test` en Python.
     * Devuelve la lista de (nombre, paso) para loguearla.
     */
    fun selfTest(): List<Pair<String, Boolean>> {
        val checks = mutableListOf<Pair<String, Boolean>>()
        val helo = buildHeloRequest()
        checks += "HELO packet hex == vector 2026-11-08" to
            (bytesToHex(helo) == HELO_PACKET_HEX)
        checks += "HELO packet CRC 0xb15c" to
            (getLe32(helo, 0x18) == HELO_PACKET_CRC)
        val resp = hexToBytes(HELO_RESPONSE_HEX)
        checks += "Respuesta HELO: trailer valido" to validateTrailer(resp)
        checks += "Respuesta HELO: CRC registrado 0x55e9" to
            (getLe32(resp, 0x18) == HELO_RESPONSE_CRC)
        val h = parseHeader(resp)
        checks += "Respuesta HELO: cmd HELO" to (h.cmd == CMD_HELO)
        checks += "Respuesta HELO: arg1 == 1" to (h.args[0] == 1)
        checks += "Respuesta HELO: arg2 == 0x01000016" to (h.args[1] == HELO_RESPONSE_ARG2)
        checks += "Respuesta HELO: CRC 0x55e9" to (h.crc == HELO_RESPONSE_CRC)
        checks += "Respuesta HELO: body_len == 0" to (h.bodyLen == 0)
        checks += "Respuesta HELO: args conocidos" to isKnownHeloResponse(h)
        return checks
    }
}
