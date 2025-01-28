package computer.gingershaped.turtleshell.connection

import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.session.ServerSession
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import io.ktor.websocket.close
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext
import kotlin.ExperimentalStdlibApi
import computer.gingershaped.turtleshell.connection.runWebsocketConnection
import computer.gingershaped.turtleshell.connection.SshConnection
import computer.gingershaped.turtleshell.session.runSession
import computer.gingershaped.turtleshell.util.hexformat
import computer.gingershaped.turtleshell.packets.ReceivedPacket
import java.util.UUID
import java.nio.ByteBuffer
import java.io.IOException

data class Challenge(val query: String, val response: Regex, val echo: Boolean = false)

@OptIn(kotlin.ExperimentalUnsignedTypes::class, kotlin.ExperimentalStdlibApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionManager(
    val greeting: String,
    val instructions: String,
    val challenges: List<Challenge>
) : ShellFactory, KeyboardInteractiveAuthenticator {
    val connections = mutableMapOf<String, Channel<SshConnection>>()

    override fun createShell(session: ChannelSession): Command {
        val username = session.sessionContext.username
        val channel = connections[username]
            ?: throw IOException("User disconnected early")
        logger.info("Creating a new shell for $username")
        return SshConnection(username).also { channel.trySend(it).getOrThrow() }
    }

    override fun generateChallenge(session: ServerSession, username: String, lang: String, subMethods: String): InteractiveChallenge? {
        if (!connections.containsKey(username)) {
            logger.info("Login attempted with invalid username ${username}")
            return null
        }
        logger.info("New login attempt for session ${username}")
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

    suspend fun handleSocket(ws: DefaultWebSocketServerSession) {
        val id = "username-${(1..1000).random()}"
        logger.info("Starting new connection with id $id")
        connections[id] = Channel(Channel.UNLIMITED)
        try {
            ws.runWebsocketConnection(id, connections[id]!!).consumeEach { packet ->
                ws.send(packet.serialize().toByteArray())
            }
        } finally {
            connections.remove(id)!!
        }
    }

    internal companion object {
        val logger = KtorSimpleLogger("ConnectionManager")
    }
}