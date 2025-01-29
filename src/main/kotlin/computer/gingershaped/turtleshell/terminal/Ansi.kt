package computer.gingershaped.turtleshell.terminal

import com.github.ajalt.colormath.Color
import kotlin.math.absoluteValue

object Ansi {
    const val ESCAPE = '\u001b'
    const val CSI: String = "$ESCAPE["
    const val OSC: String = "$ESCAPE]"

    enum class Direction(val code: Char, val glfw: GLFW? = null) {
        UP('A', GLFW.UP), DOWN('B', GLFW.DOWN), RIGHT('C', GLFW.RIGHT), LEFT('D', GLFW.LEFT),
        START_DOWN('E'), START_UP('F'),
        COLUMN('G'), HOME('H');

        companion object {
            val byCode = Direction.entries.associateBy { it.code }
        }
    }
    enum class Level {
        ANSI16, ANSI256, TRUECOLOR
    }
    enum class ColorType(val offset: Int) {
        FOREGROUND(30), BACKGROUND(40)
    }
    enum class Mode(val code: Int) {
        CURSOR_VISIBLE(25), ALTERNATE_BUFFER(1049), ALTERNATE_SCROLL(1007), MOUSE_TRACKING(1002), SGR_COORDS(1006)
    }
    enum class EraseType(val code: Char) {
        DISPLAY('J'), LINE('K')
    }
    enum class EraseRegion(val code: Int) {
        CURSOR_TO_END(0), CURSOR_TO_BEGINNING(1), FULL_CLEAR(2)
    }

    fun cursorTo(x: Int, y: Int): String = CSI + y.toString() + ";" + x.toString() + "H"
    fun cursorMove(direction: Direction, position: Int): String =
        CSI + position.also { check(it > 0) }.toString() + direction.code
    fun scroll(distance: Int) = when {
        distance < 0 -> CSI + distance.absoluteValue + "T"
        distance == 0 -> ""
        else -> CSI + distance + "S"
    }

    fun setColor(color: Color, type: ColorType, level: Level) =
        CSI + color.ansify(level, type).joinToString(";") + "m"

    fun setModes(vararg modes: Mode, enabled: Boolean) =
        CSI + "?" + modes.joinToString(";") { it.code.toString() } + if (enabled) "h" else "l"

    fun erase(type: EraseType, region: EraseRegion) =
        CSI + region.code.toString() + type.code

}

fun Color.ansify(level: Ansi.Level, type: Ansi.ColorType = Ansi.ColorType.FOREGROUND) = when (level) {
    Ansi.Level.ANSI16 -> listOf((type.offset - 30) + toAnsi16().code)
    Ansi.Level.ANSI256 -> listOf(type.offset + 8, 5, toAnsi256().code)
    Ansi.Level.TRUECOLOR -> toSRGB().clamp().run { listOf(type.offset + 8, 2, redInt, greenInt, blueInt) }
}