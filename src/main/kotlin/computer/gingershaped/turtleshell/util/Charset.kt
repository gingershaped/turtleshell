package computer.gingershaped.turtleshell.util

import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CoderResult
import java.nio.charset.CharsetDecoder
import java.nio.charset.spi.CharsetProvider
import java.nio.CharBuffer
import java.nio.ByteBuffer
import kotlin.streams.asSequence

private const val QUESTION_MARK: Byte = 0x3f

class CCCharset internal constructor() : Charset("computercraft", arrayOf()) {
    override operator fun contains(other: Charset) =
        Charsets.ISO_8859_1.contains(other)

    override fun newEncoder() = Encoder(this)
    override fun newDecoder() = Decoder(this)

    inner class Encoder(val charset: Charset) : CharsetEncoder(
        charset, 1F, 1F, byteArrayOf(QUESTION_MARK)
    ) {
        override fun encodeLoop(input: CharBuffer, output: ByteBuffer): CoderResult {
            // Our codepage includes characters from Symbols for Legacy Computing, so
            // this encoder has to be able to handle UTF-16 surrogate pairs
            while (input.hasRemaining()) {
                val char = input.get()
                val codepoint: Int
                if (char.isHighSurrogate()) {
                    // Surrogate magic happens in here
                    if (!input.hasRemaining()) {
                        input.position(input.position() - 1)
                        return CoderResult.UNDERFLOW
                    }
                    val low = input.get()
                    if (!low.isLowSurrogate()) {
                        return CoderResult.malformedForLength(2)
                    }
                    // Pulled from Wikipedia
                    codepoint = 0x10000 + (char.code - 0xd800) * 0x400 + (low.code - 0xdc00)
                } else {
                    codepoint = char.code
                }
                if (output.remaining() < 1) {
                    return CoderResult.OVERFLOW
                }
                val byte = CODEPAGE.indexOf(codepoint)
                if (byte < 1) {
                    return CoderResult.unmappableForLength(if (char.isHighSurrogate()) 2 else 1)
                }
                output.put(byte.toByte())
            }
            return CoderResult.UNDERFLOW
        }
    }

    inner class Decoder(val charset: Charset) : CharsetDecoder(
        charset, 1F, 1F
    ) {
        @OptIn(kotlin.ExperimentalStdlibApi::class)
        override fun decodeLoop(input: ByteBuffer, output: CharBuffer): CoderResult {
            while (input.hasRemaining()) {
                val byte = input.get().toUByte().toInt()
                // println("DECODE ${byte.toHexString()}")
                // if (byte !in CODEPAGE) {
                //     return CoderResult.unmappableForLength(1)
                // }
                val chars = Character.toChars(CODEPAGE[byte])
                if (output.remaining() < chars.size) {
                    return CoderResult.OVERFLOW
                }
                output.put(chars)
            }
            return CoderResult.UNDERFLOW
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    companion object {
        val CODEPAGE = ("""
        | ☺☻♥♣♦♠◘○  ♂♀ ♪♫
        |►◄↕‼¶§▬↨↑↓→←∟↔▲▼
        | !"#$%&'()*+,-./
        |0123456789:;<=>?
        |@ABCDEFGHIJKLMNO
        |PQRSTUVWXYZ[\]^_
        |`abcdefghijklmno
        |pqrstuvwxyz{|}~░
        | 🬀🬁🬂🬃🬄🬐🬆🬇🬐🬉🬊🬋🬌🬍🬎
        |🬏🬐🬑🬒🬓▌🬔🬕🬖🬗🬘🬙🬚🨛🬜🬝
        | ¡¢£¤¥¦§¨©ª«¬-®¯
        |°±²³´µ¶·¸¹º»¼½¾¿
        |ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏ
        |ÐÑÒÓÔÕÖ×ØÙÚÛÜÝÞß
        |àáâãäåæçèéêëìíîï
        |ðñòóôõö÷øùúûüýþÿ
        """.trimMargin() as java.lang.CharSequence).codePoints().filter { it != 0x0a }.toArray()
    }
}

class CCCharsetProvider : CharsetProvider() {
    private val INSTANCE = CCCharset()

    override fun charsets() = listOf(INSTANCE).iterator()
    override fun charsetForName(name: String) =
        if (name == "computercraft") INSTANCE else null
}