@file:OptIn(ExperimentalUnsignedTypes::class)
package computer.gingershaped.turtleshell.terminal

import com.github.ajalt.colormath.Color
import com.github.ajalt.colormath.model.RGB
import computer.gingershaped.turtleshell.util.ColoredString
import computer.gingershaped.turtleshell.util.nybbles
import kotlin.math.absoluteValue

class Palette private constructor(private val colors: MutableMap<Int, Color>) {
    constructor() : this(DEFAULT_COLORS.toMutableMap())

    operator fun get(index: Int): Color {
        check(index in 0..15)
        return colors[index]!!
    }

    operator fun set(index: Int, color: Color) {
        check(index in 0..15)
        colors[index] = color
    }

    fun reset() {
        colors.putAll(DEFAULT_COLORS)
    }

    companion object {
        private val DEFAULT_COLORS = mapOf<Int, Color>(
            0x0 to RGB("#f0f0f0"),
            0x1 to RGB("#f2b233"),
            0x2 to RGB("#e57fd8"),
            0x3 to RGB("#99b2f2"),
            0x4 to RGB("#dede6c"),
            0x5 to RGB("#7fcc19"),
            0x6 to RGB("#f2b2cc"),
            0x7 to RGB("#4c4c4c"),
            0x8 to RGB("#999999"),
            0x9 to RGB("#4c99b2"),
            0xa to RGB("#b266e5"),
            0xb to RGB("#3366cc"),
            0xc to RGB("#7f664c"),
            0xd to RGB("#57a64e"),
            0xe to RGB("#cc4c4c"),
            0xf to RGB("#111111"),
        )
    }
}

data class Cell(val char: Char, val fg: UInt, val bg: UInt) {
    constructor(char: Char, colors: UByte) : this(char, colors.nybbles.first, colors.nybbles.second)
    init {
        check(fg in 0u..15u) { "Foreground must be a valid palette index" }
        check(bg in 0u..15u) { "Background must be a valid palette index" }
    }
}

interface TerminalBuffer : Iterable<Cell> {
    var width: Int
    var height: Int

    fun resize(width: Int, height: Int)

    fun indexOf(x: Int, y: Int): Int {
        check(x in 0..<width)
        check(y in 0..<height)
        return (y * width) + x
    }

    operator fun get(index: Int): Cell
    operator fun get(x: Int, y: Int): Cell
}

const val EMPTY_CHAR = ' '
const val EMPTY_COLOR: UByte = 0x0fu

@OptIn(ExperimentalUnsignedTypes::class)
class ArrayTerminalBuffer(width: Int, height: Int) : TerminalBuffer {
    private var _width = width
    private var _height = height
    override var width
        set(value) {
            resize(value, _height)
        }
        get() = _width
    override var height
        set(value) {
            resize(_width, value)
        }
        get() = _height
    var content = CharArray(width * height)
        private set
    var colors = UByteArray(width * height)
        private set

    init {
        content.fill(EMPTY_CHAR)
        colors.fill(EMPTY_COLOR)
    }

    override fun resize(width: Int, height: Int) {
        check(width > 0)
        check(height > 0)
        _width = width
        _height = height
        content = CharArray(width * height)
        colors = UByteArray(width * height)
        content.fill(EMPTY_CHAR)
        colors.fill(EMPTY_COLOR)
    }

    override operator fun iterator() = (0..<width * height).map(::get).iterator()
    override operator fun get(index: Int) = Cell(content[index], colors[index])
    override operator fun get(x: Int, y: Int) = get(indexOf(x, y))

    operator fun set(index: Int, value: Cell) {
        content[index] = value.char
        colors[index] = ((value.fg shl 4) + value.bg).toUByte()
    }
    operator fun set(x: Int, y: Int, value: Cell) = set(indexOf(x, y), value)

    fun scroll(distance: Int) {
        when {
            distance.absoluteValue > height -> {
                content.fill(EMPTY_CHAR)
                colors.fill(EMPTY_COLOR)
            }
            distance > 0 -> {
                content.copyInto(content, 0, indexOf(0, distance), content.size)
                content.fill(EMPTY_CHAR, indexOf(0, height - distance), content.size)
                colors.copyInto(colors, 0, indexOf(0, distance), content.size)
                colors.fill(EMPTY_COLOR, indexOf(0, height - distance), content.size)
            }
            distance < 0 -> {
                val absDist = distance.absoluteValue
                content.copyInto(content, indexOf(0, absDist), 0, indexOf(0, height - absDist))
                content.fill(EMPTY_CHAR, 0, indexOf(0, absDist))
                colors.copyInto(colors, indexOf(0, absDist), 0, indexOf(0, height - absDist))
                colors.fill(EMPTY_COLOR, 0, indexOf(0, absDist))
            }
        }
    }

    fun fillLine(line: Int, char: Char, color: UByte) {
        check(line in 0..<height)
        content.fill(char, indexOf(0, line), indexOf(width-1, line))
        colors.fill(color, indexOf(0, line), indexOf(width-1, line))
    }

