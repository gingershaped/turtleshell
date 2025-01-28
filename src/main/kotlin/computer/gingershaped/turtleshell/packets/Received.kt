@file:OptIn(ExperimentalUnsignedTypes::class)
package community.rto.ginger.turtleshell.packets

import community.rto.ginger.turtleshell.util.CHARSET
import community.rto.ginger.turtleshell.util.toUByteArray
import community.rto.ginger.turtleshell.util.ColoredString
import community.rto.ginger.turtleshell.util.hexformat
import community.rto.ginger.turtleshell.packets.getUByte
import java.nio.ByteBuffer
import java.io.IOException
import com.github.ajalt.colormath.Color
import com.github.ajalt.colormath.model.RGB

sealed interface SessionPacket {
    val sessionId: UInt
}

internal fun ByteBuffer.getUByte() = get().toUByte()
internal fun ByteBuffer.getUShort() = getShort().toUShort()
internal fun ByteBuffer.getUInt() = getInt().toUInt()

sealed class ReceivedPacket {
    data class Blit(override val sessionId: UInt, val x: UShort, val y: UShort, val text: ColoredString) : ReceivedPacket(), SessionPacket
    data class Scroll(override val sessionId: UInt, val distance: Int) : ReceivedPacket(), SessionPacket
    data class SetPaletteColor(override val sessionId: UInt, val index: Int, val color: Color) : ReceivedPacket(), SessionPacket
    data class SetCursorVisible(override val sessionId: UInt, val visible: Boolean) : ReceivedPacket(), SessionPacket
    data class SetCursorPosition(override val sessionId: UInt, val x: UShort, val y: UShort) : ReceivedPacket(), SessionPacket
    data class FillLine(override val sessionId: UInt, val line: UShort, val color: Int) : ReceivedPacket(), SessionPacket
    data class FillScreen(override val sessionId: UInt, val color: Int) : ReceivedPacket(), SessionPacket
    data class Flush(override val sessionId: UInt) : ReceivedPacket(), SessionPacket
    data class EndSession(override val sessionId: UInt) : ReceivedPacket(), SessionPacket

    data class SetSecret(val secret: String) : ReceivedPacket()

    companion object {
        @OptIn(kotlin.ExperimentalStdlibApi::class)
        fun decode(buf: ByteBuffer): ReceivedPacket = runCatching {
            when(val variant = buf.getUByte().toUInt()) {
                0x00u -> Blit(buf.getUInt(), buf.getUShort(), buf.getUShort(), ColoredString.fromBuffer(buf))
                0x01u -> Scroll(buf.getUInt(), buf.getUShort().toInt() * if (buf.getUByte() > 0u) -1 else 1)
                0x02u -> {
                    val sessionId = buf.getUInt()
                    val (index, r, g, b) = buf.getUInt().toUByteArray().map { it.toInt() }
                    SetPaletteColor(sessionId, index, RGB.from255(r, g, b))
                }
                0x03u -> SetCursorVisible(buf.getUInt(), buf.get().toUByte() > 0x00u)
                0x04u -> SetCursorPosition(buf.getUInt(), buf.getUShort(), buf.getUShort())
                0x05u -> FillLine(buf.getUInt(), buf.getUShort(), buf.getUByte().toInt())
                0x06u -> FillScreen(buf.getUInt(), buf.getUByte().toInt())
                0x1fu -> Flush(buf.getUInt())
                0x20u -> EndSession(buf.getUInt())
                0x21u -> SetSecret(CHARSET.decode(buf).toString())
                else -> error("Packet variant byte is invalid! (${variant.toHexString()})")
            }.also {
                check(!buf.hasRemaining()) {
                    "Packet is too long! (${buf.remaining()} bytes unread)"
                }
            }
        }.getOrElse { error ->
            buf.rewind()
            val arr = ByteArray(buf.capacity())
            buf.get(arr)
            throw IOException("Packet decoding failed! Packet contents were as follows: ${arr.toHexString(hexformat)}", error)
        }
    }
}