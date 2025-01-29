@file:OptIn(ExperimentalUnsignedTypes::class)
package computer.gingershaped.turtleshell.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset

val CHARSET = charset("computercraft")

fun UInt.toUByteArray() = (3 downTo 0).map { shr(8 * it).toUByte() }.toUByteArray()
fun UShort.toUByteArray() = ubyteArrayOf((this.toUInt() and 0xff00u shr 8).toUByte(), (this.toUInt() and 0x00ffu).toUByte())
fun ByteBuffer.toByteArray() = ByteArray(remaining()).also { get(it) }

@kotlinx.coroutines.ExperimentalCoroutinesApi
fun CoroutineScope.decode(channel: ReceiveChannel<Byte>, charset: Charset, bufferSize: Int = 256) = produce {
    val decoder = charset.newDecoder()
    val inputBuffer = ByteBuffer.allocate(bufferSize)
    val outputBuffer = CharBuffer.allocate(bufferSize)
    for (byte in channel) {
        val bufferWasFull = !inputBuffer.hasRemaining()
        if (inputBuffer.hasRemaining()) {
            inputBuffer.put(byte)
            if (!channel.isEmpty) {
                continue
            }
        }
        inputBuffer.flip()
        val decodeResult = decoder.decode(inputBuffer, outputBuffer, false)
        if (!decodeResult.isError) {
            inputBuffer.compact()
            outputBuffer.flip()
            while (outputBuffer.hasRemaining()) {
                send(outputBuffer.get())
            }
            outputBuffer.clear()
        } else {
            decodeResult.throwException()
        }
        if (bufferWasFull && inputBuffer.hasRemaining()) {
            inputBuffer.put(byte)
        }
    }
    inputBuffer.flip()
    decoder.decode(inputBuffer, outputBuffer, true).takeIf { it.isError }?.throwException()
    decoder.flush(outputBuffer).takeIf { it.isError }?.throwException()
    outputBuffer.flip()
    while (outputBuffer.hasRemaining()) {
        send(outputBuffer.get())
    }
}

suspend fun SendChannel<ByteArray>.send(string: String, charset: Charset = Charsets.UTF_8) {
    send(string.toByteArray(charset))
}

data class ColoredString(val content: CharArray, val colors: UByteArray) {
    val length = content.size
    companion object {
        fun fromBuffer(buffer: ByteBuffer) = with(buffer) {
            val length = remaining()
            check(length % 2 == 0)
            val half = length / 2
            val content = ByteArray(half).also { get(it, 0, half) }
            val colors = ByteArray(half).also { get(it, 0, half) }
            ColoredString(
                CHARSET.decode(ByteBuffer.wrap(content)).array(),
                colors.toUByteArray()
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ColoredString

        if (length != other.length) return false
        if (!content.contentEquals(other.content)) return false
        if (colors != other.colors) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + content.contentHashCode()
        result = 31 * result + colors.hashCode()
        return result
    }
}

val UByte.nybbles get() = toUInt().let { (it and 0xf0u shr 4) to (it and 0x0fu) }

infix fun Int.setOn(other: Int) = this and other > 0

fun ubyteFromBits(vararg bits: Boolean) =
    bits.takeLast(8).asReversed().foldIndexed(0u) { index, acc, bit -> acc + ((if (bit) 1u else 0u) shl index) }.toUByte()

@OptIn(ExperimentalStdlibApi::class)
val hexformat = HexFormat {
    bytes {
        byteSeparator = " "
    }
}