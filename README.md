# Turtleshell: SSH for ComputerCraft

Turtleshell is a relay server which makes it possible to use [CC: Tweaked](https://tweaked.cc) computers over a SSH session from the comfort of your desktop.

![A screenshot of a GNOME Terminal window, displaying the default ComputerCraft shell prompt.](https://hedgedoc.ginger.services/uploads/d08ee341-d267-485b-b850-5f3325e15f1b.png)

## Supported features
* Text coloring, formatting, and cursor control
* Mouse input
* Multiple SSH sessions into one computer

## Planned features
* Support for SFTP to allow remote filesystem access

## Self-hosting
Turtleshell is written in Kotlin (for the relay server) and TypeScript (for the client), and the client is transpiled to Lua using the excellent [TypeScriptToLua](https://typescripttolua.github.io/) project. Compilation therefore requires an installation of Node.js, in addition to a JDK. To build, simply run `./gradlew build`; the client will be compiled and bundled into the JAR by Gradle, and a runnable JAR artifact will be available at `build/libs/computer.gingershaped.turtleshell.turtleshell-all.jar`.
