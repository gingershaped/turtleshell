@file:OptIn(ExperimentalUnsignedTypes::class)
package computer.gingershaped.turtleshell.packets

import computer.gingershaped.turtleshell.terminal.Input
import computer.gingershaped.turtleshell.util.toUByteArray
import computer.gingershaped.turtleshell.util.ubyteFromBits

@OptIn(kotlin.ExperimentalUnsignedTypes::class)
sealed class SentPacket(val variant: UByte) {
    protected abstract fun serializeBody(): UByteArray
    fun serialize() = ubyteArrayOf(variant, *serializeBody())

    data class Hello(val major: UByte, val minor: UByte, val features: UByte) : SentPacket(0x00u) {
        override fun serializeBody() = ubyteArrayOf(major, minor, features)
    }
    data class Message(val message: String, val type: Type) : SentPacket(0x01u) {
        override fun serializeBody() = ubyteArrayOf(type.code, *message.toByteArray(Charsets.ISO_8859_1).toUByteArray())
        enum class Type(val code: UByte) {
            INFO(0x00u), WARNING(0x01u), ERROR(0x02u), AUTH(0x03u)
        }
    }
    data class StartShellSession(val sessionId: UInt, val features: UByte, val width: UShort, val height: UShort) : SentPacket(0x02u) {
        override fun serializeBody() = ubyteArrayOf(*sessionId.toUByteArray(), features, *width.toUByteArray(), *height.toUByteArray())
    }
    data class EndShellSession(val sessionId: UInt) : SentPacket(0x03u) {
        override fun serializeBody() = sessionId.toUByteArray()
    }
    data class InterruptShellSession(val sessionId: UInt) : SentPacket(0x04u) {
        override fun serializeBody() = sessionId.toUByteArray()
    }
    data class KeyInput(val sessionId: UInt, val keycode: UShort, val pressed: Boolean) : SentPacket(0x05u) {
        override fun serializeBody() = ubyteArrayOf(*sessionId.toUByteArray(), *keycode.toUByteArray(), if (pressed) 0x01u else 0x00u)
    }
    data class KeycodeInput(val sessionId: UInt, val keycodes: List<UShort>) : SentPacket(0x06u) {
        override fun serializeBody() = ubyteArrayOf(*sessionId.toUByteArray()) + keycodes.map { it.toUByteArray() }.reduce(UByteArray::plus)
    }
    data class CharInput(val sessionId: UInt, val key: String) : SentPacket(0x07u) {
        override fun serializeBody() = ubyteArrayOf(*sessionId.toUByteArray(), *key.toByteArray(Charsets.ISO_8859_1).toUByteArray())
    }
    data class Resize(val sessionId: UInt, val width: UShort, val height: UShort) : SentPacket(0x08u) {
        override fun serializeBody() = ubyteArrayOf(*sessionId.toUByteArray(), *width.toUByteArray(), *height.toUByteArray())
    }
    data class MouseInput(val sessionId: UInt, val input: Input.Mouse) : SentPacket(0x09u) {
        override fun serializeBody() = input.run {
            ubyteArrayOf(
                *sessionId.toUByteArray(),
                button.number,
                *x.toUInt().toUByteArray(),
                *y.toUInt().toUByteArray(),
                ubyteFromBits(modifiers.shift, modifiers.meta, modifiers.control, dragged, down)
            )
        }
    }
}