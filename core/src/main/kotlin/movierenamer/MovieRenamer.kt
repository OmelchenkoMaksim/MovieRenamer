package movierenamer

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.streams.asSequence

object MovieRenamer {
    fun run(args: Array<String>) {
        Console.install()

        val moviesDirectory = resolveMoviesDirectory(args) ?: return

        Console.info("Запуск Movie Renamer")
        Console.info("Рабочая директория: $moviesDirectory")

        if (!moviesDirectory.isDirectory()) {
            Console.error("Директория не найдена: $moviesDirectory")
            return
        }

        val videoFiles = try {
            VideoScanner.findVideoFiles(moviesDirectory)
        } catch (exception: Exception) {
            Console.error("Ошибка сканирования: ${exception.message}")
            return
        }

        if (videoFiles.isEmpty()) {
            Console.info("В директории нет поддерживаемых видеофайлов")
            return
        }

        Console.info("Найдено файлов: ${videoFiles.size}")
        println()

        videoFiles.forEachIndexed { index, file ->
            MediaPrinter.print(
                index = index,
                file = file,
                media = MediaParser.parse(file),
            )
        }

        println()
        Console.info("Анализ завершён")
        Console.info("Файлы не изменялись")
    }

    private fun resolveMoviesDirectory(args: Array<String>): Path? {
        if (args.size > 1) {
            Console.error("Использование: MovieRenamer [директория]")
            return null
        }

        val rawPath = args.firstOrNull() ?: Config.moviesDirectory.toString()
        return Path.of(rawPath).toAbsolutePath().normalize()
    }
}

// -----------------------------------------------------------------------------
// Config
// -----------------------------------------------------------------------------

object Config {
    val moviesDirectory: Path = Path.of("movies")

    val videoExtensions: Set<String> = setOf(
        "mkv",
        "mp4",
        "avi",
        "m4v",
        "mov",
        "webm",
        "ts",
        "m2ts",
    )
}

// -----------------------------------------------------------------------------
// Models
// -----------------------------------------------------------------------------

enum class MediaType(val displayName: String) {
    MOVIE("Фильм"),
    TV_EPISODE("Эпизод сериала"),
}

data class MediaInfo(
    val mediaType: MediaType,
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val resolution: String?,
    val source: String?,
    val editions: List<String>,
    val languages: List<String>,
)

// -----------------------------------------------------------------------------
// Console / Unicode
// -----------------------------------------------------------------------------

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
                utf8PrintStream(FileDescriptor.out) to utf8PrintStream(FileDescriptor.err)
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

private fun isWindows(): Boolean {
    return System.getProperty("os.name").orEmpty().lowercase().contains("win")
}

private fun utf8PrintStream(descriptor: FileDescriptor): PrintStream {
    return PrintStream(FileOutputStream(descriptor), true, StandardCharsets.UTF_8)
}

private object WindowsStdio {
    private const val STD_OUTPUT_HANDLE = -11
    private const val STD_ERROR_HANDLE = -12
    private const val CP_UTF8 = 65001

    fun bind(): Pair<PrintStream, PrintStream> {
        return try {
            val kernel32 = Kernel32.load()
            kernel32.SetConsoleCP(CP_UTF8)
            kernel32.SetConsoleOutputCP(CP_UTF8)
            bindHandle(kernel32, STD_OUTPUT_HANDLE, FileDescriptor.out) to
                bindHandle(kernel32, STD_ERROR_HANDLE, FileDescriptor.err)
        } catch (_: Throwable) {
            utf8PrintStream(FileDescriptor.out) to utf8PrintStream(FileDescriptor.err)
        }
    }

    private fun bindHandle(
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

// -----------------------------------------------------------------------------
// Scanner
// -----------------------------------------------------------------------------

object VideoScanner {
    fun findVideoFiles(directory: Path): List<Path> {
        return Files.walk(directory).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() }
                .filter { it.extension.lowercase() in Config.videoExtensions }
                .sortedBy { it.toString().lowercase() }
                .toList()
        }
    }
}

// -----------------------------------------------------------------------------
// Parser
// -----------------------------------------------------------------------------

object MediaParser {
    private val asciiFlags = setOf(RegexOption.IGNORE_CASE)

    private const val ASCII_START = """(?<![A-Za-z0-9])"""
    private const val ASCII_END = """(?![A-Za-z0-9])"""

