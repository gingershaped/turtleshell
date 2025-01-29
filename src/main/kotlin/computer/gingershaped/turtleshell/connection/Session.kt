package computer.gingershaped.turtleshell.connection

import computer.gingershaped.turtleshell.packets.ReceivedPacket
import computer.gingershaped.turtleshell.packets.SentPacket
import computer.gingershaped.turtleshell.packets.SessionPacket
import computer.gingershaped.turtleshell.terminal.Ansi
import computer.gingershaped.turtleshell.terminal.AnsiTerminal
import computer.gingershaped.turtleshell.terminal.Input
import computer.gingershaped.turtleshell.terminal.generateInputs
import computer.gingershaped.turtleshell.util.decode
import computer.gingershaped.turtleshell.util.send
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun runSession(
    username: UUID,
    id: UInt,
    ssh: SshConnection,
    incoming: Flow<ReceivedPacket>,
    outgoing: SendChannel<SentPacket>
): Nothing {
    coroutineScope {
        val logger = KtorSimpleLogger("Relay[$id @ $username]")
        val term = AnsiTerminal(ssh.size.value.width, ssh.size.value.height, Ansi.Level.ANSI256) // TODO
        launch {
            generateInputs(decode(ssh.stdin, Charsets.UTF_8)).consumeEach { input ->
                outgoing.send(
                    when (input) {
                        is Input.Character -> SentPacket.CharInput(id, input.char.toString())
                        is Input.Keycodes -> SentPacket.KeycodeInput(id, input.keycodes.map { it.code })
                        is Input.Mouse -> SentPacket.MouseInput(id, input)
                        is Input.Interrupt -> SentPacket.InterruptShellSession(id)
                    }
                )
            }
            logger.info("SSH disconnected, closing")
            outgoing.send(SentPacket.EndShellSession(id))
            cancel()
        }
        launch {
            ssh.stdout.send(
                Ansi.setModes(
                    Ansi.Mode.ALTERNATE_BUFFER,
                    Ansi.Mode.ALTERNATE_SCROLL,
                    Ansi.Mode.MOUSE_TRACKING,
                    Ansi.Mode.SGR_COORDS,
                    enabled = true
                )
            )
            ssh.stdout.send(term.redraw())
            outgoing.send(SentPacket.StartShellSession(id, 0b00000000u, term.width.toUShort(), term.height.toUShort()))
            launch {
                ssh.size.collect { (width, height) ->
                    term.resize(width, height)
                    outgoing.send(SentPacket.Resize(id, width.toUShort(), height.toUShort()))
                }
            }
            suspend fun closeSsh(reason: String) {
                ssh.close(reason, true)
                cancel()
            }

            incoming.collect { packet ->
                if (packet is SessionPacket && packet.sessionId == id) {
                    when (packet) {
                        is ReceivedPacket.EndSession -> {
                            logger.info("Session ended by host")
                            closeSsh("Session ended by host.")
                            cancel()
                            awaitCancellation()
                        }

                        is ReceivedPacket.SetPaletteColor -> {
                            term.setPaletteColor(packet.index, packet.color)
                        }

                        is ReceivedPacket.Draw -> {
                            packet.commands.map { command ->
                                when (command) {
                                    is ReceivedPacket.DrawCommand.Blit -> {
                                        term.blit(command.text, command.x.toInt(), command.y.toInt())
                                    }
        
                                    is ReceivedPacket.DrawCommand.Scroll -> {
                                        term.scroll(command.distance)
                                    }
        
                                    is ReceivedPacket.DrawCommand.SetCursorVisible -> {
                                        Ansi.setModes(Ansi.Mode.CURSOR_VISIBLE, enabled = command.visible)
                                    }
        
                                    is ReceivedPacket.DrawCommand.SetCursorPosition -> {
                                        term.moveCursor(command.x.toInt(), command.y.toInt())
                                    }
        
                                    is ReceivedPacket.DrawCommand.FillLine -> {
                                        term.fillLine(command.line.toInt(), command.color)
                                    }
        
                                    is ReceivedPacket.DrawCommand.FillScreen -> {
                                        term.fillScreen(command.color)
                                    }
                                }
                            }.joinToString("").let {
                                ssh.stdout.send(it)
                            }                            
                        }
                    }
                }
            }
            logger.info("Host disconnected")
            closeSsh("Host disconnected.")
        }
        logger.info("Relay started")
        awaitCancellation()
    }
}