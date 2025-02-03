@file:OptIn(ExperimentalUnsignedTypes::class)
package computer.gingershaped.turtleshell.packets

import com.github.ajalt.colormath.Color
import com.github.ajalt.colormath.model.RGB
import computer.gingershaped.turtleshell.util.CHARSET
import computer.gingershaped.turtleshell.util.ColoredString
import computer.gingershaped.turtleshell.util.hexformat
import computer.gingershaped.turtleshell.util.toUByteArray
import computer.gingershaped.turtleshell.util.CCCharsetProvider
import computer.gingershaped.turtleshell.packets.getUInt
import computer.gingershaped.turtleshell.packets.getUByte
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.io.bytestring.getByteString
import kotlinx.io.bytestring.decodeToString

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

    data class FileResponse(val requestId: UInt, val response: Response) : ReceivedPacket() {
        sealed interface Response
        data object Success : Response
        data class Failure(val message: String) : Response
        data class OpenedFile(val handle: UInt, val size: ULong) : Response
        data class Read(val payload: ByteBuffer) : Response
        data class Write(val written: UInt) : Response
        data class DirectoryListing(val entries: List<Entry>) : Response {
            data class Entry(val isDirectory: Boolean, val absolutePath: String)
        }
        data class MountInformation(val driveName: String, val capacity: UInt, val free: UInt) : Response
        data class FileInformation(
            val size: UInt,
            val isDir: Boolean,
            val isReadOnly: Boolean,
            val created: ULong,
            val modified: ULong
        ) : Response, BasicFileAttributes {
            override fun lastModifiedTime() = FileTime.fromMillis(modified.toLong())

            override fun lastAccessTime() = FileTime.fromMillis(modified.toLong())

            override fun creationTime() = FileTime.fromMillis(created.toLong())

            override fun isRegularFile() = !isDir

            override fun isDirectory() = isDir

            override fun isSymbolicLink() = false

            override fun isOther() = false

            override fun size() = size.toLong()

            override fun fileKey() = null
        }
    }

    companion object {
        @OptIn(ExperimentalStdlibApi::class)
        fun decode(buf: ByteBuffer): ReceivedPacket = runCatching {
            when(val variant = buf.getUByte().toUInt()) {
                0x00u -> {
                    val sessionId = buf.getUInt()
                    val commands = mutableListOf<DrawCommand>()
                    while (buf.remaining() > 0) {
                        commands += when (val command = buf.getUByte().toUInt()) {
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
                0x21u -> {
                    val requestId = buf.getUInt()
                    val body = when (val responseType = buf.getUByte().toUInt()) {
                        0x00u -> FileResponse.Success
                        0x01u -> FileResponse.Failure(buf.getByteString().decodeToString(CCCharsetProvider.INSTANCE))
                        0x02u -> FileResponse.OpenedFile(buf.getUInt(), buf.getLong().toULong())
                        0x03u -> FileResponse.Read(buf.slice())
                        0x04u -> FileResponse.Write(buf.getUInt())
                        0x05u -> {
                            val entries = mutableListOf<FileResponse.DirectoryListing.Entry>()
                            while (buf.remaining() > 0) {
                                entries += FileResponse.DirectoryListing.Entry(
                                    buf.getUByte() > 0x00u,
                                    buf.getByteString(buf.getUInt().toInt()).decodeToString(CCCharsetProvider.INSTANCE),
                                )
                            }
                            FileResponse.DirectoryListing(entries)
                        }
                        0x06u -> FileResponse.MountInformation(
                            buf.getByteString(buf.getUInt().toInt()).decodeToString(CCCharsetProvider.INSTANCE),
                            buf.getUInt(),
                            buf.getUInt(),
                        )
                        0x07u -> FileResponse.FileInformation(
                            buf.getUInt(),
                            buf.getUByte() > 0x00u,
                            buf.getUByte() > 0x00u,
                            buf.getLong().toULong(),
                            buf.getLong().toULong(),
                        )
                        else -> error("File response variant byte is invalid! (${responseType.toHexString()})")
                    }
                    FileResponse(requestId, body)
                }
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