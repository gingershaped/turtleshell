export default class Buffer {
    private data: number[];
    private _position: number = 0;
    private _limit: number = Infinity;
    
    constructor() {
        this.data = [];
    }

    get position() {
        return this._position;
    }
    get limit() {
        return this._limit;
    }
    setLimit(limit: number) {
        if (limit < 0) {
            error("Limit must be positive!");
        }
        this._limit = limit;
        return this;
    }
    seek(position: number) {
        if (position < 0) {
            error("Limit must be positive!")
        }
        if (position >= this.limit) {
            error("Position must be less than limit!")
        }
        this._position = position;
        return this;
    }

    get(position: number) {
        if (position < 0) {
            error("Position must be positive!");
        }
        if (position >= this.data.length) {
            error("Buffer underflow!");
        }
        return this.data[position];
    }

    writeTo(index: number, byte: number) {
        if (byte < 0) {
            error("Bytes cannot be negative!")
        }
        if (byte > 0xff) {
            error("Byte too large!")
        }
        if (index < 0) {
            error("Index cannot be negative!")
        }
        if (index >= this._limit) {
            error("Cannot write past limit!")
        }
        if (index >= this.data.length) {
            const oldLength = this.data.length;
            this.data.length = index + 1;
            // Apparently oldLength - 1 is wrong? Must be a weird implementation thing
            this.data.fill(0, oldLength, this.data.length);
        }
        this.data[index] = byte;
        return this;
    }

    read() {
        if (this._position >= this._limit) {
            error("Cannot read past limit!");
        }
        return this.get(this._position++);
    }

    readShort() {
        const high = this.read();
        const low = this.read();
        return (high << 8) + low;
    }

    readInt() {
        let int = 0;
        for (let byte = 0; byte < 4; byte++) {
            int <<= 8;
            int += this.read();
        }
        return int;
    }

    readRemaining() {
        const originalPosition = this._position;
        this._position = this._limit;
        return this.data.slice(originalPosition, this._position);
    }

    write(...bytes: number[]) {
        for (const byte of bytes) {
            this.writeTo(this._position++, byte);
        }
        return this;
    }

    writeString(str: string) {
        for (let i = 0; i < str.length; i++) {
            this.write(string.byte(str, i + 1));
        }
        return this;
    }

    flip() {
        this._limit = this._position;
        this._position = 0;
        return this;
    }
    reset() {
        this._limit = Infinity;
        this._position = 0;
        return this;
    }

    hexdump() {
        let dump = "";
        for (let byte = 0; byte < this._limit; byte++) {
            dump += string.format("%x ", this.data[byte]);
        }
        return dump;
    }

    static wrap(string: string) {
        return new Buffer().writeString(string).flip();
    }
}