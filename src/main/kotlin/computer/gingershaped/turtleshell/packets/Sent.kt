@file:OptIn(ExperimentalUnsignedTypes::class)
package computer.gingershaped.turtleshell.packets

import computer.gingershaped.turtleshell.terminal.Input
import computer.gingershaped.turtleshell.util.toUByteArray
import computer.gingershaped.turtleshell.util.ubyteFromBits
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeUByte
import kotlinx.io.writeUShort
import kotlinx.io.writeUInt
import kotlinx.io.writeULong
import kotlinx.io.writeString
import kotlinx.io.transferFrom
import java.nio.ByteBuffer

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

    data class FileRequest(val requestId: UInt, val request: Request) : SentPacket(0x21u) {
        override fun Buffer.serializeBody() {
            writeUInt(requestId)
            with(request) { serialize() }
        }

        sealed class Request(val variant: UByte) {
            protected abstract fun Buffer.serialize()
        }

        data class OpenFile(val path: String): Request(0x00u) {
            override fun Buffer.serialize() {
                writeString(path)
            }
        }

        data class CloseFile(val handle: UInt) : Request(0x01u) {
            override fun Buffer.serialize() {
                writeUInt(handle)
            }
        }

        data class ReadFile(val handle: UInt, val position: ULong) : Request(0x02u) {
            override fun Buffer.serialize() {
                writeUInt(handle)
                writeULong(position)
            }
        }

        data class WriteFile(val handle: UInt, val position: ULong, val payload: ByteBuffer) : Request(0x03u) {
            override fun Buffer.serialize() {
                writeUInt(handle)
                writeULong(position)
                transferFrom(payload)
            }
        }

        data class ListDirectory(val path: String) : Request(0x04u) {
            override fun Buffer.serialize() {
                writeString(path)
            }
        }

        data class CreateDirectory(val path: String) : Request(0x05u) {
            override fun Buffer.serialize() {
                writeString(path)
            }
        }

        data class Delete(val path: String) : Request(0x06u) {
            override fun Buffer.serialize() {
                writeString(path)
            }
        }

        data class Copy(val source: String, val dest: String) : Request(0x07u) {
            override fun Buffer.serialize() {
                writeString(source)
                writeUByte(0x00u)
                writeString(dest)
                writeUByte(0x00u)
            }
        }

        data class Move(val source: String, val dest: String) : Request(0x08u) {
            override fun Buffer.serialize() {
                writeString(source)
                writeUByte(0x00u)
                writeString(dest)
                writeUByte(0x00u)
            }
        }

        data class MountInformation(val path: String) : Request(0x09u) {
            override fun Buffer.serialize() {
                writeString(path)
            }
        }

        data class FileInformation(val path: String) : Request(0x0au) {
            override fun Buffer.serialize() {
                writeString(path)
            }
        }
    }
}