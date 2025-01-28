package computer.gingershaped.turtleshell.connection

import java.io.InputStream
import java.io.OutputStream
import java.io.Closeable

import org.slf4j.LoggerFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.server.command.AsyncCommand
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.Signal
import org.apache.sshd.common.channel.Channel as SshChannel
import org.apache.sshd.common.io.IoInputStream
import org.apache.sshd.common.io.IoOutputStream
import org.apache.sshd.common.io.IoReadFuture
import org.apache.sshd.common.io.IoWriteFuture
import org.apache.sshd.common.future.SshFutureListener
import org.apache.sshd.common.future.CloseFuture
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.ktor.util.logging.KtorSimpleLogger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SshConnection(val username: String, val bufferSize: Int = 8192) : AsyncCommand {
    val logger = KtorSimpleLogger("SshConnection[$username]")
    private val scope = CoroutineScope(CoroutineName("SshConnection[$username]") + Dispatchers.IO)
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
                        if (stream.isClosed() || stream.isClosing()) {
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