package computer.gingershaped.turtleshell.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.Channel

// Sourced from https://www.glfw.org/docs/latest/group__keys.html
// You don't want to know how long this took
enum class GLFW(val code: UShort) {
    SPACE           ( 32u),

    APOSTROPHE      ( 39u),

    COMMA           ( 44u),
    MINUS           ( 45u),
    PERIOD          ( 46u),
    SLASH           ( 47u),
    ZERO            ( 48u),
    ONE             ( 49u),
    TWO             ( 50u),
    THREE           ( 51u),
    FOUR            ( 52u),
    FIVE            ( 53u),
    SIX             ( 54u),
    SEVEN           ( 55u),
    EIGHT           ( 56u),
    NINE            ( 57u),

    SEMICOLON       ( 59u),

    EQUAL           ( 61u),

    A               ( 65u),
    B               ( 66u),
    C               ( 67u),
    D               ( 68u),
    E               ( 69u),
    F               ( 70u),
    G               ( 71u),
    H               ( 72u),
    I               ( 73u),
    J               ( 74u),
    K               ( 75u),
    L               ( 76u),
    M               ( 77u),
    N               ( 78u),
    O               ( 79u),
    P               ( 80u),
    Q               ( 81u),
    R               ( 82u),
    S               ( 83u),
    T               ( 84u),
    U               ( 85u),
    V               ( 86u),
    W               ( 87u),
    X               ( 88u),
    Y               ( 89u),
    Z               ( 90u),

    LEFT_BRACKET    ( 91u),
    BACKSLASH       ( 92u),
    RIGHT_BRACKET   ( 93u),

    GRAVE_ACCENT    ( 96u),

    WORLD_1         (161u),
    WORLD_2         (162u),

    ESCAPE          (256u),
    ENTER           (257u),
    TAB             (258u),
    BACKSPACE       (259u), 
    INSERT          (260u),
    DELETE          (261u),
    RIGHT           (262u),
    LEFT            (263u),
    DOWN            (264u),
    UP              (265u),
    PAGE_UP         (266u),
    PAGE_DOWN       (267u),
    HOME            (268u),
    END             (269u),

    CAPS_LOCK       (280u),
    SCROLL_LOCK     (281u),
    NUM_LOCK        (282u),
    PRINT_SCREEN    (283u),
    PAUSE           (284u),

    F1              (290u),
    F2              (291u),
    F3              (292u),
    F4              (293u),
    F5              (294u),
    F6              (295u),
    F7              (296u),
    F8              (297u),
    F9              (298u),
    F10             (299u),
    F11             (300u),
    F12             (301u),
    F13             (302u),
    F14             (303u),
    F15             (304u),
    F16             (305u),
    F17             (306u),
    F18             (307u),
    F19             (308u),
    F20             (309u),
    F21             (310u),
    F22             (311u),
    F23             (312u),
    F24             (313u),
    F25             (314u),

    KP_0            (320u),
    KP_1            (321u),
    KP_2            (322u),
    KP_3            (323u),
    KP_4            (324u),
    KP_5            (325u),
    KP_6            (326u),
    KP_7            (327u),
    KP_8            (328u),
    KP_9            (329u),
    KP_DECIMAL      (330u),
    KP_DIVIDE       (331u),
    KP_MULTIPLY     (332u),
    KP_SUBTRACT     (333u),
    KP_ADD          (334u),
    KP_ENTER        (335u),
    KP_EQUAL        (336u),

    LEFT_SHIFT      (340u),
    LEFT_CONTROL    (341u),
    LEFT_ALT        (342u),
    LEFT_SUPER      (343u),
    RIGHT_SHIFT     (344u),
    RIGHT_CONTROL   (345u),
    RIGHT_ALT       (346u),
    RIGHT_SUPER     (347u),
    MENU            (348u);

    companion object {
        val byCode = GLFW.entries.associateBy { it.code }
    }
}