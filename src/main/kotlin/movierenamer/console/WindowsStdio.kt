package movierenamer.console

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object WindowsStdio {
    private const val STD_OUTPUT_HANDLE = -11
    private const val STD_ERROR_HANDLE = -12
    private const val CP_UTF8 = 65001

    fun bind(): Pair<PrintStream, PrintStream> {
        return try {
            val kernel32 = Kernel32.load()
            kernel32.SetConsoleCP(CP_UTF8)
            kernel32.SetConsoleOutputCP(CP_UTF8)
            utf8PrintStream(kernel32, STD_OUTPUT_HANDLE, FileDescriptor.out) to
                utf8PrintStream(kernel32, STD_ERROR_HANDLE, FileDescriptor.err)
        } catch (_: Throwable) {
            utf8PrintStream(FileDescriptor.out) to utf8PrintStream(FileDescriptor.err)
        }
    }

    private fun utf8PrintStream(
        kernel32: Kernel32,
        stdHandle: Int,
        fallback: FileDescriptor,
    ): PrintStream {
        val handle = kernel32.GetStdHandle(stdHandle)
        if (handle == null || handle.isInvalid() || !kernel32.isConsole(handle)) {
            return utf8PrintStream(fallback)
        }
        return PrintStream(WindowsConsoleOutputStream(kernel32, handle), true, StandardCharsets.UTF_8)
    }

    private fun Kernel32.isConsole(handle: Pointer?): Boolean {
        if (handle == null) return false
        return GetConsoleMode(handle, IntByReference())
    }

    private fun Pointer.isInvalid(): Boolean {
        val value = Pointer.nativeValue(this)
        return value == 0L || value == -1L
    }
}

private interface Kernel32 : Library {
    fun GetStdHandle(nStdHandle: Int): Pointer?
    fun GetConsoleMode(hConsoleHandle: Pointer, lpMode: IntByReference): Boolean
    fun SetConsoleOutputCP(wCodePageID: Int): Boolean
    fun SetConsoleCP(wCodePageID: Int): Boolean
    fun WriteConsoleW(
        hConsoleOutput: Pointer,
        lpBuffer: CharArray,
        nNumberOfCharsToWrite: Int,
        lpNumberOfCharsWritten: IntByReference,
        lpReserved: Pointer?,
    ): Boolean

    companion object {
        fun load(): Kernel32 = Native.load("kernel32", Kernel32::class.java)
    }
}

private class WindowsConsoleOutputStream(
    private val kernel32: Kernel32,
    private val handle: Pointer,
) : OutputStream() {
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    @Synchronized
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val input = ByteBuffer.wrap(b, off, len)
        val output = CharBuffer.allocate(len + 1)
        while (input.hasRemaining()) {
            val result = decoder.decode(input, output, false)
            output.flip()
            if (output.hasRemaining()) {
                writeChars(output)
            }
            output.clear()
            when {
                result.isUnderflow -> break
                result.isOverflow -> continue
                result.isError -> result.throwException()
            }
        }
    }

    private fun writeChars(buffer: CharBuffer) {
        val chars = CharArray(buffer.remaining())
        buffer.get(chars)
        var offset = 0
        val written = IntByReference()
        while (offset < chars.size) {
            val chunk = minOf(4096, chars.size - offset)
            val slice = if (offset == 0 && chunk == chars.size) {
                chars
            } else {
                chars.copyOfRange(offset, offset + chunk)
            }
            if (!kernel32.WriteConsoleW(handle, slice, chunk, written, null)) {
                throw IOException("WriteConsoleW failed while printing Unicode text")
            }
            offset += chunk
        }
    }
}
