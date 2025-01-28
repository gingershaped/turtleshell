package community.rto.ginger.turtleshell.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        get("/host") {
            call.respondFile(File("/home/ginger/turtleshell/client/main.lua"))
        }
    }
}
