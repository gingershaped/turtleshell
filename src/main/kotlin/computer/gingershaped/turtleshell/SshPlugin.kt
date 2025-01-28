package computer.gingershaped.turtleshell

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
import org.apache.sshd.common.keyprovider.KeyPairProvider

class SshPluginConfig {
    lateinit var server: SshServer
}

val SshPlugin = createApplicationPlugin(name = "SshPlugin", createConfiguration = ::SshPluginConfig) {
    val server = pluginConfig.server
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
