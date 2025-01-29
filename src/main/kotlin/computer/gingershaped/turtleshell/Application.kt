package computer.gingershaped.turtleshell

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceOrFileSource
import com.sksamuel.hoplite.addResourceSource
import computer.gingershaped.turtleshell.connection.Challenge
import computer.gingershaped.turtleshell.connection.ConnectionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.netty.NettyIoServiceFactoryFactory
import org.apache.sshd.server.SshServer
import java.net.URI
import java.time.Duration
import java.util.*

data class Config(
    val http: Http,
    val ssh: Ssh,
    val auth: Auth,
) {
    data class Http(
        val host: String,
        val port: Int,
        val address: String,
    ) {
        val addressUrl = Url(address)
    }
    data class Ssh(
        val host: String,
        val port: Int,
        val hostKey: String,
        val address: String,
    )
    data class Auth(
        val greeting: String,
        val instructions: String,
        val challenges: List<Challenge> = listOf(),
    )
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, com.sksamuel.hoplite.ExperimentalHoplite::class)
fun main() {
    val config = ConfigLoaderBuilder.default()
        .withExplicitSealedTypes("type")
        .addResourceSource("/default.toml")
        .addResourceOrFileSource("config.toml")
        .build().loadConfigOrThrow<Config>()

    val socketAddress = URLBuilder(config.http.addressUrl).apply {
        set(scheme = if (protocol.name == "http") { "ws" } else { "wss" })
        path("ws")
    }.buildString()
    val clientLua = Unit.javaClass.getResourceAsStream("/client.lua")!!
        .reader().use { it.readText() }
        .replace("\$SOCKETADDRESS", socketAddress)
        .replace("\$SSHADDRESS", config.ssh.address)
        .replace("\$SSHPORT", config.ssh.port.toString())

    embeddedServer(Netty, host = config.http.host, port = config.http.port) {
        val socketFlow = MutableSharedFlow<Pair<UUID, DefaultWebSocketServerSession>>()
        val connectionManager = ConnectionManager(
            config.http.address,
            config.auth.greeting,
            config.auth.instructions,
            config.auth.challenges,
            this,
            socketFlow,
        )
        install(SshPlugin) {
            server = SshServer.setUpDefaultServer().apply {
                keyPairProvider = KeyPairProvider.wrap(
                    SecurityUtils.getKeyPairResourceParser().loadKeyPairs(null, null, null, config.ssh.hostKey)
                )
                ioServiceFactoryFactory = NettyIoServiceFactoryFactory()
                shellFactory = connectionManager
                keyboardInteractiveAuthenticator = connectionManager
                publickeyAuthenticator = null
                host = config.ssh.host
                port = config.ssh.port
        
                CoreModuleProperties.SERVER_IDENTIFICATION.set(this, "TURTLESHELL-SSH-RELAY")
                CoreModuleProperties.IDLE_TIMEOUT.set(this, Duration.ofHours(1))
                CoreModuleProperties.PASSWORD_PROMPTS.set(this, 1)
            }
        }
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        routing {
            webSocket("/ws/{uuid}") {
                val uuid = runCatching {
                    UUID.fromString(call.parameters["uuid"])
                }.getOrNull()
                if (uuid == null) {
                    application.log.info("Request rejected for malformed UUID ${uuid}")
                    call.respond(HttpStatusCode.BadRequest)
                } else {
                    application.log.info("WebSocket opened for session ${uuid}")
                    socketFlow.emit(uuid to this)
                    closeReason.await()
                    application.log.info("WebSocket closed for session ${uuid}")
                }
            }
            get("/client") {
                call.respondText(clientLua)
            }
        }
    }.start(wait = true)
}