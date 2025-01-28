import { pretty } from "cc.pretty";
import Logger from "./logger";
import Buffer from "./packets/buffer";
import { MessageType, MouseButton, Variant, deserialize } from "./packets/received";
import { SessionTerminal, intify } from "./session";

// This is replaced by the server with a value in its config
const ADDRESS = "$ADDRESS";

interface Session {
    thread: LuaThread;
    filter: string | undefined;
    currentTerminal: ITerminal;
    sessionTerminal: SessionTerminal;
}

class Host {
    readonly NOT_FORWARDED_EVENTS = ["char", "key", "key_up", "paste", "file_transfer", "mouse_click", "mouse_drag", "mouse_up", "mouse_scroll"];
    ws: WebSocket;
    logger: Logger;
    relayLogger: Logger;
    sessions: Map<number, Session> = new Map();
    parent: ITerminal;

    constructor(parent: ITerminal, readonly address: string) {
        this.parent = parent;
        this.logger = new Logger("host ", parent);
        this.relayLogger = new Logger("relay", parent);
    }

    startSession(id: number, width: number, height: number) {
        const terminal = new SessionTerminal(this.ws, id, width, height);

        let terminable = {} as ITerminal;
        for (const [key] of pairs(term.native())) {
            const value = terminal[key]
            if (typeof value == "function") {
                terminable[key] = (...args) => (value as (this: void, ...args) => any)(terminal, ...args)
            }
        }

        const session = {
            thread: coroutine.create(function() {
                const env = new LuaTable();
                env.set("_TURTLESHELL_SESSION", id);
                const [ok, tb] = xpcall(() => os.run(env, "/rom/programs/shell.lua"), (err) => {
                    return debug.traceback(err)
                });
                if (!ok) {
                    error(tb)
                }
                term.setCursorBlink(false);
                print("Program has exited. Press any key to end the session...");
                os.pullEvent("char");
            }),
            filter: undefined,
            currentTerminal: terminable,
            sessionTerminal: terminal,
        };
        this.sessions.set(id, session);
        this.resumeSession(id);
    }

    resumeSession(id: number, event?: string, ...args: any[]) {
        const session = this.sessions.get(id);
        if (session.filter == undefined || session.filter == event || event == "terminate") {
            term.redirect(session.currentTerminal);
            const [ok, filter] = coroutine.resume(session.thread, event, ...args);
            // in case the process called redirect() itself
            session.currentTerminal = term.current();
            if (ok) {
                if (typeof filter == "string" || typeof filter == "undefined") {
                    session.filter = filter;
                }
            } else {
                this.logger.log(`Session ${id} errored!`, MessageType.ERROR);
                this.logger.log(filter, MessageType.ERROR);
            }
        }
        if (coroutine.status(session.thread) == "dead") {
            this.logger.log(`Session ${id} has exited`);
            this.sessions.delete(id);
            session.sessionTerminal.close();
            return false;
        } else {
            session.sessionTerminal.flush();
        }
        return true;
    }

    killAll() {
        while (this.sessions.size > 0) {
            for (const id of this.sessions.keys()) {
                this.resumeSession(id, "terminate");
            }
        }
    }

