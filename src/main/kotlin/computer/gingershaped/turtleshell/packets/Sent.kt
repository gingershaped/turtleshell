@file:OptIn(ExperimentalUnsignedTypes::class)
package computer.gingershaped.turtleshell.packets

import computer.gingershaped.turtleshell.terminal.Input
import computer.gingershaped.turtleshell.util.toUByteArray
import computer.gingershaped.turtleshell.util.ubyteFromBits
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeUByte
import kotlinx.io.writeUInt
import kotlinx.io.writeUShort
import kotlinx.io.writeString

@OptIn(ExperimentalUnsignedTypes::class)
sealed class SentPacket(private val variant: UByte) {
    protected abstract fun Buffer.serializeBody()
    fun serialize() = Buffer().apply {
        writeUByte(variant)
        serializeBody()
    }.readByteArray()

    data class Hello(val major: UByte, val minor: UByte, val features: UByte) : SentPacket(0x00u) {
        override fun Buffer.serializeBody() {
            writeUByte(major)
            writeUByte(minor)
            writeUByte(features)
        }
    }

    data class Message(val message: String, val type: Type) : SentPacket(0x01u) {
        override fun Buffer.serializeBody() {
            writeUByte(type.code)
            writeInt(message.length)
            writeString(message)
        }
        enum class Type(val code: UByte) {
            INFO(0x00u), WARNING(0x01u), ERROR(0x02u), AUTH(0x03u)
        }
    }

    data class StartShellSession(val sessionId: UInt, val features: UByte, val width: UShort, val height: UShort) : SentPacket(0x02u) {
        override fun Buffer.serializeBody() {
            writeUInt(sessionId)
            writeUByte(features)
            writeUShort(width)
            writeUShort(height)
        }
    }

    data class EndShellSession(val sessionId: UInt) : SentPacket(0x03u) {
        override fun Buffer.serializeBody() {
            writeUInt(sessionId)
        }
    }

    data class InterruptShellSession(val sessionId: UInt) : SentPacket(0x04u) {
        override fun Buffer.serializeBody() {
            writeUInt(sessionId)
        }
    }

    data class KeyInput(val sessionId: UInt, val keycode: UShort, val pressed: Boolean) : SentPacket(0x05u) {
        override fun Buffer.serializeBody() {
            writeUInt(sessionId)
            writeUShort(keycode)
            writeUByte(if (pressed) 0x01u else 0x00u)
        }
    }

    data class KeycodeInput(val sessionId: UInt, val keycodes: List<UShort>) : SentPacket(0x06u) {
        override fun Buffer.serializeBody() {
            writeUInt(sessionId)
            keycodes.forEach(::writeUShort)
        }
    }

    data class CharInput(val sessionId: UInt, val key: String) : SentPacket(0x07u) {
        override fun Buffer.serializeBody() {
            writeUInt(sessionId)
            writeString(key)
        }
    }

    data class Resize(val sessionId: UInt, val width: UShort, val height: UShort) : SentPacket(0x08u) {
        override fun Buffer.serializeBody() {
            writeUInt(sessionId)
            writeUShort(width)
            writeUShort(height)
        }
    }
    
    data class MouseInput(val sessionId: UInt, val input: Input.Mouse) : SentPacket(0x09u) {
        override fun Buffer.serializeBody() {
            with(input) {
                writeUInt(sessionId)
                writeUByte(button.number)
                writeUInt(x.toUInt())
                writeUInt(y.toUInt())
                writeUByte(ubyteFromBits(modifiers.shift, modifiers.meta, modifiers.control, dragged, down))
            }
        }
    }
}