package com.masters.freyaflash.laf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectores reales del LM-K410, verificados en vivo el 2026-11-08
 * y de nuevo el 2026-11-18 (HELO OK en IF2 desde el A71).
 */
class LafProtocolTest {

    @Test
    fun heloPacketMatchesVerifiedVector() {
        val helo = LafProtocol.buildHeloRequest()
        assertEquals(LafProtocol.HELO_PACKET_HEX, LafProtocol.bytesToHex(helo))
        assertEquals(LafProtocol.HELO_PACKET_CRC, LafProtocol.getLe32(helo, 0x18))
    }

    @Test
    fun heloResponseParsesToVerifiedValues() {
        val resp = LafProtocol.hexToBytes(LafProtocol.HELO_RESPONSE_HEX)
        // La respuesta real trae CRC 0x55e9, que NO valida con la formula
        // de los requests (comprobado con el Python verificado); por eso
        // no se exige validatePacket(resp). Se valida trailer + args.
        assertTrue(LafProtocol.validateTrailer(resp))
        val h = LafProtocol.parseHeader(resp)
        assertEquals("HELO", h.cmd)
        assertEquals(1, h.args[0])
        assertEquals(LafProtocol.HELO_RESPONSE_ARG2, h.args[1])
        assertEquals(LafProtocol.HELO_RESPONSE_CRC, h.crc)
        assertEquals(0, h.bodyLen)
        assertTrue(LafProtocol.isKnownHeloResponse(h))
    }

    @Test
    fun heloRequestPacketValidates() {
        // La formula CRC si esta probada para los paquetes que armamos.
        val helo = LafProtocol.buildHeloRequest()
        assertTrue(LafProtocol.validatePacket(helo))
    }

    @Test
    fun selfTestAllPass() {
        val checks = LafProtocol.selfTest()
        assertTrue(checks.all { it.second })
    }
}
