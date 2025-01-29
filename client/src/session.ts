import Buffer from "./packets/buffer"

// Magic!
export function shortify(number: number): [number, number] {
    assert(number >= 0);
    assert(number <= 65535);
    return [(number & 0xff00) >> 8, number & 0x00ff];
}
export function intify(number: number): [number, number, number, number] {
    assert(number >= 0);
    assert(number <= 4294967295);
    const bytes = [];
    for (let place = 3; place >= 0; place--) {
        bytes.push((number >> (8 * place)) & 0xff)
    }
    return bytes as [number, number, number, number];
}

const BLIT              = 0x00;
const SCROLL            = 0x01;
const CURSOR_BLINK      = 0x03;
const CURSOR_POS        = 0x04;
const FILL_LINE         = 0x05;
const FILL_SCREEN       = 0x06;

const DRAW              = 0x00;
const PALETTE           = 0x01;
const END_SESSION       = 0x20;

const BASE_16 = "0123456789abcdef";

export class SessionTerminal implements ITerminal {
    private fg = 0;
    private bg = 0;
    private width: number;
    private height: number;
    private cursor = { x: 0, y: 0 };
    private blink = true;
    private palette: [number, number, number][] = [];
    private closed = false;
    private drawBuffer = new Buffer();

    constructor(readonly ws: WebSocket, readonly id: number, width: number, height: number) {
        this.width = width;
        this.height = height;
        for (let i = 1; i <= 16; i++) {
            this.palette.push(term.nativePaletteColor(i));
        }
    }

    private send(buffer: Buffer) {
        if (this.closed) {
            error("Cannot use a closed terminal session!");
        }
        buffer.flip();
        this.ws.send(string.char(...buffer.readRemaining()), true);
        buffer.reset();
    }

    private buffer(message: number) {
        return new Buffer().write(message, ...intify(this.id))
    }

    private colorToPalette(color: number) {
        if (color < 0 || color > 0xffff) {
            error(`Attempted to convert an invalid color constant ${color}!`);
        }
        return math.floor(math.log(color, 2));
    }

    close() {
        if (this.closed) {
            error("Tried to close an already closed session!");
        }
        this.send(this.buffer(END_SESSION));
        this.closed = true;
    }

    resize(width: number, height: number) {
        this.width = width;
        this.height = height;
    }

    flush() {
        this.drawBuffer.flip()
        this.send(this.buffer(DRAW).write(...this.drawBuffer.readRemaining()));
        this.drawBuffer.reset();
    }

