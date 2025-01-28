package community.rto.ginger.turtleshell

import community.rto.ginger.turtleshell.plugins.*
import community.ginger.rto.turtleshell.SshPlugin
import community.ginger.rto.turtleshell.ConnectionManager
import kotlinx.coroutines.newCoroutineContext
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.HttpHeaders

fun main() {
    embeddedServer(Netty, port = 20909, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}


@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun Application.module() {
    val connectionManager = ConnectionManager()
    install(SshPlugin) {
        this.connectionManager = connectionManager
    }
    configureSockets(connectionManager)
    configureRouting()
}
