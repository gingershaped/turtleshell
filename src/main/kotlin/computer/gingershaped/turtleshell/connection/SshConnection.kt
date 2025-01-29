package computer.gingershaped.turtleshell.connection

import computer.gingershaped.turtleshell.terminal.Ansi
import computer.gingershaped.turtleshell.util.send
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.*
import org.apache.sshd.common.future.CloseFuture
import org.apache.sshd.common.future.SshFutureListener
import org.apache.sshd.common.io.IoInputStream
import org.apache.sshd.common.io.IoOutputStream
import org.apache.sshd.common.io.IoReadFuture
import org.apache.sshd.common.io.IoWriteFuture
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.AsyncCommand
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.coroutines.resume
import org.apache.sshd.common.channel.Channel as SshChannel

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SshConnection(val uuid: UUID, val bufferSize: Int = 8192) : AsyncCommand {
    val logger = KtorSimpleLogger("SshConnection[$uuid]")
    val scope = CoroutineScope(CoroutineName("SshConnection[$uuid]") + Dispatchers.IO)
    private val startJob = Job(scope.coroutineContext[Job]!!)
    val started: Job
        get() = startJob
    private val completion = CompletableDeferred<CompletionReason>(scope.coroutineContext[Job]!!)
    private lateinit var stdinStream: IoInputStream
    private lateinit var stdoutStream: IoOutputStream
    private lateinit var stderrStream: IoOutputStream
    private lateinit var exitCb: ExitCallback
    private lateinit var _size: MutableStateFlow<Size>

    lateinit var size: StateFlow<Size>

    val stdin = callbackFlow<Byte> {
        startJob.join()
        if (!stdinStream.isClosed) {
            val buffer = ByteArrayBuffer(bufferSize)
            var readFuture: IoReadFuture
            val closeListener = SshFutureListener<CloseFuture> { close() }
            stdinStream.addCloseFutureListener(closeListener)

            fun readListener(future: IoReadFuture) {
                check(future.isDone)
                if (stdinStream.isClosing || stdinStream.isClosed) {
                    close()
                } else if (future.exception != null) {
                    logger.error("Failed to read", future.exception)
                    close(future.exception)
                } else {
                    buffer.compact()
                    buffer.compactData.forEach {
                        trySendBlocking(it).onFailure {
                            logger.warn("Failed to send buffer", it)
                        }
                    }
                    buffer.clear()
                    readFuture = stdinStream.read(buffer).addListener(::readListener)
                }
            }
            readFuture = stdinStream.read(buffer).addListener(::readListener)

            awaitClose {
                stdinStream.removeCloseFutureListener(closeListener)
                readFuture.removeListener(::readListener)
                if (completion.isActive) {
                    logger.debug("stdin was closed")
                    completion.complete(CompletionReason.STDIN_CLOSED)
                }
            }
        }
    }.buffer(Channel.UNLIMITED).produceIn(scope)

    val stdout = Channel<ByteArray>(bufferSize)
    val stderr = Channel<ByteArray>(bufferSize)

    override fun start(session: ChannelSession, env: Environment) {
        logger.info("Starting connection")
        if (startJob.isCancelled) {
            exitCb.onExit(-1, "Cancelled early")
            return
        }
        val stdoutJob = scope.launch {
            consumeChannelToStream(stdout, stdoutStream)
        }
        val stderrJob = scope.launch {
            consumeChannelToStream(stderr, stderrStream)
        }

        _size = MutableStateFlow(env.size())
        size = _size.asStateFlow()
        val resizeListener = SignalListener { channel: SshChannel, _ ->
            if (channel == session) {
                _size.value = (channel as ChannelSession).environment.size()
            }
        }
        env.addSignalListener(resizeListener, Signal.WINCH)
        
        logger.info("Started connection")
        startJob.complete()
        scope.launch(NonCancellable) {
            try {
                when (completion.await()) {
                    CompletionReason.USER_DISCONNECTED -> {
                        // All streams are closed
                        stdin.cancel()
                        stdoutJob.cancel()
                        stderrJob.cancel()
                    }
                    CompletionReason.STDIN_CLOSED -> {
                        // We can still finish sending data
                        stdout.close()
                        stderr.close()
                        stdoutJob.join()
                        stderrJob.join()
                        exitCb.onExit(0, "Stdin closed")
                    }
                }
            } catch (e: CancellationException) {
                scope.cancel()
                exitCb.onExit(-1)
            } finally {
                env.removeSignalListener(resizeListener)
            }
        }
    }

    suspend fun close(reason: String? = null, reset: Boolean = false) {
        stdout.send(
            Ansi.setModes(Ansi.Mode.ALTERNATE_BUFFER, Ansi.Mode.ALTERNATE_SCROLL, Ansi.Mode.MOUSE_TRACKING, Ansi.Mode.SGR_COORDS, enabled=false)
            + Ansi.setModes(Ansi.Mode.CURSOR_VISIBLE, enabled=true)
            + "\n"
            + Ansi.CSI + "0m"
            + "${reason ?: "Goodbye"}\r\n"
        )
        stdin.cancel()
    }

    private suspend fun CoroutineScope.consumeChannelToStream(channel: Channel<ByteArray>, stream: IoOutputStream) {
        // Be warned: the API for SSHD's Buffer works _completely_ differently
        // from the API for NIO buffers, despite using similar names for its functionality
        val buffer = ByteArrayBuffer(bufferSize)
        channel.consume {
            for (array in this) {
                for (chunk in array.toList().chunked(bufferSize).map { it.toByteArray() }) {
                    buffer.wpos(0)
                    // Using putBytes prepends the length of the array. What the fuck, Apache?
                    buffer.putRawBytes(chunk)
                    buffer.rpos(0)
                    suspendCancellableCoroutine<Unit> { continuation ->
                        fun writeListener(future: IoWriteFuture) {
                            check(future.isDone)
                            if (future.exception != null) {
                                continuation.cancel(future.exception)
                            } else {
                                check(future.isWritten)
                                continuation.resume(Unit)
                            }
                        }
                        if (stream.isClosed || stream.isClosing) {
                            logger.warn("Failed to send buffer to a closed stream")
                            continuation.resume(Unit)
                        } else {
                            val future = stream.writeBuffer(buffer).addListener(::writeListener)
                            continuation.invokeOnCancellation {
                                future.removeListener(::writeListener)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun destroy(session: ChannelSession) {
        if (completion.isActive) {
            logger.info("User has disconnected")
            completion.complete(CompletionReason.USER_DISCONNECTED)
        }
    }

    override fun setIoInputStream(stream: IoInputStream) {
        stdinStream = stream
    }

    override fun setIoOutputStream(stream: IoOutputStream) {
        stdoutStream = stream
    }

    override fun setIoErrorStream(stream: IoOutputStream) {
        stderrStream = stream
    }

    override fun setExitCallback(cb: ExitCallback) {
        exitCb = cb
    }

    // These aren't used for anything, but the interface
    // requires them regardless
    override fun setInputStream(stream: InputStream) {}
    override fun setOutputStream(stream: OutputStream) {}
    override fun setErrorStream(stream: OutputStream) {}

    private enum class CompletionReason {
        USER_DISCONNECTED, STDIN_CLOSED
    }

    data class Size(val width: Int, val height: Int)
    fun Environment.size() =
        Size(env[Environment.ENV_COLUMNS]!!.toInt(), env[Environment.ENV_LINES]!!.toInt())
}