    write(thing: any): void {
        const text = tostring(thing)
        this.blit(text, BASE_16[this.fg].repeat(text.length), BASE_16[this.bg].repeat(text.length));
    }
    scroll(y: number): void {
        this.drawBuffer.write(SCROLL, ...shortify(math.abs(y)), y >= 0 ? 0 : 1);
    }
    getCursorPos(): LuaMultiReturn<[number, number]> {
        return $multi(this.cursor.x + 1, this.cursor.y + 1);
    }
    setCursorPos(x: number, y: number): void {
        x -= 1;
        y -= 1;
        this.cursor = { x: x, y: y };
        if (x >= 0 && y >= 0 && x < this.width && y <= this.width) {
            this.drawBuffer.write(CURSOR_POS, ...shortify(x), ...shortify(y));
        }
    }
    getCursorBlink(): boolean {
        return this.blink;
    }
    setCursorBlink(blink: boolean) {
        this.blink = blink;
        this.drawBuffer.write(CURSOR_BLINK, blink ? 0x01 : 0x00);
    }
    getSize(): LuaMultiReturn<[number, number]> {
        return $multi(this.width, this.height);
    }
    clear(): void {
        this.drawBuffer.write(FILL_SCREEN, this.bg);
    }
    clearLine(): void {
        this.drawBuffer.write(FILL_LINE, ...shortify(this.cursor.y), this.bg);
    }
    getTextColor(): number {
        return Math.pow(2, this.fg);
    }
    setTextColor(color: number): void {
        this.fg = this.colorToPalette(color);
    }
    getBackgroundColor(): number {
        return Math.pow(2, this.bg);
    }
    setBackgroundColor(color: number): void {
        this.bg = this.colorToPalette(color);
    }
    isColor(): boolean {
        return true;
    }
    blit(text: string, textColor: string, backgroundColor: string): void {
        if (text.length != textColor.length) {
            error(`Text color string is wrong length (${textColor.length}), should be ${text.length}!`)
        }
        if (text.length != backgroundColor.length) {
            error(`Background color string is wrong length (${backgroundColor.length}), should be ${text.length}!`)
        }
        if (text.length == 0 || this.cursor.y < 0 || this.cursor.y >= this.height) {
            return;
        }

        // The protocol can handle text overflowing off the right edge,
        // but we need to clip left overflow ourselves
        if (this.cursor.x < 0) {
            const leftClipAmount = Math.max(0, -this.cursor.x);
            textColor = string.lower(textColor).slice(leftClipAmount);
            backgroundColor = string.lower(backgroundColor).slice(leftClipAmount);
            if (text.length - leftClipAmount <= 0) {
                // We're still offscreen
                this.cursor.x += leftClipAmount;
                return;
            }
            text = text.slice(leftClipAmount);
            assert(text.length > 0);
            this.cursor.x = 0;
        }
        
        this.drawBuffer.write(BLIT);
        this.drawBuffer.write(...shortify(this.cursor.x), ...shortify(this.cursor.y));

        this.drawBuffer.write(...intify(text.length))
        this.drawBuffer.writeString(text);
        for (let i = 0; i < text.length; i++) {
            const fg = BASE_16.indexOf(textColor[i]);
            const bg = BASE_16.indexOf(backgroundColor[i]);
            assert(fg >= 0);
            assert(bg >= 0);
            this.drawBuffer.write((fg << 4) + bg);
        }

        this.cursor.x += text.length;
    }
    setPaletteColor(index: number, r: number, g: number, b: number): void
    setPaletteColor(index: number, color: number): void
    setPaletteColor(index: number, r: number, g?: number, b?: number): void {
        index = this.colorToPalette(index);
        if (index < 0 || index > 15) {
            error(`Index ${index} is invalid!`)
        }
        assert(r >= 0);
        let color: number;
        if (g != undefined && b != undefined) {
            assert(g >= 0);
            assert(b >= 0);
            assert(r <= 0xff);
            assert(g <= 0xff);
            assert(b <= 0xff);
            color = (r << 16) + (g << 8) + b;
        } else {
            assert(r <= 0xffffff);
            color = r;
        }
        this.palette[index] = [(color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff];
        this.send(this.buffer(PALETTE).write(...intify((index << 24) + color)));
    }
    getPaletteColor(index: number): LuaMultiReturn<[number, number, number]> {
        index = this.colorToPalette(index);
        return $multi(...this.palette[index]);
    }

    getTextColour = this.getTextColor;
    setTextColour = this.setTextColor;
    getBackgroundColour = this.getBackgroundColor;
    setBackgroundColour = this.setBackgroundColor;
    isColour = this.isColor;
    setPaletteColour = this.setPaletteColor;
    getPaletteColour = this.getPaletteColor;

    // Not sure what all this is? Possibly for an emulator of some sort
    private noimpl = (name) => () => error(`Method ${name} not implemented`);
    getGraphicsMode() {
        return false;
    } 
    setGraphicsMode(mode: number | boolean) {
        if (!(mode == false || mode == 0)) {
            error(`Graphics modes are not supported (tried to set to ${mode})`);
        }
    }
    getPixel = this.noimpl("getPixel");
    setPixel = this.noimpl("setPixel");
    getPixels = this.noimpl("getPixels");
    setPixels = this.noimpl("setPixels");
    getFrozen = this.noimpl("getFrozen");
    setFrozen = this.noimpl("setFrozen");
}
