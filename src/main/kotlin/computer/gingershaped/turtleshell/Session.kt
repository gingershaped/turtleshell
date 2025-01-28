package computer.gingershaped.turtleshell.session

import computer.gingershaped.turtleshell.packets.ReceivedPacket
import computer.gingershaped.turtleshell.packets.SentPacket
import computer.gingershaped.turtleshell.packets.SessionPacket
import computer.gingershaped.turtleshell.connection.SshConnection
import computer.gingershaped.turtleshell.util.decode
import computer.gingershaped.turtleshell.util.send
import computer.gingershaped.turtleshell.terminal.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.nio.charset.Charset
import java.nio.charset.CoderResult
import java.nio.ByteBuffer
import java.nio.CharBuffer
import io.ktor.util.logging.KtorSimpleLogger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend fun CoroutineScope.runSession(username: String, id: UInt, ssh: SshConnection, incoming: Flow<ReceivedPacket>, outgoing: SendChannel<SentPacket>): Nothing {
    val logger = KtorSimpleLogger("Relay[$id @ $username]")
    val term = AnsiTerminal(ssh.size.value.width, ssh.size.value.height, Ansi.Level.ANSI256) // TODO

    launch {
        generateInputs(decode(ssh.stdin, Charsets.UTF_8)).consumeEach { input ->
            outgoing.send(when (input) {
                is Input.Character -> SentPacket.CharInput(id, input.char.toString())
                is Input.Keycodes -> SentPacket.KeycodeInput(id, input.keycodes.map { it.code })
                is Input.Mouse -> SentPacket.MouseInput(id, input)
                is Input.Interrupt -> SentPacket.InterruptShellSession(id)
            })
        }
        logger.info("SSH disconnected, closing")
        outgoing.send(SentPacket.EndShellSession(id))
        this@runSession.cancel()
    }
    launch {
        ssh.stdout.send(Ansi.setModes(Ansi.Mode.ALTERNATE_BUFFER, Ansi.Mode.ALTERNATE_SCROLL, Ansi.Mode.MOUSE_TRACKING, Ansi.Mode.SGR_COORDS, enabled=true))
        ssh.stdout.send(term.redraw())
        outgoing.send(SentPacket.StartShellSession(id, 0b00000000u, term.width.toUShort(), term.height.toUShort()))
        launch {
            ssh.size.collect { (width, height) ->
                term.resize(width, height)
                outgoing.send(SentPacket.Resize(id, width.toUShort(), height.toUShort()))
            }
        }
        suspend fun closeSsh(reason: String) {
            ssh.stdout.send(
                Ansi.setModes(Ansi.Mode.ALTERNATE_BUFFER, Ansi.Mode.ALTERNATE_SCROLL, Ansi.Mode.MOUSE_TRACKING, Ansi.Mode.SGR_COORDS, enabled=false)
                + Ansi.setModes(Ansi.Mode.CURSOR_VISIBLE, enabled=true)
                + "\n"
                + Ansi.CSI + "0m"
                + "$reason\r\n"
            )
            ssh.stdin.cancel()
            this@runSession.cancel()
        }
        val builder = StringBuilder()
        incoming.collect { packet -> 
            if (packet is SessionPacket && packet.sessionId == id) {
                when (packet) {
                    is ReceivedPacket.EndSession -> {
                        logger.info("Session ended by host")
                        closeSsh("Session ended by host.")
                        cancel()
                        awaitCancellation()
                    }
                    is ReceivedPacket.Flush -> {
                        ssh.stdout.send(builder.toString())
                        builder.clear()
                    }
                    else -> when (packet) {
                        is ReceivedPacket.Blit -> {
                            term.blit(packet.text, packet.x.toInt(), packet.y.toInt())
                        }
                        is ReceivedPacket.Scroll -> {
                            term.scroll(packet.distance)
                        }
                        is ReceivedPacket.SetPaletteColor -> {
                            term.setPaletteColor(packet.index, packet.color)
                        }
                        is ReceivedPacket.SetCursorVisible -> {
                            Ansi.setModes(Ansi.Mode.CURSOR_VISIBLE, enabled=packet.visible)
                        }
                        is ReceivedPacket.SetCursorPosition -> {
                            term.moveCursor(packet.x.toInt(), packet.y.toInt())
                        }
                        is ReceivedPacket.FillLine -> {
                            term.fillLine(packet.line.toInt(), packet.color)
                        }
                        is ReceivedPacket.FillScreen -> {
                            term.fillScreen(packet.color)
                        }
                        else -> throw IllegalStateException()
                    }.let { builder.append(it) }
                }
            }
        }
        logger.info("Host disconnected")
        closeSsh("Host disconnected.")
    }

    logger.info("Relay started")
    awaitCancellation()
}