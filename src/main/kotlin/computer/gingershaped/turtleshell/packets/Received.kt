@file:OptIn(ExperimentalUnsignedTypes::class)
package computer.gingershaped.turtleshell.packets

import com.github.ajalt.colormath.Color
import com.github.ajalt.colormath.model.RGB
import computer.gingershaped.turtleshell.util.CHARSET
import computer.gingershaped.turtleshell.util.ColoredString
import computer.gingershaped.turtleshell.util.hexformat
import computer.gingershaped.turtleshell.util.toUByteArray
import computer.gingershaped.turtleshell.packets.getUInt
import computer.gingershaped.turtleshell.packets.getUByte
import java.io.IOException
import java.nio.ByteBuffer

sealed interface SessionPacket {
    val sessionId: UInt
}

internal fun ByteBuffer.getUByte() = get().toUByte()
internal fun ByteBuffer.getUShort() = getShort().toUShort()
internal fun ByteBuffer.getUInt() = getInt().toUInt()

sealed class ReceivedPacket {
    sealed class DrawCommand {
        data class Blit(val x: UShort, val y: UShort, val text: ColoredString) : DrawCommand()
        data class Scroll(val distance: Int) : DrawCommand()
        data class SetCursorVisible(val visible: Boolean) : DrawCommand()
        data class SetCursorPosition(val x: UShort, val y: UShort) : DrawCommand()
        data class FillLine(val line: UShort, val color: Int) : DrawCommand()
        data class FillScreen(val color: Int) : DrawCommand()
    }
    data class Draw(override val sessionId: UInt, val commands: List<DrawCommand>) : ReceivedPacket(), SessionPacket
    data class SetPaletteColor(override val sessionId: UInt, val index: Int, val color: Color) : ReceivedPacket(), SessionPacket
    data class EndSession(override val sessionId: UInt) : ReceivedPacket(), SessionPacket

    companion object {
        @OptIn(ExperimentalStdlibApi::class)
        fun decode(buf: ByteBuffer): ReceivedPacket = runCatching {
            when(val variant = buf.getUByte().toUInt()) {
                0x00u -> {
                    val sessionId = buf.getUInt()
                    val commands = mutableListOf<DrawCommand>()
                    while (buf.remaining() > 0) {
                        commands += when(val command = buf.getUByte().toUInt()) {
                            0x00u -> DrawCommand.Blit(buf.getUShort(), buf.getUShort(), ColoredString.fromBuffer(buf))
                            0x01u -> DrawCommand.Scroll(buf.getUShort().toInt() * if (buf.getUByte() > 0u) -1 else 1)
                            0x03u -> DrawCommand.SetCursorVisible(buf.get().toUByte() > 0x00u)
                            0x04u -> DrawCommand.SetCursorPosition(buf.getUShort(), buf.getUShort())
                            0x05u -> DrawCommand.FillLine(buf.getUShort(), buf.getUByte().toInt())
                            0x06u -> DrawCommand.FillScreen(buf.getUByte().toInt())
                            else -> error("Draw command variant byte is invalid! (${command.toHexString()})")
                        }
                    }
                    Draw(sessionId, commands)
                }
                0x01u -> {
                    val sessionId = buf.getUInt()
                    val (index, r, g, b) = buf.getUInt().toUByteArray().map { it.toInt() }
                    SetPaletteColor(sessionId, index, RGB.from255(r, g, b))
                }
                0x20u -> EndSession(buf.getUInt())
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