    private val yearRegex = Regex("""$ASCII_START(19\d{2}|20\d{2})$ASCII_END""")
    private val seasonEpisodeRegex = Regex("""${ASCII_START}S(\d{1,2})E(\d{1,3})$ASCII_END""", asciiFlags)
    private val seasonRegex = Regex("""${ASCII_START}S\d{1,2}$ASCII_END""", asciiFlags)
    private val resolutionRegex = Regex(
        """$ASCII_START(480p|720p|1080p|2160p|4K|UHD)$ASCII_END""",
        asciiFlags,
    )
    private val leadingResolutionRegex = Regex(
        """^[\p{Zs}\s]*\[(480p|720p|1080p|2160p|4K|UHD)\][\p{Zs}\s]*""",
        asciiFlags,
    )
    private val sourceRegex = Regex(
        """$ASCII_START(WEB[ .\-\p{Pd}]?DL|WEB[ .\-\p{Pd}]?RIP|BLU[ .\-\p{Pd}]?RAY|BDRIP|BRRIP|HDRIP|DVDRIP|HDTV)$ASCII_END""",
        asciiFlags,
    )
    private val editionRegex = Regex(
        """$ASCII_START(OPEN[ .\-\p{Pd}]?MATTE|UNRATED|EXTENDED(?:[ .\-\p{Pd}]?EDITION)?|DIRECTOR'?S[ .\-\p{Pd}]?CUT|THEATRICAL|REMASTERED)$ASCII_END""",
        asciiFlags,
    )
    private val languageRegex = Regex(
        """(?iu)(?<![\p{L}\p{N}])(?:\d+x)?(""" +
            """RUSSIAN|ENGLISH|UKRAINIAN|""" +
            """РУССКИЙ|АНГЛИЙСКИЙ|УКРАИНСКИЙ|""" +
            """RUS|ENG|UKR|GER|FRE|JPN|""" +
            """РУС|АНГЛ|УКР""" +
            """)(?![\p{L}\p{N}])""",
    )

