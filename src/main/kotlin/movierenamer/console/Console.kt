package movierenamer.console

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Console {
    private val lock = Any()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            val (stdout, stderr) = if (isWindows()) {
                WindowsStdio.bind()
            } else {
                utf8Stdio()
            }
            System.setOut(stdout)
            System.setErr(stderr)
            installed = true
        }
    }

    fun info(message: String) {
        println("${now()} [INFO] $message")
    }

    fun error(message: String) {
        System.err.println("${now()} [ERROR] $message")
    }

    private fun now(): String = LocalTime.now().format(timeFormatter)
}

internal fun isWindows(): Boolean {
    return System.getProperty("os.name").orEmpty().lowercase().contains("win")
}

internal fun utf8PrintStream(descriptor: FileDescriptor): PrintStream {
    return PrintStream(FileOutputStream(descriptor), true, StandardCharsets.UTF_8)
}

private fun utf8Stdio(): Pair<PrintStream, PrintStream> {
    return utf8PrintStream(FileDescriptor.out) to utf8PrintStream(FileDescriptor.err)
}
