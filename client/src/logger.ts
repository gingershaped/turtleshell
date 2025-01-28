import { MessageType } from "./packets/received";

export default class Logger {
    readonly levels = ["INFO", "WARN", "ERR!", "AUTH"];
    constructor(readonly name: string, readonly term: ITerminal) {}

    log(message: string, level: MessageType = MessageType.INFO) {
        const previousTerminal = term.redirect(this.term);
        write(`[${this.name}] `);
        switch (level) {
            case MessageType.INFO: {
                this.term.setTextColor(colors.lightGray);
                break;
            }
            case MessageType.WARNING: {
                this.term.setTextColor(colors.yellow);
                break;
            }
            case MessageType.ERROR: {
                this.term.setTextColor(colors.red);
                break;
            }
            case MessageType.AUTH: {
                this.term.setTextColor(colors.purple);
                break;
            }
        }
        write(this.levels[level]);
        this.term.setTextColor(colors.lightGray);
        write(": ");
        this.term.setTextColor(colors.white);
        print(message);
        term.redirect(previousTerminal);
    }
}