    private _run() {
        this.logger.log("Connecting to relay");
        const [ws, message] = http.websocket(this.address);
        if (ws == false) {
            error(message);
        }
        this.ws = ws
        const buf = Buffer.wrap(ws.receive())
        const hello = deserialize(buf)
        if (hello.variant != Variant.HELLO) {
            error("Relay said something that wasn't hello!")
        }
        this.logger.log("Connection established");

        while (true) {
            const [event, ...args] = os.pullEventRaw();
            if (event == "websocket_message" && args[0] == this.address) {
                const packet = deserialize(Buffer.wrap(args[1]));
                if ("sessionId" in packet) {
                    if (packet.variant != 0x02 && !this.sessions.has(packet.sessionId)) {
                        this.logger.log(`Received packet ${packet.variant} for invalid session ${packet.sessionId}`, MessageType.WARNING);
                        continue;
                    } else if (packet.variant == 0x02 && this.sessions.has(packet.sessionId)) {
                        error(`Relay attempted to start an already existing session ${packet.sessionId}!`);
                    }
                }
                // this.logger.log(textutils.serialise(packet));
                switch (packet.variant) {
                    case Variant.HELLO: {
                        error("Relay said hello twice!");
                        break;
                    }
                    case Variant.MESSAGE: {
                        this.relayLogger.log(packet.message, packet.type);
                        break;
                    }
                    case Variant.START_SHELL_SESSION: {
                        this.logger.log(`Starting new session: ${packet.sessionId}`);
                        this.startSession(packet.sessionId, packet.width, packet.height);
                        break;
                    }
                    case Variant.END_SHELL_SESSION: {
                        this.logger.log(`Relay ended session: ${packet.sessionId}`);
                        while (this.sessions.has(packet.sessionId)) {
                            this.resumeSession(packet.sessionId, "terminate");
                        }
                        break;
                    }
                    case Variant.INTERRUPT_SHELL_SESSION: {
                        this.logger.log(`Relay interrupted session: ${packet.sessionId}`)
                        this.resumeSession(packet.sessionId, "terminate");
                        break;
                    }
                    case Variant.KEY_INPUT: {
                        this.resumeSession(
                            packet.sessionId,
                            packet.pressed ? "key" : "key_up",
                            packet.keycode,
                            false
                        );
                        break;
                    }
                    case Variant.KEYCODE_INPUT: {
                        for (const [event, keycode] of packet.keycodes.map<[string, number]>((c) => ["key", c]).concat(packet.keycodes.map((c) => ["key_up", c]))) {
                            const died = this.resumeSession(packet.sessionId, event, keycode, false);
                            if (died) {
                                break;
                            }
                        }
                        break;
                    }
                    case Variant.CHAR_INPUT: {
                        this.resumeSession(packet.sessionId, "char", packet.key);
                        break;
                    }
                    case Variant.RESIZE: {
                        this.sessions.get(packet.sessionId).sessionTerminal.resize(packet.width, packet.height);
                        this.resumeSession(packet.sessionId, "term_resize");
                        break;
                    }
                    case Variant.MOUSE_INPUT: {
                        // TODO: Modifier handling
                        packet.x += 1;
                        packet.y += 1;
                        if (packet.button == MouseButton.SCROLL_UP || packet.button == MouseButton.SCROLL_DOWN) {
                            this.resumeSession(
                                packet.sessionId,
                                "mouse_scroll",
                                packet.button == MouseButton.SCROLL_UP ? -1 : 1,
                                packet.x,
                                packet.y
                            );
                        } else if (packet.button != MouseButton.OTHER) {
                            const button = [1, 3, 2][packet.button]; // CC uses Left-Right-Middle instead of Left-Middle-Right
                            if (packet.dragged) {
                                this.resumeSession(
                                    packet.sessionId,
                                    "mouse_drag",
                                    button,
                                    packet.x,
                                    packet.y
                                );
                            } else {
                                this.resumeSession(
                                    packet.sessionId,
                                    packet.down ? "mouse_click" : "mouse_up",
                                    button,
                                    packet.x,
                                    packet.y
                                )
                            }
                        }
                        break;
                    }
                }
            } else if (event == "websocket_closed" && args[0] == this.address) {
                this.logger.log("Websocket was closed, shutting down")
                this.killAll()
                break;
            } else if (event == "terminate") {
                this.logger.log("Shutting down");
                this.killAll()
                this.ws.close();
                break;
            } else if (!this.NOT_FORWARDED_EVENTS.includes(event)) {
                for (const id of this.sessions.keys()) {
                    this.resumeSession(id, event, ...args);
                }
            }
        }
    }

    run() {
        const ok = xpcall(this._run.bind(this), (err) => {
            term.redirect(this.parent);
            this.ws.close();
            this.logger.log(debug.traceback("An internal error occured"), MessageType.ERROR);
            this.logger.log(err, MessageType.ERROR);
        })
        if (ok) {
            term.redirect(this.parent);
            this.ws.close();
        }
    }
}

new Host(term.current(), ADDRESS).run()
