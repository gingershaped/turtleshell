import sys
import io
import tty
import termios

try:
    print("\x1b[12h", end="")
    tty.setcbreak(sys.stdin.fileno(), termios.TCSANOW)
    with io.open(sys.stdin.fileno(), "rb", closefd=False, buffering=0) as stdin:
        while True:
            b = stdin.read(1)[0]
            print(hex(b)[2:].rjust(4), repr(chr(b)))
finally:
    pass