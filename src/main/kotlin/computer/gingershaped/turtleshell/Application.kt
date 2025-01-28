package computer.gingershaped.turtleshell

import computer.gingershaped.turtleshell.SshPlugin
import computer.gingershaped.turtleshell.connection.ConnectionManager
import computer.gingershaped.turtleshell.connection.Challenge
import kotlinx.coroutines.newCoroutineContext
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.HttpHeaders
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.netty.NettyIoServiceFactoryFactory
import org.apache.sshd.core.CoreModuleProperties
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceOrFileSource
import com.sksamuel.hoplite.addResourceSource
import java.time.Duration

data class Config(
    val http: Http,
    val ssh: Ssh,
    val auth: Auth,
) {
    data class Http(
        val host: String,
        val port: Int,
    )
    data class Ssh(
        val host: String,
        val port: Int,
        val hostKey: String,
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

    embeddedServer(Netty, host = config.http.host, port = config.http.port) {
        val connectionManager = ConnectionManager(
            config.auth.greeting,
            config.auth.instructions,
            config.auth.challenges
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
            webSocket("/ws") {
                connectionManager.handleSocket(this)
            }
        }
    }.start(wait = true)
}