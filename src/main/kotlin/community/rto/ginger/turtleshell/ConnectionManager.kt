package community.ginger.rto.turtleshell

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
import community.rto.ginger.turtleshell.runConnection
import community.rto.ginger.turtleshell.SshConnection
import community.rto.ginger.turtleshell.util.hexformat
import community.rto.ginger.turtleshell.packets.ReceivedPacket
import java.util.UUID
import java.nio.ByteBuffer
import java.io.IOException

interface Terminal {
    val stdin: ReceiveChannel<Byte>
    val stdout: SendChannel<Byte>
    val stderr: SendChannel<Byte>
}

internal val logger = KtorSimpleLogger("ConnectionManager")

@OptIn(kotlin.ExperimentalUnsignedTypes::class, kotlin.ExperimentalStdlibApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionManager(val authInstruction: String = "") : ShellFactory, KeyboardInteractiveAuthenticator {
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
            interactionName = "Turtleshell SSH Relay"
            interactionInstruction = authInstruction
            addPrompt("Does the black moon howl? ", true)
        }
    }

    override fun authenticate(session: ServerSession, username: String, responses: List<String>): Boolean {
        return responses[0] == "yes"
    }

    suspend fun handleSocket(ws: DefaultWebSocketServerSession) {
        val id = "username-${(1..1000).random()}"
        logger.info("Starting new connection with id $id")
        connections[id] = Channel(Channel.UNLIMITED)
        try {
            ws.runConnection(id, connections[id]!!).consumeEach { packet ->
                ws.send(packet.serialize().toByteArray())
            }
        } finally {
            connections.remove(id)!!
        }
    }
}