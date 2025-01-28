package community.rto.ginger.turtleshell.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration
import java.util.Collections
import community.ginger.rto.turtleshell.ConnectionManager

fun Application.configureSockets(connectionManager: ConnectionManager) {
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
}
