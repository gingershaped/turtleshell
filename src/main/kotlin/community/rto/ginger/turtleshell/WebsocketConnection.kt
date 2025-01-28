package community.rto.ginger.turtleshell

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.util.logging.KtorSimpleLogger
import community.rto.ginger.turtleshell.packets.SentPacket
import community.rto.ginger.turtleshell.packets.ReceivedPacket
import community.rto.ginger.turtleshell.terminal.relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineExceptionHandler
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

private suspend fun DefaultWebSocketServerSession.decodePackets() = flow {
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun DefaultWebSocketServerSession.runConnection(id: String, sshConnections: ReceiveChannel<SshConnection>) = produce<SentPacket> channel@{
    val logger = KtorSimpleLogger("WebsocketConnection[$id]")
    logger.info("Connection opened")

    send(SentPacket.Hello(MAJOR, MINOR, FEATURES))
    send(SentPacket.Message(id, SentPacket.Message.Type.AUTH))
    coroutineScope {
        val packetFlow = decodePackets().shareIn(this, SharingStarted.WhileSubscribed())
        launch {
            val sessionId = AtomicInteger()
            for (ssh in sshConnections) {
                logger.info("Starting relay session: $id")
                ssh.started.join()
                launch {
                    try {
                        relay(
                            id,
                            sessionId.getAndIncrement().toUInt(),
                            ssh,
                            packetFlow.takeWhile { it != null }.filterNotNull(),
                            this@channel
                        )
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        logger.info("Relay $id was canceled")
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