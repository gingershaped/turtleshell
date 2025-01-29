package computer.gingershaped.turtleshell

import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import org.apache.sshd.server.SshServer

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
