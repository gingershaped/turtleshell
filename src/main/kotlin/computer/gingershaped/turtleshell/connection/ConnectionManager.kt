package computer.gingershaped.turtleshell.connection

import computer.gingershaped.turtleshell.terminal.Ansi
import io.ktor.http.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ShellFactory
import java.net.URI
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.text.buildString

val CONNECT_TIMEOUT = 10.minutes

data class Challenge(val query: String, val response: Regex, val echo: Boolean = true)

@OptIn(ExperimentalUnsignedTypes::class)
class ConnectionManager(
    private val address: String,
    private val greeting: String,
    private val instructions: String,
    private val challenges: List<Challenge>,
    private val scope: CoroutineScope,
    private val sockets: SharedFlow<Pair<UUID, DefaultWebSocketServerSession>>,
) : ShellFactory, KeyboardInteractiveAuthenticator {
    private val activeConnections = mutableMapOf<UUID, Channel<SshConnection>>()

    override fun createShell(session: ChannelSession): Command {
        val uuid = runCatching { UUID.fromString(session.sessionContext.username) }.getOrNull()
        if (uuid != null && uuid in activeConnections) {
            logger.info("Creating a new shell for session $uuid")
            return SshConnection(uuid).also {
                activeConnections[uuid]!!.trySend(it).getOrThrow()
            }
        } else {
            val newUuid = UUID.randomUUID()
            logger.info("Creating a new session $newUuid")
            return SshConnection(newUuid).also { scope.launch { startNewSession(it) } }
        }
    }

    override fun generateChallenge(session: ServerSession, username: String, lang: String, subMethods: String): InteractiveChallenge {
        logger.info("New login attempt for session $username")
        return InteractiveChallenge().apply {
            interactionName = greeting
            interactionInstruction = instructions
            for (challenge in challenges) {
                addPrompt(challenge.query, challenge.echo)
            }
        }
    }

    override fun authenticate(session: ServerSession, username: String, responses: List<String>) =
        challenges.zip(responses).all { (challenge, response) -> 
            challenge.response.matches(response)
        }

    private suspend fun startNewSession(initialConnection: SshConnection) {
        coroutineScope {
            val uuid = initialConnection.uuid
            initialConnection.stdout.send(
                buildString {
                    append("\r\n")
                    append(Ansi.CSI + "1m")
                    append("Run this command on your CC computer to connect:\r\n")
                    append(Ansi.CSI + "0m")
                    append("    wget run ${URLBuilder(address).apply { path("/client") }} ${uuid}\r\n")
                }.encodeToByteArray()
            )
            val socketTask = async {
                withTimeoutOrNull(CONNECT_TIMEOUT) {
                    sockets.first { it.first == uuid }
                }?.second
            }
            val controlCListener = launch {
                while (true) {
                    val byte = initialConnection.stdin.receiveCatching().getOrNull()
                    if (byte == null) {
                        logger.info("Session $uuid closed while waiting for host")
                        break
                    }
                    if (byte.toInt() == 0x03) {
                        logger.info("Received Ctrl-C from session $uuid while waiting for host")
                        initialConnection.close()
                        break
                    }
                }
                socketTask.cancelAndJoin()
            }
            val socket = socketTask.await()
            controlCListener.cancelAndJoin()
            if (socket == null) {
                logger.info("Session $uuid timed out waiting for host")
                initialConnection.close("Timed out waiting for host")
                return@coroutineScope
            }
            val channel = Channel<SshConnection>(Channel.UNLIMITED).also { it.send(initialConnection) }
            activeConnections[uuid] = channel
            try {
                socket.runWebsocketConnection(uuid, channel).consumeEach { packet ->
                    socket.send(packet.serialize().toByteArray())
                }
            } finally {
                activeConnections.remove(uuid)!!
                socket.close()
            }
        }
    }

    internal companion object {
        val logger = KtorSimpleLogger("ConnectionManager")
    }
}