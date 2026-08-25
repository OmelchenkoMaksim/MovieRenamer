package movierenamer.console

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Console {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun useUtf8() {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8))
    }

    fun info(message: String) {
        println("${now()} [INFO] $message")
    }

    fun error(message: String) {
        System.err.println("${now()} [ERROR] $message")
    }

    private fun now(): String = LocalTime.now().format(timeFormatter)
}