    fun parse(path: Path): MediaInfo {
        val originalName = normalizeUnicode(path.nameWithoutExtension)
        val leadingResolution = leadingResolutionRegex.find(originalName)?.groupValues?.getOrNull(1)
        val fileName = normalizeReleaseName(originalName)
        val parentName = normalizeReleaseName(path.parent?.fileName?.toString().orEmpty())
        val seasonEpisodeMatch = seasonEpisodeRegex.find(fileName)
        val isTvEpisode = seasonEpisodeMatch != null
        val metadataText = if (isTvEpisode) "$fileName $parentName" else fileName

        return MediaInfo(
            mediaType = if (isTvEpisode) MediaType.TV_EPISODE else MediaType.MOVIE,
            title = if (isTvEpisode) {
                extractSeriesTitle(fileName, parentName)
            } else {
                extractMovieTitle(fileName)
            },
            year = yearRegex.find(metadataText)?.value?.toIntOrNull(),
            season = seasonEpisodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
            episode = seasonEpisodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull(),
            episodeTitle = seasonEpisodeMatch?.let { extractEpisodeTitle(fileName, it) },
            resolution = (resolutionRegex.find(metadataText)?.value ?: leadingResolution)
                ?.let(::normalizeResolution),
            source = sourceRegex.find(metadataText)?.value?.let(::normalizeSource),
            editions = editionRegex.findAll(metadataText)
                .map { normalizeEdition(it.value) }
                .distinct()
                .toList(),
            languages = languageRegex.findAll(metadataText)
                .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeLanguage) }
                .distinct()
                .toList(),
        )
    }

    private fun extractMovieTitle(fileName: String): String {
        return fileName
            .substring(0, findFirstMetadataIndex(fileName))
            .trimReleaseSeparators()
            .ifBlank { fileName }
    }

    private fun extractSeriesTitle(fileName: String, parentName: String): String {
        val titleFromParent = parentName
            .substring(0, findFirstMetadataIndex(parentName))
            .trimReleaseSeparators()

        if (titleFromParent.isNotBlank()) {
            return titleFromParent
        }

        return fileName
            .substring(0, findFirstMetadataIndex(fileName))
            .trimReleaseSeparators()
            .ifBlank { "Название не определено" }
    }

    private fun extractEpisodeTitle(fileName: String, seasonEpisodeMatch: MatchResult): String? {
        val afterEpisodeCode = fileName
            .substring(seasonEpisodeMatch.range.last + 1)
            .trimReleaseSeparators()

        if (afterEpisodeCode.isBlank()) {
            return null
        }

        return afterEpisodeCode
            .substring(0, findFirstMetadataIndex(afterEpisodeCode))
            .trimReleaseSeparators()
            .ifBlank { null }
    }

    private fun findFirstMetadataIndex(value: String): Int {
        return listOfNotNull(
            yearRegex.find(value)?.range?.first,
            seasonEpisodeRegex.find(value)?.range?.first,
            seasonRegex.find(value)?.range?.first,
            resolutionRegex.find(value)?.range?.first,
            sourceRegex.find(value)?.range?.first,
            editionRegex.find(value)?.range?.first,
            languageRegex.find(value)?.range?.first,
        ).minOrNull() ?: value.length
    }

    private fun normalizeReleaseName(value: String): String {
        return normalizeUnicode(value)
            .replace(leadingResolutionRegex, "")
            .replace(Regex("""\p{Pd}"""), "-")
            .replace('.', ' ')
            .replace('\uFF0E', ' ')
            .replace('_', ' ')
            .replace('\uFF3F', ' ')
            .replace(Regex("""[\p{Zs}\s]+"""), " ")
            .trim()
    }

    private fun normalizeUnicode(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u200B-\u200D\u2060\uFEFF\u00AD]"""), "")
    }

    private fun normalizeResolution(value: String): String {
        return when (value.uppercase()) {
            "4K" -> "4K"
            "UHD" -> "UHD"
            else -> value.lowercase()
        }
    }

    private fun normalizeSource(value: String): String {
        return when (value.uppercase().replace(Regex("""[ .\-\p{Pd}]"""), "")) {
            "WEBDL" -> "WEB-DL"
            "WEBRIP" -> "WEBRip"
            "BLURAY" -> "BluRay"
            "BDRIP" -> "BDRip"
            "BRRIP" -> "BRRip"
            "HDRIP" -> "HDRip"
            "DVDRIP" -> "DVDRip"
            "HDTV" -> "HDTV"
            else -> value
        }
    }

    private fun normalizeEdition(value: String): String {
        val compact = value.uppercase()
            .replace(Regex("""[.\-\p{Pd}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return when {
            compact == "OPEN MATTE" -> "Open Matte"
            compact == "UNRATED" -> "Unrated"
            compact.startsWith("EXTENDED") -> "Extended"
            compact.contains("DIRECTOR") -> "Director's Cut"
            compact == "THEATRICAL" -> "Theatrical"
            compact == "REMASTERED" -> "Remastered"
            else -> value
        }
    }

    private fun normalizeLanguage(value: String): String? {
        return when (value.uppercase()) {
            "RUS", "RUSSIAN", "РУС", "РУССКИЙ" -> "RU"
            "ENG", "ENGLISH", "АНГЛ", "АНГЛИЙСКИЙ" -> "EN"
            "UKR", "UKRAINIAN", "УКР", "УКРАИНСКИЙ" -> "UK"
            "GER" -> "DE"
            "FRE" -> "FR"
            "JPN" -> "JA"
            else -> null
        }
    }
}

private fun String.trimReleaseSeparators(): String {
    return trim { char ->
        char.isWhitespace() ||
            char == '.' ||
            char == '_' ||
            char == '-' ||
            Character.getType(char) == Character.DASH_PUNCTUATION.toInt()
    }
}

// -----------------------------------------------------------------------------
// Report
// -----------------------------------------------------------------------------

object MediaPrinter {
    fun print(index: Int, file: Path, media: MediaInfo) {
        println("==================================================")
        println("${index + 1}. ${file.fileName}")
        println("Полный путь: $file")
        println("Тип: ${media.mediaType.displayName}")
        println("Название: ${media.title}")
        println("Год: ${media.year ?: "не найден"}")

        if (media.mediaType == MediaType.TV_EPISODE) {
            println("Сезон: ${media.season ?: "не найден"}")
            println("Эпизод: ${media.episode ?: "не найден"}")
            println("Название эпизода: ${media.episodeTitle ?: "не найдено"}")
        }

        println("Разрешение: ${media.resolution ?: "не найдено"}")
        println("Источник: ${media.source ?: "не найден"}")
        println("Версия: ${media.editions.ifEmpty { listOf("не найдена") }.joinToString()}")
        println("Языки: ${media.languages.ifEmpty { listOf("не найдены") }.joinToString()}")
    }
}
