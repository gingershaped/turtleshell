package computer.gingershaped.turtleshell.connection

import computer.gingershaped.turtleshell.packets.ReceivedPacket
import computer.gingershaped.turtleshell.packets.SentPacket
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

const val MAJOR: UByte = 0x02u
const val MINOR: UByte = 0x00u
/* Feature bits:
 * LSB unused
 *   | KeyInput supported
 *   | Unused
 *   | Unused
 *   | Unused
 *   | Unused
 *   | Unused
 * MSB Unused
 */
const val FEATURES: UByte = 0b000000000u

private fun DefaultWebSocketServerSession.decodePackets() = flow {
    for (frame in incoming) {
        if (frame !is Frame.Binary) {
            error("Recieved nonbinary frame!")
        }
        emit(ReceivedPacket.decode(frame.buffer))
    }
    emit(null)
}

internal suspend fun <T> SharedFlow<T?>.subscribe(block: suspend (T) -> Unit) = 
    takeWhile { it != null }.filterNotNull().collect { block(it) }

@OptIn(ExperimentalCoroutinesApi::class)
fun DefaultWebSocketServerSession.runWebsocketConnection(uuid: UUID, sshConnections: ReceiveChannel<SshConnection>) = produce<SentPacket> channel@{
    val logger = KtorSimpleLogger("WebsocketConnection[$uuid]")
    logger.info("Connection opened")

    val packetFlow = MutableSharedFlow<ReceivedPacket?>()
    send(SentPacket.Hello(MAJOR, MINOR, FEATURES))

    val relays = launch {
        val sessionId = AtomicInteger()
        for (ssh in sshConnections) {
            logger.info("Starting relay session")
            ssh.started.join()
            launch {
                try {
                    runSession(
                        uuid,
                        sessionId.getAndIncrement().toUInt(),
                        ssh,
                        packetFlow.takeWhile { it != null }.filterNotNull(),
                        this@channel
                    )
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    logger.info("Relay $uuid was canceled")
                } finally {
                    ssh.stdin.cancel()
                }
            }
        }
    }

    runCatching { 
        incoming.consumeEach { frame ->
            if (frame !is Frame.Binary) {
                error("Recieved nonbinary frame!")
            }
            packetFlow.emit(ReceivedPacket.decode(frame.buffer))
        }
    }.onFailure { 
        logger.error("An exception occured while receiving data!", it)
    }.also {
        logger.info("Socket closed, stopping relays")
        packetFlow.emit(null)
        relays.join()
    }
}