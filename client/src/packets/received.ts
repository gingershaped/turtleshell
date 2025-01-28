import Buffer from "./buffer";

export const enum Variant {
    HELLO                   = 0x00,
    MESSAGE                 = 0x01,
    START_SHELL_SESSION     = 0x02,
    END_SHELL_SESSION       = 0x03,
    INTERRUPT_SHELL_SESSION = 0x04,
    KEY_INPUT               = 0x05,
    KEYCODE_INPUT           = 0x06,
    CHAR_INPUT              = 0x07,
    RESIZE                  = 0x08,
    MOUSE_INPUT             = 0x09,
}

export interface Hello {
    variant: Variant.HELLO,
    major: number;
    minor: number;
    features: number;
}

export const enum MessageType {
    INFO = 0x00, WARNING = 0x01, ERROR = 0x02, AUTH = 0x03
}
export interface Message {
    variant: Variant.MESSAGE;
    message: string;
    type: MessageType;
}

export interface StartShellSession {
    variant: Variant.START_SHELL_SESSION;
    sessionId: number;
    features: number;
    width: number;
    height: number;
}

export interface EndShellSession {
    variant: Variant.END_SHELL_SESSION;
    sessionId: number;
}

export interface InterruptShellSession {
    variant: Variant.INTERRUPT_SHELL_SESSION;
    sessionId: number;
}

export interface KeyInput {
    variant: Variant.KEY_INPUT;
    sessionId: number;
    keycode: number;
    pressed: boolean;
}

export interface KeycodeInput {
    variant: Variant.KEYCODE_INPUT;
    sessionId: number;
    keycodes: number[];
}

export interface CharInput {
    variant: Variant.CHAR_INPUT;
    sessionId: number;
    key: string;
}

export interface Resize {
    variant: Variant.RESIZE;
    sessionId: number;
    width: number;
    height: number;
}

export const enum MouseButton {
    LEFT = 0x00, MIDDLE = 0x01, RIGHT = 0x02, SCROLL_UP = 0x03, SCROLL_DOWN = 0x04, OTHER = 0x05
}

export interface MouseInput {
    variant: Variant.MOUSE_INPUT;
    sessionId: number;
    button: MouseButton;
    x: number;
    y: number;
    shift: boolean;
    meta: boolean;
    control: boolean;
    dragged: boolean;
    down: boolean;
}

export type Packet = Hello | Message | StartShellSession | EndShellSession | InterruptShellSession | KeyInput | KeycodeInput | CharInput | Resize | MouseInput;

function toShortArray(array: number[]) {
    const result = [];
    assert(array.length % 2 == 0);
    for (let i = 0; i < (array.length / 2); i++) {
        const upper = array[i];
        const lower = array[i + 1];
        assert(upper >= 0x00 && upper <= 0xff);
        assert(lower >= 0x00 && lower <= 0xff);
        result.push((upper << 8) + lower)
    }
    return result;
}

// TODO: This sucks! Do it better.
export function deserialize(buffer: Buffer): Packet {
    const variant = buffer.read();
    let packet: Omit<Packet, "variant">;
    switch (variant) {
        case Variant.HELLO:
            packet = {
                major: buffer.read(),
                minor: buffer.read(),
                features: buffer.read(),
            };
            break;
        case Variant.MESSAGE:
            packet = {
                type: buffer.read(),
                message: string.char(...buffer.readRemaining()),
            };
            break;
        case Variant.START_SHELL_SESSION:
            packet = {
                sessionId: buffer.readInt(),
                features: buffer.read(),
                width: buffer.readShort(),
                height: buffer.readShort(),
            };
            break;
        case Variant.INTERRUPT_SHELL_SESSION:
        case Variant.END_SHELL_SESSION:
            packet = {
                sessionId: buffer.readInt(),
            };
            break;
        case Variant.KEY_INPUT:
            packet = {
                sessionId: buffer.readInt(),
                keycode: buffer.readShort(),
                pressed: buffer.read() == 0x01
            };
            break;
        case Variant.KEYCODE_INPUT:
            packet = {
                sessionId: buffer.readInt(),
                keycodes: toShortArray(buffer.readRemaining())
            };
            break;
        case Variant.CHAR_INPUT:
            packet = {
                sessionId: buffer.readInt(),
                key: string.char(...buffer.readRemaining())
            };
            break;
        case Variant.RESIZE:
            packet = {
                sessionId: buffer.readInt(),
                width: buffer.readShort(),
                height: buffer.readShort()
            }
            break;
        case Variant.MOUSE_INPUT:
            const sessionId = buffer.readInt();
            const button = buffer.read();
            const x = buffer.readInt();
            const y = buffer.readInt();
            const flags = buffer.read();
            packet = {
                sessionId: sessionId,
                button: button,
                x: x,
                y: y,
                shift:      (flags & 0b00010000) > 0,
                meta:       (flags & 0b00001000) > 0,
                control:    (flags & 0b00000100) > 0,
                dragged:    (flags & 0b00000010) > 0,
                down:       (flags & 0b00000001) > 0,
            };
            break;
        default:
            error(`Invalid packet variant byte ${variant}`);
    }
    return { variant: variant, ...packet } as Packet;
}