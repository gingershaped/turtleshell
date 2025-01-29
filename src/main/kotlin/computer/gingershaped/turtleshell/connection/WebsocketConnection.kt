package computer.gingershaped.turtleshell.connection

import computer.gingershaped.turtleshell.packets.ReceivedPacket
import computer.gingershaped.turtleshell.packets.SentPacket
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

const val MAJOR: UByte = 0xFFu
const val MINOR: UByte = 0xFFu
/* Feature bits:
 * LSB SetSecret supported
 *   | KeyInput supported
 *   | Unused
 *   | Unused
 *   | Unused
 *   | Unused
 *   | Unused
 * MSB Unused
 */
const val FEATURES: UByte = 0b000000001u

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

    send(SentPacket.Hello(MAJOR, MINOR, FEATURES))
    coroutineScope {
        val packetFlow = decodePackets().shareIn(this, SharingStarted.WhileSubscribed())
        launch {
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
        packetFlow.subscribe { packet ->
            when (packet) {
                is ReceivedPacket.SetSecret -> TODO()
                else -> Unit   
            }
        }
        logger.info("Websocket disconnected")
        cancel()
    }
}