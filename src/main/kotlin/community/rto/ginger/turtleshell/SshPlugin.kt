package community.ginger.rto.turtleshell

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.netty.NettyIoServiceFactoryFactory
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.common.PropertyResolverUtils

class SshPluginConfig {
    lateinit var connectionManager: ConnectionManager
    var host = "0.0.0.0"
    var port = 9999
    var hostKey: Path = Path("./hostkey")
}

val SshPlugin = createApplicationPlugin(name = "SshPlugin", createConfiguration = ::SshPluginConfig) {
    val server = SshServer.setUpDefaultServer().apply {
        keyPairProvider = SimpleGeneratorHostKeyProvider(pluginConfig.hostKey)
        ioServiceFactoryFactory = NettyIoServiceFactoryFactory()
        shellFactory = pluginConfig.connectionManager
        keyboardInteractiveAuthenticator = pluginConfig.connectionManager
        publickeyAuthenticator = null
        host = pluginConfig.host
        port = pluginConfig.port

        CoreModuleProperties.SERVER_IDENTIFICATION.set(this, "TURTLESHELL-SSH-RELAY")
        CoreModuleProperties.IDLE_TIMEOUT.set(this, Duration.ofHours(1))
        CoreModuleProperties.PASSWORD_PROMPTS.set(this, 1)
    }
    application.log.info("SSH plugin loaded")
    
    on(MonitoringEvent(ApplicationStarted)) { application ->
        application.log.info("Starting SSH server")
        server.runCatching { start() }.onFailure { 
            application.log.error("Failed to start server", it)
        }.onSuccess { 
            application.log.info("Started SSH server")
        }.getOrThrow()
    }

    on(MonitoringEvent(ApplicationStopped)) { application ->
        application.log.info("Stopping SSH server")
        server.stop()
    }
}
