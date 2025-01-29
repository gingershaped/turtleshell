package computer.gingershaped.turtleshell.terminal

import computer.gingershaped.turtleshell.util.setOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile

private suspend fun SendChannel<List<UShort>>.send(code: GLFW) = send(code.code)
private suspend fun SendChannel<List<UShort>>.send(code: UShort) = send(listOf(code))
@OptIn(ExperimentalUnsignedTypes::class)
private suspend fun SendChannel<List<UShort>>.send(vararg codes: UShort) = send(codes.toList())
private suspend fun SendChannel<List<UShort>>.send(vararg codes: GLFW) = send(codes.map { it.code })

sealed class Input {
    data class Keycodes(val keycodes: List<GLFW>) : Input()
    data class Character(val char: Char) : Input()
    data class Mouse(val button: Button, val modifiers: Modifiers, val x: Int, val y: Int, val dragged: Boolean, val down: Boolean) : Input() {
        init {
            check(x >= 0)
            check(y >= 0)
        }
        data class Modifiers(val shift: Boolean, val meta: Boolean, val control: Boolean)
        enum class Button(val number: UByte) {
            LEFT(0x00u), MIDDLE(0x01u), RIGHT(0x02u), SCROLL_UP(0x03u), SCROLL_DOWN(0x04u), OTHER(0x05u)
        }
    }
    data object Interrupt : Input()
}

@kotlinx.coroutines.ExperimentalCoroutinesApi
fun CoroutineScope.generateInputs(stdin: ReceiveChannel<Char>) = produce {
    with(Ansi) {
        for (char in stdin) {
            when (char) {
                ESCAPE -> when (stdin.receive()) {
                    '[' -> when (val escape = stdin.receive()) {
                        in 'A'..'D' -> listOf(Ansi.Direction.byCode[escape]!!.glfw!!)
                        '<' -> {
                            val (buttonString, xString, yString) = stdin.receiveAsFlow().transformWhile {
                                emit(it)
                                it !in "Mm"
                            }.toList().joinToString("").split(';')
                            val buttons = buttonString.toInt()
                            send(Input.Mouse(
                                when {
                                    0b10000000 setOn buttons -> Input.Mouse.Button.OTHER
                                    0b01000000 setOn buttons -> when(0b11 and buttons) {
                                        0 -> Input.Mouse.Button.SCROLL_UP
                                        1 -> Input.Mouse.Button.SCROLL_DOWN
                                        else -> Input.Mouse.Button.OTHER
                                    }
                                    else -> when(0b11 and buttons) {
                                        0 -> Input.Mouse.Button.LEFT
                                        1 -> Input.Mouse.Button.MIDDLE
                                        2 -> Input.Mouse.Button.RIGHT
                                        else -> Input.Mouse.Button.OTHER
                                    }
                                },
                                Input.Mouse.Modifiers(
                                    0b00000100 setOn buttons,
                                    0b00001000 setOn buttons,
                                    0b00010000 setOn buttons,
                                ),
                                xString.toInt() - 1,
                                yString.dropLast(1).toInt() - 1,
                                0b00100000 setOn buttons,
                                yString.endsWith('M')
                            ))
                            listOf()
                        }
                        else -> listOf()
                    }
                    else -> listOf(GLFW.ESCAPE)
                }
    
                // Special characters
                '\t' -> listOf(GLFW.TAB)
                '\b', '\u007f' -> listOf(GLFW.BACKSPACE)
                '\n', '\r' -> listOf(GLFW.ENTER)
                // Ctrl-<something>
                in '\u0000'..'\u001f' -> {
                    if (char == '\u0014') { // Ctrl-T
                        send(Input.Interrupt)
                        listOf()
                    } else {
                        listOf(GLFW.LEFT_CONTROL, GLFW.byCode[(64 + char.code).toUShort()]!!)
                    }
                }
    
                // Lowercase 7-bit printable ASCII, except for letters, maps directly
                ' ', '\'', in ','..'9', ';', '=', '`', '[', '\\', ']' -> listOf(GLFW.byCode[char.code.toUShort()]!!)
                // Lowercase letters map to their uppercase ASCII code
                in 'a'..'z' -> listOf(GLFW.byCode[(char.code - 32).toUShort()]!!)
                // Uppercase letters are Shift and their code
                in 'A'..'Z' -> listOf(GLFW.LEFT_SHIFT, GLFW.byCode[char.code.toUShort()]!!)
    
                // All the other stuff that requires Shift on a US keyboard
                '~' -> listOf(GLFW.LEFT_SHIFT, GLFW.GRAVE_ACCENT)
                '!' -> listOf(GLFW.LEFT_SHIFT, GLFW.ONE)
                '@' -> listOf(GLFW.LEFT_SHIFT, GLFW.TWO)
                '#' -> listOf(GLFW.LEFT_SHIFT, GLFW.THREE)
                '$' -> listOf(GLFW.LEFT_SHIFT, GLFW.FOUR)
                '%' -> listOf(GLFW.LEFT_SHIFT, GLFW.FIVE)
                '^' -> listOf(GLFW.LEFT_SHIFT, GLFW.SIX)
                '&' -> listOf(GLFW.LEFT_SHIFT, GLFW.SEVEN)
                '*' -> listOf(GLFW.LEFT_SHIFT, GLFW.EIGHT)
                '(' -> listOf(GLFW.LEFT_SHIFT, GLFW.NINE)
                ')' -> listOf(GLFW.LEFT_SHIFT, GLFW.ZERO)
                '_' -> listOf(GLFW.LEFT_SHIFT, GLFW.MINUS)
                '+' -> listOf(GLFW.LEFT_SHIFT, GLFW.EQUAL)
                ':' -> listOf(GLFW.LEFT_SHIFT, GLFW.SEMICOLON)
                '{' -> listOf(GLFW.LEFT_SHIFT, GLFW.LEFT_BRACKET)
                '|' -> listOf(GLFW.LEFT_SHIFT, GLFW.BACKSLASH)
                '}' -> listOf(GLFW.LEFT_SHIFT, GLFW.RIGHT_BRACKET)
                '<' -> listOf(GLFW.LEFT_SHIFT, GLFW.COMMA)
                '>' -> listOf(GLFW.LEFT_SHIFT, GLFW.PERIOD)
                '?' -> listOf(GLFW.LEFT_SHIFT, GLFW.SLASH)
                '"' -> listOf(GLFW.LEFT_SHIFT, GLFW.APOSTROPHE)
                else -> listOf()
            }.let {
                if (it.isNotEmpty()) {
                    send(Input.Keycodes(it))
                }
            }
            if (char.code in 32..126 || char.code in 160..255) {
                send(Input.Character(char))
            }
        }
    }
}