    fun fill(char: Char, color: UByte) {
        content.fill(char)
        colors.fill(color)
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
class AnsiTerminal(width: Int, height: Int, private val level: Ansi.Level) {
    private val palette = Palette()
    private val buffer = ArrayTerminalBuffer(width, height)
    var width by buffer::width
        private set
    var height by buffer::height
        private set
    private var cursorX = 0
    private var cursorY = 0
    private var currentFg = EMPTY_COLOR.nybbles.first
    private var currentBg = EMPTY_COLOR.nybbles.second

    fun blit(string: ColoredString, startX: Int, startY: Int) = StringBuilder().apply {
        check(startX >= 0)
        check(startY >= 0)
        if (startX >= width || startY >= height) {
            return ""
        }
        with(Ansi) {
            // append(setColor(palette[previousFg.toInt()], palette[previousBg.toInt()], level))
            val changedCells = mutableListOf<Pair<Int, Cell>>()
            val charsToWrite = string.length.coerceAtMost(width - startX)
            for ((index, cell) in (string.content zip string.colors).take(charsToWrite).withIndex()) {
                val x = startX + index
                val (currentChar, currentFg, currentBg) = buffer[x, startY]
                val (newChar, colors) = cell
                val (newFg, newBg) = colors.nybbles
                if (currentChar != newChar || currentFg != newFg || currentBg != newBg) {
                    changedCells += x to Cell(newChar, newFg, newBg)
                }
            }
            if (changedCells.isNotEmpty()) {
                append(cursorTo(startX + 1, startY + 1))
                cursorX = startX
                cursorY = startY
            }
            for ((x, cell) in changedCells) {
                if (cursorX != x) {
                    append(cursorMove(Ansi.Direction.COLUMN, x + 1))
                    cursorX = x
                }
                val (char, fg, bg) = cell
                if (fg != currentFg) {
                    append(setColor(palette[fg.toInt()], Ansi.ColorType.FOREGROUND, level))
                    currentFg = fg
                }
                if (bg != currentBg) {
                    append(setColor(palette[bg.toInt()], Ansi.ColorType.BACKGROUND, level))
                    currentBg = bg
                }
                append(char)
                buffer[x, startY] = Cell(char, fg, bg)
                cursorX++
            }
        }
    }.toString()

    fun redraw() = StringBuilder().apply {
        with(Ansi) {
            append(cursorTo(1, 1))
            var currentFg = currentFg
            var currentBg = currentBg
            append(setColor(palette[currentFg.toInt()], Ansi.ColorType.FOREGROUND, level))
            append(setColor(palette[currentBg.toInt()], Ansi.ColorType.BACKGROUND, level))
            for ((char, colors) in buffer.content zip buffer.colors) {
                val (fg, bg) = colors.nybbles
                if (fg != currentFg) {
                    append(setColor(palette[fg.toInt()], Ansi.ColorType.FOREGROUND, level))
                    currentFg = fg
                }
                if (bg != currentBg) {
                    append(setColor(palette[bg.toInt()], Ansi.ColorType.BACKGROUND, level))
                    currentBg = bg
                }
                append(char)
            }
            append(cursorTo(cursorX + 1, cursorY + 1))
        }
    }.toString()

    fun scroll(distance: Int): String {
        buffer.scroll(distance)
        return Ansi.scroll(distance)
    }

    fun resize(width: Int, height: Int) {
        buffer.resize(width, height)
    }

    fun fillLine(line: Int, color: Int): String {
        check(color in 0..<16)
        buffer.fillLine(line, '\u0000', color.toUByte())
        return Ansi.erase(Ansi.EraseType.LINE, Ansi.EraseRegion.FULL_CLEAR)
    }

    fun fillScreen(color: Int): String {
        check(color in 0..<16)
        buffer.fill('\u0000', color.toUByte())
        return Ansi.erase(Ansi.EraseType.DISPLAY, Ansi.EraseRegion.FULL_CLEAR)
    }

    fun setPaletteColor(index: Int, color: Color) = StringBuilder().apply {
        with(Ansi) {
            palette[index] = color
            for ((bufferIndex, cell) in (buffer.content zip buffer.colors).withIndex()) {
                val (char, colors) = cell
                val (fg, bg) = colors.nybbles
                if (fg.toInt() == index || bg.toInt() == index) {
                    append(cursorTo((bufferIndex % width) + 1, (bufferIndex / width) + 1))
                    append(setColor(palette[fg.toInt()], Ansi.ColorType.FOREGROUND, level))
                    append(setColor(palette[bg.toInt()], Ansi.ColorType.BACKGROUND, level))
                    append(char)
                }
            }
            append(cursorTo(cursorX + 1, cursorY + 1))
        }
    }.toString()

    fun moveCursor(x: Int, y: Int): String {
        cursorX = x.coerceIn(0..<width)
        cursorY = y.coerceIn(0..<height)
        return Ansi.cursorTo(x + 1, y + 1)
    }

    fun getPaletteColor(index: Int) = palette[index]
}