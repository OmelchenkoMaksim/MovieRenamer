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
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.Duration
import java.time.LocalTime
import java.time.Year
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension
import kotlin.streams.asSequence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// =============================================================================
// 0000  Старт: терминал → папка → скан → каталоги → разбор имени → печать
// =============================================================================

object MovieRenamer {
    fun run(settings: LaunchSettings) {
        // 0000.01 Иначе кириллица в консоли Windows поедет.
        Talk.install()
        TitleCatalog.resetStats()

        if (settings.mode == WorkMode.REVERT) {
            revertNames(settings)
            return
        }

        val lookupOnline = settings.lookupOnline || settings.mode == WorkMode.DEBUG
        val debug = settings.mode == WorkMode.DEBUG
        val moviesDirectory = if (debug) {
            Config.debugSamples.toAbsolutePath().normalize()
        } else {
            settings.moviesDirectory.toAbsolutePath().normalize()
        }
        val resultsDirectory = if (debug) {
            Config.debugResults.toAbsolutePath().normalize()
        } else {
            null
        }

        Talk.info("Запуск Movie Renamer")
        Talk.info("Режим: ${settings.mode.displayName}")
        Talk.info("Рабочая директория: $moviesDirectory")
        if (settings.mode == WorkMode.DEBUG) {
            Talk.info("Исходники debug не трогаем. Результат: ${resultsDirectory ?: "не задан"}")
        }
        if (lookupOnline) {
            val catalogs = buildList {
                if (TitleCatalog.isTmdbConfigured()) add("TMDB")
                if (TitleCatalog.isPoiskKinoConfigured()) add("ПоискКино")
            }
            if (catalogs.isNotEmpty()) {
                Talk.info("Онлайн-поиск: ${catalogs.joinToString(" и ")}; запасные каталоги: iTunes, TVMaze, Wikipedia")
            } else {
                Talk.info(
                    "TMDB и ПоискКино выключены: задайте токены для русских названий, жанров, актёров и рейтинга",
                )
                Talk.info("Запасной онлайн-поиск: iTunes, TVMaze, Wikipedia")
            }
        }

        if (!moviesDirectory.isDirectory()) {
            Talk.error("Директория не найдена: $moviesDirectory")
            printReport(
                RunReport(
                    mode = settings.mode,
                    directory = moviesDirectory,
                    filesRead = 0,
                    apiRequests = 0,
                    parsed = 0,
                    unclear = 0,
                    changed = 0,
                    writtenResults = 0,
                    errors = emptyList(),
                ),
                resultsDirectory,
            )
            return
        }

        if (settings.mode == WorkMode.DEBUG) {
            val results = resultsDirectory
            if (results == null) {
                Talk.error("Для DEBUG нужна папка debug/results")
                return
            }
            FileGuard.prepareResultsDirectory(results)
        }

        val videoFiles = try {
            VideoScanner.findVideoFiles(moviesDirectory)
        } catch (exception: Exception) {
            Talk.error("Ошибка сканирования: ${exception.message}")
            return
        }

        if (videoFiles.isEmpty()) {
            Talk.info("В директории нет поддерживаемых видеофайлов")
            printReport(
                RunReport(
                    mode = settings.mode,
                    directory = moviesDirectory,
                    filesRead = 0,
                    apiRequests = TitleCatalog.requestCount(),
                    parsed = 0,
                    unclear = 0,
                    changed = 0,
                    writtenResults = 0,
                    errors = emptyList(),
                ),
                resultsDirectory,
            )
            return
        }

        Talk.info("Найдено файлов: ${videoFiles.size}")
        println()

        val plans = RenamePlanner.planAll(moviesDirectory, videoFiles, lookupOnline)
        val errors = mutableListOf<FileIssue>()
        var changed = 0
        var writtenResults = 0
        val previousNames = NameHistory.load().pairs
        val remembered = linkedMapOf<String, String>()
        var historyDirectory: Path? = null

        plans.forEachIndexed { index, plan ->
            MediaPrinter.print(index, plan, settings.mode)

            when (settings.mode) {
                WorkMode.PREVIEW, WorkMode.REVERT -> Unit
                WorkMode.DEBUG -> {
                    val results = resultsDirectory ?: return@forEachIndexed
                    if (plan.status == PlanStatus.READY) {
                        try {
                            FileGuard.copyToResults(
                                sourceLibrary = moviesDirectory,
                                from = plan.file,
                                resultsDirectory = results,
                                newName = plan.proposedName,
                            )
                            rememberRename(
                                remembered,
                                previousNames,
                                currentRelative = plan.proposedName,
                                previousName = plan.file.fileName.toString(),
                            )
                            historyDirectory = results
                            writtenResults++
                        } catch (exception: Exception) {
                            val message = exception.message ?: "неизвестная ошибка"
                            Talk.error("Не удалось записать результат ${plan.file.fileName}: $message")
                            errors += FileIssue(plan.file, message)
                        }
                    }
                }
                WorkMode.RENAME, WorkMode.COPY -> {
                    if (!plan.status.canApply) {
                        return@forEachIndexed
                    }
                    try {
                        applyPlan(settings.mode, moviesDirectory, plan)
                        val target = plan.target
                        if (target != null) {
                            rememberRename(
                                remembered,
                                previousNames,
                                currentRelative = NameHistory.relativeKey(moviesDirectory, target),
                                previousName = plan.file.fileName.toString(),
                            )
                            historyDirectory = moviesDirectory
                        }
                        changed++
                    } catch (exception: Exception) {
                        val message = exception.message ?: "неизвестная ошибка"
                        Talk.error("Не удалось обработать ${plan.file.fileName}: $message")
                        errors += FileIssue(plan.file, message)
                    }
                }
            }
        }

        historyDirectory?.let { directory ->
            if (remembered.isNotEmpty()) {
                NameHistory.save(directory, remembered)
            }
        }

        printReport(
            RunReport(
                mode = settings.mode,
                directory = moviesDirectory,
                filesRead = videoFiles.size,
                apiRequests = TitleCatalog.requestCount(),
                parsed = plans.count { it.status != PlanStatus.UNCLEAR },
                unclear = plans.count { it.status == PlanStatus.UNCLEAR },
                changed = changed,
                writtenResults = writtenResults,
                errors = errors,
                unclearFiles = plans.filter { it.status == PlanStatus.UNCLEAR }.map { it.file },
            ),
            resultsDirectory,
        )
    }

    private fun applyPlan(mode: WorkMode, library: Path, plan: RenamePlan) {
        val target = plan.target ?: return
        when (mode) {
            WorkMode.RENAME -> FileGuard.rename(library, plan.file, target)
            WorkMode.COPY -> FileGuard.copy(library, plan.file, target)
            WorkMode.PREVIEW, WorkMode.DEBUG, WorkMode.REVERT -> Unit
        }
    }

    private fun revertNames(settings: LaunchSettings) {
        Talk.info("Запуск Movie Renamer")
        Talk.info("Режим: ${settings.mode.displayName}")
        val snapshot = NameHistory.load()
        val library = snapshot.directory?.toAbsolutePath()?.normalize()
        if (library == null || snapshot.pairs.isEmpty()) {
            Talk.info("Кэш прошлых имён пуст: откатывать нечего")
            printReport(
                RunReport(
                    mode = settings.mode,
                    directory = settings.moviesDirectory.toAbsolutePath().normalize(),
                    filesRead = 0,
                    apiRequests = 0,
                    parsed = 0,
                    unclear = 0,
                    changed = 0,
                    writtenResults = 0,
                    errors = emptyList(),
                ),
                resultsDirectory = null,
            )
            return
        }

        Talk.info("Рабочая директория: $library")
        Talk.info("Пар в кэше: ${snapshot.pairs.size}")

        if (!library.isDirectory()) {
            Talk.error("Директория из кэша не найдена: $library")
            return
        }

        val videoFiles = try {
            VideoScanner.findVideoFiles(library)
        } catch (exception: Exception) {
            Talk.error("Ошибка сканирования: ${exception.message}")
            return
        }

        Talk.info("Найдено файлов: ${videoFiles.size}")
        println()

        val plans = RevertPlanner.planAll(library, videoFiles, snapshot.pairs)
        val errors = mutableListOf<FileIssue>()
        var changed = 0
        val leftover = snapshot.pairs.toMutableMap()

        plans.forEachIndexed { index, plan ->
            MediaPrinter.print(index, plan, settings.mode)
            if (!plan.status.canApply) return@forEachIndexed
            try {
                FileGuard.rename(library, plan.file, plan.target ?: return@forEachIndexed)
                leftover.remove(NameHistory.relativeKey(library, plan.file))
                leftover.remove(plan.file.fileName.toString())
                changed++
            } catch (exception: Exception) {
                val message = exception.message ?: "неизвестная ошибка"
                Talk.error("Не удалось вернуть ${plan.file.fileName}: $message")
                errors += FileIssue(plan.file, message)
            }
        }

        if (leftover != snapshot.pairs) {
            NameHistory.save(library, leftover)
        }

        printReport(
            RunReport(
                mode = settings.mode,
                directory = library,
                filesRead = videoFiles.size,
                apiRequests = 0,
                parsed = plans.count { it.status != PlanStatus.UNCLEAR },
                unclear = plans.count { it.status == PlanStatus.UNCLEAR },
                changed = changed,
                writtenResults = 0,
                errors = errors,
                unclearFiles = plans.filter { it.status == PlanStatus.UNCLEAR }.map { it.file },
            ),
            resultsDirectory = null,
        )
    }

    private fun rememberRename(
        remembered: MutableMap<String, String>,
        previous: Map<String, String>,
        currentRelative: String,
        previousName: String,
    ) {
        if (currentRelative.isBlank() || previousName.isBlank() || currentRelative == previousName) return
        remembered[currentRelative] = previous[previousName] ?: previousName
    }

    private fun printReport(report: RunReport, resultsDirectory: Path?) {
        println()
        ReportPrinter.print(report)
        if (report.mode == WorkMode.DEBUG && resultsDirectory != null) {
            ReportPrinter.writeFile(resultsDirectory.resolve("summary.txt"), report)
        }
    }
}

// =============================================================================
// 0001  Данные: что крутим и в каком виде храним результат
// =============================================================================

object Config {
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

    val debugSamples: Path = Path.of("debug", "samples")
    val debugResults: Path = Path.of("debug", "results")
    val revertCache: Path = Path.of("debug", "revert-cache.json")
}

// 0001.04 Режим задаётся в Main.kt.
enum class WorkMode(val displayName: String, val isReadOnly: Boolean) {
    PREVIEW("просмотр: что можно сделать", true),
    RENAME("переименовать на месте", false),
    COPY("копия с новым именем, оригинал оставить", false),
    DEBUG("тренировка: samples читаем, results пишем", true),
    REVERT("вернуть прошлые имена", false),
}

data class LaunchSettings(
    val moviesDirectory: Path,
    val mode: WorkMode,
    val lookupOnline: Boolean,
    val resultsDirectory: Path? = null,
)

data class FileIssue(
    val file: Path,
    val message: String,
)

data class RunReport(
    val mode: WorkMode,
    val directory: Path,
    val filesRead: Int,
    val apiRequests: Int,
    val parsed: Int,
    val unclear: Int,
    val changed: Int,
    val writtenResults: Int,
    val errors: List<FileIssue>,
    val unclearFiles: List<Path> = emptyList(),
)

enum class PlanStatus(val canApply: Boolean, val displayName: String) {
    READY(true, "можно сделать"),
    ALREADY_OK(false, "уже в нужном виде"),
    UNCLEAR(false, "не поняли, как назвать"),
    BLOCKED(false, "не получится"),
}

data class RenamePlan(
    val file: Path,
    val media: MediaInfo,
    val proposedName: String,
    val status: PlanStatus,
    val reasons: List<String>,
    val catalog: CatalogHit? = null,
) {
    val target: Path? = file.parent?.resolve(proposedName)
}

enum class MediaType(val displayName: String) {
    MOVIE("Фильм"),
    TV_EPISODE("Эпизод сериала"),
}

// 0001.03 Поля, которые вытащили из имени файла. Пустые — null / пустой список.
data class CatalogHit(
    val site: String,
    val title: String,
    val year: Int?,
    val pageUrl: String?,
    val originalTitle: String? = null,
    val russianTitle: String? = null,
    val originalLanguage: String? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val rating: Double? = null,
    val ratingSource: String? = null,
    val catalogId: Int? = null,
)

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
    val originalTitle: String? = null,
    val russianTitle: String? = null,
    val originalLanguage: String? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val rating: Double? = null,
    val ratingSource: String? = null,
)

// =============================================================================
// 0010  Talk: пишем человеку в терминал, UTF-8, на Windows — WriteConsoleW
// =============================================================================

object Talk {
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

// 0010.01 Реальная cmd/PowerShell: байты UTF-8 туда писать нельзя, только UTF-16.
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
            // 0010.02 Студия и пайпы — не консоль, достаточно UTF-8 в поток.
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
    private var pendingBytes = ByteArray(0)

    @Synchronized
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val bytes = if (pendingBytes.isEmpty()) {
            b.copyOfRange(off, off + len)
        } else {
            pendingBytes + b.copyOfRange(off, off + len)
        }
        pendingBytes = ByteArray(0)
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(bytes.size + 1)
        while (input.hasRemaining()) {
            val result = decoder.decode(input, output, false)
            output.flip()
            if (output.hasRemaining()) {
                writeChars(output)
            }
            output.clear()
            when {
                result.isUnderflow -> {
                    if (input.hasRemaining()) {
                        pendingBytes = ByteArray(input.remaining())
                        input.get(pendingBytes)
                    }
                    break
                }
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
            var chunk = minOf(4096, chars.size - offset)
            if (offset + chunk < chars.size && chars[offset + chunk - 1].isHighSurrogate()) {
                chunk--
            }
            val slice = if (offset == 0 && chunk == chars.size) {
                chars
            } else {
                chars.copyOfRange(offset, offset + chunk)
            }
            if (!kernel32.WriteConsoleW(handle, slice, chunk, written, null)) {
                throw IOException("WriteConsoleW failed while printing Unicode text")
            }
            val count = written.value
            if (count <= 0 || count > chunk) {
                throw IOException("WriteConsoleW returned an invalid character count: $count")
            }
            offset += count
        }
    }
}

// =============================================================================
// 0100  Библиотека: защита файлов, обход, парсер имени, отчёт
// =============================================================================

object FileGuard {
    fun libraryRoot(directory: Path): Path {
        return directory.toAbsolutePath().normalize()
    }

    // 0100.01 Путь обязан остаться внутри корня. Защита от ../ и симлинков наружу.
    fun isInside(library: Path, path: Path): Boolean {
        val root = libraryRoot(library)
        val candidate = path.toAbsolutePath().normalize()
        if (!candidate.startsWith(root)) return false
        val parent = candidate.parent ?: return false
        return runCatching {
            parent.toRealPath().startsWith(root.toRealPath())
        }.getOrDefault(false)
    }

    fun listRegularFiles(library: Path): List<Path> {
        val root = libraryRoot(library)
        return Files.walk(root).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { isInside(root, it) }
                .toList()
        }
    }

    fun rename(library: Path, from: Path, to: Path) {
        val (source, target) = prepareMutation(library, from, to)
        Files.move(source, target)
    }

    fun copy(library: Path, from: Path, to: Path) {
        val (source, target) = prepareMutation(library, from, to)
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
    }

    fun isDebugResultsPath(directory: Path): Boolean {
        val root = directory.toAbsolutePath().normalize()
        return root == Config.debugResults.toAbsolutePath().normalize()
    }

    // 0100.02.01 Только debug/results. Исходники samples не чистим и не пишем.
    fun prepareResultsDirectory(directory: Path) {
        val root = directory.toAbsolutePath().normalize()
        checkDebugResultsDirectory(root)
        Files.createDirectories(root)
        check(!Files.isSymbolicLink(root) && isInside(Path.of("").toAbsolutePath(), root)) {
            "debug/results не должен быть симлинком или вести за пределы проекта: $root"
        }
        Files.list(root).use { stream ->
            stream.forEach { path ->
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(path)
                }
            }
        }
    }

    fun copyToResults(
        sourceLibrary: Path,
        from: Path,
        resultsDirectory: Path,
        newName: String,
    ) {
        val destRoot = resultsDirectory.toAbsolutePath().normalize()
        checkDebugResultsDirectory(destRoot)
        check(Files.isDirectory(destRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(destRoot)) {
            "Папка DEBUG results не готова или является симлинком: $destRoot"
        }
        val source = from.toAbsolutePath().normalize()
        check(isInside(sourceLibrary, source)) {
            "Читаем только файлы из samples/библиотеки: $source"
        }
        val target = destRoot.resolve(newName).normalize()
        check(isInside(destRoot, target)) {
            "Не выходим из debug/results"
        }
        check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            "Источник не обычный файл: $source"
        }
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "Не перезаписываем существующий результат: $target"
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
    }

    private fun checkDebugResultsDirectory(directory: Path) {
        check(isDebugResultsPath(directory)) {
            "Результаты DEBUG пишем только в ${Config.debugResults.toAbsolutePath().normalize()}, а не в $directory"
        }
        val projectRoot = Path.of("").toAbsolutePath().normalize()
        val parent = directory.parent
        check(parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            "Родительская папка DEBUG не найдена: $parent"
        }
        check(!Files.isSymbolicLink(parent) && isInside(projectRoot, directory)) {
            "debug/results не должен вести за пределы проекта: $directory"
        }
        check(!Files.isSymbolicLink(directory)) {
            "debug/results не должен быть симлинком: $directory"
        }
    }

    // 0100.02 Единственные мутации: move или copy. Delete нет и не будет.
    private fun prepareMutation(library: Path, from: Path, to: Path): Pair<Path, Path> {
        val root = libraryRoot(library)
        val source = from.toAbsolutePath().normalize()
        val target = to.toAbsolutePath().normalize()

        check(isInside(root, source) && isInside(root, target)) {
            "Операция только внутри библиотеки: $root"
        }
        check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            "Источник не обычный файл: $source"
        }
        check(source.parent == target.parent) {
            "Пока только новое имя в той же папке, без переноса"
        }
        check(source != target) {
            "Новое имя совпадает со старым"
        }
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "Не перезаписываем существующий файл: $target"
        }

        return source to target
    }
}

object VideoScanner {
    fun findVideoFiles(directory: Path): List<Path> {
        return FileGuard.listRegularFiles(directory)
            .filter { it.extension.lowercase() in Config.videoExtensions }
            .sortedBy { it.toString().lowercase() }
    }
}

object MediaParser {
    private val asciiFlags = setOf(RegexOption.IGNORE_CASE)

    // 0100.03 Граница ASCII-токена: «Дюна2021» и «СериалS01E01» всё ещё режутся верно.
    private const val ASCII_START = """(?<![A-Za-z0-9])"""
    private const val ASCII_END = """(?![A-Za-z0-9])"""

    private val yearRegex = Regex("""$ASCII_START(18(?:8[8-9]|9\d)|19\d{2}|20\d{2})$ASCII_END""")
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
        """$ASCII_START(WEB[ .\-\p{Pd}]?DL(?:[ .\-\p{Pd}]?RIP)?|WEB[ .\-\p{Pd}]?RIP|BLU[ .\-\p{Pd}]?RAY|BDRIP|BRRIP|HDRIP|DVDRIP|HDTV)$ASCII_END""",
        asciiFlags,
    )
    private val sitePrefixRegex = Regex(
        """^(?:www )?[\p{L}\p{N}-]+ (?:org|com|net|ru|info|tv|me|cc|biz) """,
        asciiFlags,
    )
    private val wrappedYearRegex = Regex(
        """[(\[]\s*(18(?:8[8-9]|9\d)|19\d{2}|20\d{2})\s*[)\]]""",
    )
    private val genericFolderNames = setOf(
        "samples", "sample", "movies", "movie", "video", "videos",
        "tv", "series", "shows", "downloads", "download", "media",
        "library", "debug", "temp", "tmp", "files",
        "фильмы", "сериалы", "видео", "загрузки",
    )
    private val seasonFolderRegex = Regex("""(?iu)^(?:(?:season|сезон)\s*\d{1,3}|S\d{1,2})$""")
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
        // 0100.03.01 [1080p] в начале часто выкидывают до поиска тегов — запоминаем.
        val leadingResolution = leadingResolutionRegex.find(originalName)?.groupValues?.getOrNull(1)
        val fileName = normalizeReleaseName(originalName)
        val immediateParent = path.parent
        val parentName = normalizeReleaseName(immediateParent?.fileName?.toString().orEmpty())
        val seriesParentName = if (seasonFolderRegex.matches(parentName)) {
            normalizeReleaseName(immediateParent?.parent?.fileName?.toString().orEmpty())
        } else {
            parentName
        }
        val seasonEpisodeMatch = seasonEpisodeRegex.find(fileName)
        val isTvEpisode = seasonEpisodeMatch != null
        // 0100.03.02 У серии год/качество иногда только в папке сериала.
        val metadataText = if (isTvEpisode) "$fileName $parentName $seriesParentName" else fileName

        return MediaInfo(
            mediaType = if (isTvEpisode) MediaType.TV_EPISODE else MediaType.MOVIE,
            title = if (isTvEpisode) {
                extractSeriesTitle(fileName, seriesParentName)
            } else {
                extractMovieTitle(fileName)
            },
            year = extractReleaseYear(fileName)
                ?: if (isTvEpisode) extractReleaseYear(seriesParentName) else null,
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
            // Язык берём только из имени файла: название родительской папки может быть Russian Doll.
            languages = languageMatches(fileName)
                .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeLanguage) }
                .distinct()
                .toList(),
        )
    }

    // 0100.03.03 Название фильма — всё до года релиза (последний год перед качеством).
    private fun extractMovieTitle(fileName: String): String {
        return fileName
            .substring(0, movieTitleEndIndex(fileName))
            .trimReleaseSeparators()
            .ifBlank { fileName }
    }

    private fun extractSeriesTitle(fileName: String, parentName: String): String {
        val titleFromFile = fileName
            .substring(0, findFirstMetadataIndex(fileName))
            .trimReleaseSeparators()
        val titleFromParent = parentName
            .substring(0, findFirstMetadataIndex(parentName))
            .trimReleaseSeparators()

        // 0100.03.03.01 Папка samples/movies — не название сериала.
        if (titleFromParent.isNotBlank() && !isGenericFolder(titleFromParent)) {
            return titleFromParent
        }

        return titleFromFile.ifBlank { "Название не определено" }
    }

    private fun isGenericFolder(name: String): Boolean {
        return name.lowercase() in genericFolderNames
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
            firstPlausibleYear(value)?.range?.first,
            seasonEpisodeRegex.find(value)?.range?.first,
            seasonRegex.find(value)?.range?.first,
            resolutionRegex.find(value)?.range?.first,
            sourceRegex.find(value)?.range?.first,
            editionRegex.find(value)?.range?.first,
        ).minOrNull() ?: value.length
    }

    // 0100.03.05 Год релиза — последний 1888…сейчас+1 до тегов качества: 2001 и 2049 остаются в названии.
    private fun movieTitleEndIndex(fileName: String): Int {
        val qualityIndex = firstQualityIndex(fileName)
        val lastYear = lastPlausibleYear(fileName.substring(0, qualityIndex))
        return lastYear?.range?.first ?: qualityIndex
    }

    private fun extractReleaseYear(text: String): Int? {
        val qualityIndex = firstQualityIndex(text)
        return lastPlausibleYear(text.substring(0, qualityIndex))?.value?.toInt()
    }

    private fun firstQualityIndex(value: String): Int {
        return listOfNotNull(
            seasonEpisodeRegex.find(value)?.range?.first,
            seasonRegex.find(value)?.range?.first,
            resolutionRegex.find(value)?.range?.first,
            sourceRegex.find(value)?.range?.first,
            editionRegex.find(value)?.range?.first,
        ).minOrNull() ?: value.length
    }

    private fun languageMatches(value: String): Sequence<MatchResult> {
        val metadataStart = listOfNotNull(
            firstPlausibleYear(value)?.range?.first,
            resolutionRegex.find(value)?.range?.first,
            sourceRegex.find(value)?.range?.first,
            editionRegex.find(value)?.range?.first,
        ).minOrNull() ?: return emptySequence()
        return languageRegex.findAll(value).filter { it.range.first > metadataStart }
    }

    private fun firstPlausibleYear(value: String): MatchResult? = plausibleYears(value).firstOrNull()

    private fun lastPlausibleYear(value: String): MatchResult? = plausibleYears(value).lastOrNull()

    private fun plausibleYears(value: String): List<MatchResult> {
        val maxYear = Year.now().value + 1
        return yearRegex.findAll(value)
            .filter { match ->
                val year = match.value.toInt()
                year in 1888..maxYear
            }
            .toList()
    }

    // 0100.03.04 NFC + тире/точки/пробелы к одному виду, чтобы regex не путались.
    private fun normalizeReleaseName(value: String): String {
        return normalizeUnicode(value)
            .replace(leadingResolutionRegex, "")
            .replace(Regex("""\p{Pd}"""), "-")
            .replace('.', ' ')
            .replace('\uFF0E', ' ')
            .replace('_', ' ')
            .replace('\uFF3F', ' ')
            .replace(wrappedYearRegex, "$1")
            .replace(Regex("""[\p{Zs}\s]+"""), " ")
            .replace(sitePrefixRegex, "")
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
            "WEBDLRIP" -> "WEB-DLRip"
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

object NameFormatter {
    private const val MAX_FILE_NAME_LENGTH = 240

    fun fileName(media: MediaInfo, extension: String): String {
        val stem = when (media.mediaType) {
            MediaType.MOVIE -> movieStem(media)
            MediaType.TV_EPISODE -> episodeStem(media)
        }
        return limitLength(sanitize("$stem.$extension"))
    }

    private fun movieStem(media: MediaInfo): String {
        return buildList {
            add(movieTitles(media))
            media.year?.let { add("($it)") }
            if (media.genres.isNotEmpty()) {
                add("[${media.genres.take(3).joinToString(", ")}]")
            }
            if (media.directors.isNotEmpty()) {
                add("[${media.directors.take(2).joinToString(", ")}]")
            }
            if (media.actors.isNotEmpty()) {
                add("[${media.actors.take(3).joinToString(", ")}]")
            }
            media.rating
                ?.takeIf { it > 0.0 }
                ?.let { value ->
                    val score = String.format(Locale.ROOT, "%.1f", value)
                    val source = media.ratingSource?.trim().orEmpty()
                    add(if (source.isEmpty()) "($score)" else "($score $source)")
                }
            media.resolution?.let(::add)
        }.joinToString(" ")
    }

    private fun movieTitles(media: MediaInfo): String {
        val original = media.originalTitle?.trim().orEmpty()
        val russian = media.russianTitle?.trim().orEmpty()
        if (media.originalLanguage.equals("ru", ignoreCase = true)) {
            return russian.ifBlank { original }.ifBlank { media.title }
        }
        return when {
            original.isBlank() && russian.isBlank() -> media.title
            original.isBlank() -> russian
            russian.isBlank() || sameTitle(original, russian) -> original
            else -> "$original — $russian"
        }
    }

    private fun sameTitle(first: String, second: String): Boolean {
        fun fold(value: String): String = value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
        return fold(first) == fold(second)
    }

    private fun episodeStem(media: MediaInfo): String {
        val code = "S%02dE%02d".format(media.season ?: 0, media.episode ?: 0)
        return buildList {
            add(media.title)
            add(code)
            media.episodeTitle?.let(::add)
            media.resolution?.let(::add)
        }.joinToString(" ")
    }

    // 0100.05 Символы, которые Windows не берёт в имя файла.
    private fun sanitize(value: String): String {
        return value
            .replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.')
    }

    private fun limitLength(value: String): String {
        if (value.length <= MAX_FILE_NAME_LENGTH) return value
        val extensionIndex = value.lastIndexOf('.')
        val extension = if (extensionIndex > 0) value.substring(extensionIndex) else ""
        val stem = if (extensionIndex > 0) value.substring(0, extensionIndex) else value
        val available = MAX_FILE_NAME_LENGTH - extension.length - 1
        val prefixLength = available * 2 / 3
        val suffixLength = available - prefixLength
        return stem.take(prefixLength).trimEnd() +
            "…" +
            stem.takeLast(suffixLength).trimStart() +
            extension
    }
}

object RenamePlanner {
    fun planAll(
        library: Path,
        files: List<Path>,
        lookupOnline: Boolean = false,
    ): List<RenamePlan> {
        val reserved = mutableSetOf<Path>()
        return files.map { file ->
            val plan = planOne(library, file, reserved, lookupOnline)
            plan.target?.let(reserved::add)
            plan
        }
    }

    // 0100.06 Переименовываем только если хватает названия и года / SxxExx.
    private fun planOne(
        library: Path,
        file: Path,
        reserved: Set<Path>,
        lookupOnline: Boolean,
    ): RenamePlan {
        val parsed = MediaParser.parse(file)
        val catalog = if (lookupOnline) TitleCatalog.find(parsed) else null
        val media = parsed.withCatalog(catalog)
        val proposedName = NameFormatter.fileName(media, file.extension.lowercase())
        val target = file.parent?.resolve(proposedName)
        val reasons = mutableListOf<String>()

        if (media.title.isBlank() || media.title == "Название не определено") {
            reasons += "нет названия"
        }
        when (media.mediaType) {
            MediaType.MOVIE -> if (media.year == null) reasons += "нет года"
            MediaType.TV_EPISODE -> {
                if (media.season == null) reasons += "нет сезона"
                if (media.episode == null) reasons += "нет эпизода"
            }
        }

        val status = when {
            reasons.isNotEmpty() -> PlanStatus.UNCLEAR
            proposedName.equals(file.fileName.toString(), ignoreCase = false) -> PlanStatus.ALREADY_OK
            target == null -> {
                reasons += "нет папки у файла"
                PlanStatus.BLOCKED
            }
            !FileGuard.isInside(library, target) -> {
                reasons += "цель вне библиотеки"
                PlanStatus.BLOCKED
            }
            Files.exists(target, LinkOption.NOFOLLOW_LINKS) || target in reserved -> {
                reasons += "файл с таким именем уже есть"
                PlanStatus.BLOCKED
            }
            else -> PlanStatus.READY
        }

        return RenamePlan(
            file = file,
            media = media,
            proposedName = proposedName,
            status = status,
            reasons = reasons,
            catalog = catalog,
        )
    }
}

object RevertPlanner {
    fun planAll(library: Path, files: List<Path>, pairs: Map<String, String>): List<RenamePlan> {
        val reserved = mutableSetOf<Path>()
        return files.map { file ->
            val plan = planOne(library, file, pairs, reserved)
            plan.target?.let(reserved::add)
            plan
        }
    }

    private fun planOne(
        library: Path,
        file: Path,
        pairs: Map<String, String>,
        reserved: Set<Path>,
    ): RenamePlan {
        val parsed = MediaParser.parse(file)
        val relative = NameHistory.relativeKey(library, file)
        val originalName = pairs[relative] ?: pairs[file.fileName.toString()]
        val reasons = mutableListOf<String>()
        if (originalName.isNullOrBlank()) {
            reasons += "нет пары в кэше"
        }
        val proposedName = originalName ?: file.fileName.toString()
        val target = file.parent?.resolve(proposedName)
        val status = when {
            reasons.isNotEmpty() -> PlanStatus.UNCLEAR
            proposedName == file.fileName.toString() -> PlanStatus.ALREADY_OK
            target == null -> {
                reasons += "нет папки у файла"
                PlanStatus.BLOCKED
            }
            !FileGuard.isInside(library, target) -> {
                reasons += "цель вне библиотеки"
                PlanStatus.BLOCKED
            }
            Files.exists(target, LinkOption.NOFOLLOW_LINKS) || target in reserved -> {
                reasons += "файл с таким именем уже есть"
                PlanStatus.BLOCKED
            }
            else -> PlanStatus.READY
        }
        return RenamePlan(
            file = file,
            media = parsed,
            proposedName = proposedName,
            status = status,
            reasons = reasons,
        )
    }
}

object NameHistory {
    private val json = Json { prettyPrint = true }

    fun relativeKey(root: Path, file: Path): String {
        val base = root.toAbsolutePath().normalize()
        val target = file.toAbsolutePath().normalize()
        return base.relativize(target).toString().replace('\\', '/')
    }

    fun load(file: Path = Config.revertCache): RevertSnapshot {
        if (!Files.isRegularFile(file)) return RevertSnapshot(directory = null, pairs = emptyMap())
        return runCatching {
            val root = json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject
            val directory = root["directory"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
            val pairs = root["pairs"]?.jsonObject
                ?.mapNotNull { (key, value) ->
                    val original = value.jsonPrimitive.contentOrNull?.trim().orEmpty()
                    key.trim().takeIf { it.isNotEmpty() && original.isNotEmpty() }?.let { it to original }
                }
                ?.toMap()
                .orEmpty()
            RevertSnapshot(directory = directory, pairs = pairs)
        }.getOrDefault(RevertSnapshot(directory = null, pairs = emptyMap()))
    }

    fun save(directory: Path, pairs: Map<String, String>, file: Path = Config.revertCache) {
        val parent = file.toAbsolutePath().normalize().parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        val payload = buildJsonObject {
            put("directory", directory.toAbsolutePath().normalize().toString())
            put(
                "pairs",
                buildJsonObject {
                    pairs.forEach { (current, original) ->
                        if (current.isNotBlank() && original.isNotBlank()) {
                            put(current, original)
                        }
                    }
                },
            )
        }
        Files.writeString(file, payload.toString() + "\n", StandardCharsets.UTF_8)
    }
}

data class RevertSnapshot(
    val directory: Path?,
    val pairs: Map<String, String>,
)

object MediaPrinter {
    fun print(index: Int, plan: RenamePlan, mode: WorkMode) {
        val media = plan.media
        println("==================================================")
        println("${index + 1}. ${plan.file.fileName}")
        println("Статус: ${plan.status.displayName}")
        println("Новое имя: ${plan.proposedName}")
        if (plan.reasons.isNotEmpty()) {
            println("Почему: ${plan.reasons.joinToString("; ")}")
        }
        plan.catalog?.let { hit ->
            println("Каталог: ${hit.site} — ${hit.title}${hit.year?.let { " ($it)" } ?: ""}")
        }

        if (mode == WorkMode.DEBUG) {
            println("Полный путь: ${plan.file}")
            println("Тип: ${media.mediaType.displayName}")
            println("Название: ${media.title}")
            if (media.mediaType == MediaType.MOVIE) {
                println("Оригинальное название: ${media.originalTitle ?: "не найдено"}")
                println("Русское название: ${media.russianTitle ?: "не найдено"}")
                println("Жанры: ${media.genres.ifEmpty { listOf("не найдены") }.joinToString()}")
                println("Режиссёр: ${media.directors.ifEmpty { listOf("не найден") }.joinToString()}")
                println("Главные актёры: ${media.actors.ifEmpty { listOf("не найдены") }.joinToString()}")
                println(
                    "Рейтинг: " +
                        (
                            media.rating?.let { value ->
                                val score = String.format(Locale.ROOT, "%.1f", value)
                                val source = media.ratingSource?.trim().orEmpty()
                                if (source.isEmpty()) score else "$score $source"
                            } ?: "не найден"
                            ),
                )
            }
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
            plan.catalog?.pageUrl?.let { println("Страница: $it") }
        }
    }
}

object ReportPrinter {
    fun print(report: RunReport) {
        lines(report).forEach(Talk::info)
    }

    fun writeFile(path: Path, report: RunReport) {
        Files.writeString(path, lines(report).joinToString("\n") + "\n", StandardCharsets.UTF_8)
    }

    private fun lines(report: RunReport): List<String> {
        return buildList {
            add("—— Итог ——")
            add("Режим: ${report.mode.displayName}")
            add("Прочитали каталог: ${report.directory}")
            add("Файлов прочитано: ${report.filesRead}")
            add("Запросов к API: ${report.apiRequests}")
            add("Удалось разобрать: ${report.parsed}")
            add("Не разобрали: ${report.unclear}")
            report.unclearFiles.forEach { add("  не разобрали: ${it.fileName}") }
            add("Файлов изменено в библиотеке: ${report.changed}")
            if (report.mode == WorkMode.DEBUG) {
                add("Записано в debug/results: ${report.writtenResults}")
            }
            add("Ошибок: ${report.errors.size}")
            report.errors.forEach { add("  ошибка: ${it.file.fileName} — ${it.message}") }
        }
    }
}

private fun MediaInfo.withCatalog(hit: CatalogHit?): MediaInfo {
    if (hit == null) return this
    return copy(
        title = TitleCatalog.cleanTitle(hit.title).ifBlank { title },
        year = year ?: hit.year,
        originalTitle = hit.originalTitle?.let(TitleCatalog::cleanTitle),
        russianTitle = hit.russianTitle?.let(TitleCatalog::cleanTitle),
        originalLanguage = hit.originalLanguage,
        genres = hit.genres.take(3),
        directors = hit.directors.take(2),
        actors = hit.actors.take(3),
        rating = hit.rating,
        ratingSource = hit.ratingSource,
    )
}

// =============================================================================
// 1000  Интернет: TMDB и ПоискКино, затем открытые каталоги без ключей
// =============================================================================

object TitleCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, CatalogHit?>()
    private val httpRequests = AtomicInteger(0)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun resetStats() {
        httpRequests.set(0)
        cache.clear()
    }

    fun requestCount(): Int = httpRequests.get()

    fun isTmdbConfigured(): Boolean = tmdbToken() != null

    fun isPoiskKinoConfigured(): Boolean = poiskKinoToken() != null

    fun find(media: MediaInfo): CatalogHit? {
        if (media.title.isBlank() || media.title == "Название не определено") {
            return null
        }
        val cacheKey = "${media.mediaType}|${media.title.lowercase()}|${media.year}"
        return cache.getOrPut(cacheKey) {
            if (media.mediaType == MediaType.MOVIE) {
                val tmdb = searchTmdbMovie(media)
                val poiskKino = if (movieHitNeedsMoreData(tmdb) && isPoiskKinoConfigured()) {
                    searchPoiskKinoMovie(media)
                } else {
                    null
                }
                val fromPrimaryCatalogs = chooseMovieHit(media, tmdb, poiskKino, fallbackHits = emptyList())
                if (fromPrimaryCatalogs != null) return@getOrPut fromPrimaryCatalogs
            }
            chooseMovieHit(
                media,
                tmdb = null,
                poiskKino = null,
                fallbackHits = searchFallbackCatalogs(media),
            )
        }
    }

    // TMDB → недостающие поля из ПоискКино → только если оба пустые, iTunes/TVMaze/Wikipedia.
    fun chooseMovieHit(
        local: MediaInfo,
        tmdb: CatalogHit?,
        poiskKino: CatalogHit?,
        fallbackHits: List<CatalogHit>,
    ): CatalogHit? {
        if (tmdb != null && !movieHitNeedsMoreData(tmdb)) {
            return tmdb
        }
        val fromPrimaryCatalogs = mergeCatalogHits(tmdb, poiskKino)
        if (fromPrimaryCatalogs != null) return fromPrimaryCatalogs
        return pickBest(local, fallbackHits)
    }

    fun movieHitNeedsMoreData(hit: CatalogHit?): Boolean {
        if (hit == null) return true
        return firstNonBlank(hit.originalTitle) == null ||
            firstNonBlank(hit.russianTitle) == null ||
            hit.genres.isEmpty() ||
            hit.directors.isEmpty() ||
            hit.actors.isEmpty() ||
            hit.rating == null ||
            hit.rating <= 0.0
    }

    fun mergeCatalogHits(primary: CatalogHit?, secondary: CatalogHit?): CatalogHit? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        val primaryRating = primary.rating?.takeIf { it > 0.0 }
        val secondaryRating = secondary.rating?.takeIf { it > 0.0 }
        return primary.copy(
            site = listOf(primary.site, secondary.site)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" + "),
            title = firstNonBlank(primary.title, secondary.title) ?: primary.title,
            year = primary.year ?: secondary.year,
            pageUrl = primary.pageUrl ?: secondary.pageUrl,
            originalTitle = firstNonBlank(primary.originalTitle, secondary.originalTitle),
            russianTitle = firstNonBlank(primary.russianTitle, secondary.russianTitle),
            originalLanguage = firstNonBlank(primary.originalLanguage, secondary.originalLanguage),
            genres = primary.genres.ifEmpty { secondary.genres },
            directors = primary.directors.ifEmpty { secondary.directors },
            actors = primary.actors.ifEmpty { secondary.actors },
            rating = primaryRating ?: secondaryRating,
            ratingSource = if (primaryRating != null) {
                primary.ratingSource
            } else {
                secondary.ratingSource
            },
            catalogId = primary.catalogId ?: secondary.catalogId,
        )
    }

    private fun searchFallbackCatalogs(media: MediaInfo): List<CatalogHit> {
        return buildList {
            addAll(searchItunes(media))
            if (media.mediaType == MediaType.TV_EPISODE) {
                addAll(searchTvMaze(media))
            }
            addAll(searchWikipedia(media, "ru"))
            addAll(searchWikipedia(media, "en"))
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
    }

    fun pickBest(local: MediaInfo, hits: List<CatalogHit>): CatalogHit? {
        return hits
            .map { it to score(local, it) }
            .filter { it.second >= 45 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(local: MediaInfo, hit: CatalogHit): Int {
        return listOfNotNull(hit.title, hit.originalTitle, hit.russianTitle)
            .distinct()
            .maxOfOrNull { candidate -> scoreTitle(local, candidate, hit.year) }
            ?: -1
    }

    private fun scoreTitle(local: MediaInfo, candidateTitle: String, candidateYear: Int?): Int {
        val localKeys = titleKeys(local.title)
        val hitKeys = titleKeys(candidateTitle)
        if (localKeys.isEmpty() || hitKeys.isEmpty()) return -1

        val exactTitle = localKeys.intersect(hitKeys).isNotEmpty()
        // Без локального года каталог может дополнить данные только при точном названии.
        if (local.year == null && !exactTitle) return -1

        var points = if (exactTitle) {
            60
        } else {
            val localWords = localKeys.maxBy { it.length }.split(" ").filter(String::isNotBlank).toSet()
            val hitWords = hitKeys.maxBy { it.length }.split(" ").filter(String::isNotBlank).toSet()
            if (localWords.size <= 1 || hitWords.isEmpty()) return -1
            val matched = localWords.intersect(hitWords).size
            val coverage = matched.toDouble() / localWords.size
            val precision = matched.toDouble() / hitWords.size
            if (coverage < 0.75 || precision < 0.6) return -1
            (coverage * 30 + precision * 20).toInt()
        }

        if (local.year != null && candidateYear != null) {
            val delta = kotlin.math.abs(local.year - candidateYear)
            points += when {
                delta == 0 -> 25
                delta == 1 -> 5
                else -> -40
            }
        }
        return points
    }

    private fun foldTitle(value: String): String {
        return value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun titleKeys(value: String): Set<String> {
        return setOf(foldTitle(value), foldTitle(latinToCyrillic(value)))
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun searchQueries(title: String): List<String> {
        val raw = title.trim()
        if (raw.isEmpty()) return emptyList()
        val cyrillic = latinToCyrillic(raw).trim()
        return listOf(raw, cyrillic).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }

    // Латиница из релизов: brat → брат. Не перевод: dune не станет «Дюна».
    fun latinToCyrillic(value: String): String {
        var text = value.lowercase()
        val digraphs = listOf(
            "shch" to "щ",
            "yo" to "ё",
            "jo" to "ё",
            "zh" to "ж",
            "kh" to "х",
            "ts" to "ц",
            "ch" to "ч",
            "sh" to "ш",
            "yu" to "ю",
            "ju" to "ю",
            "ya" to "я",
            "ja" to "я",
        )
        for ((from, to) in digraphs) {
            text = text.replace(from, to)
        }
        val letters = mapOf(
            'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е",
            'f' to "ф", 'g' to "г", 'h' to "х", 'i' to "и", 'j' to "й",
            'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о",
            'p' to "п", 'q' to "к", 'r' to "р", 's' to "с", 't' to "т",
            'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "ы",
            'z' to "з",
        )
        return buildString {
            for (char in text) {
                append(letters[char] ?: char)
            }
        }
    }

    private fun searchTmdbMovie(media: MediaInfo): CatalogHit? {
        val token = tmdbToken() ?: return null
        val year = media.year?.let { "&year=$it" }.orEmpty()
        val candidates = searchQueries(media.title).flatMap { query ->
            val url = "https://api.themoviedb.org/3/search/movie" +
                "?query=${enc(query)}&language=ru-RU&include_adult=false$year"
            val body = get(url, token) ?: return@flatMap emptyList()
            runCatching {
                json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
                    .take(10)
                    .mapNotNull { element ->
                        val item = element.jsonObject
                        val id = item.int("id") ?: return@mapNotNull null
                        val russianTitle = item.str("title") ?: return@mapNotNull null
                        CatalogHit(
                            site = "TMDB",
                            title = russianTitle,
                            year = yearOf(item.str("release_date")),
                            pageUrl = "https://www.themoviedb.org/movie/$id",
                            originalTitle = item.str("original_title"),
                            russianTitle = russianTitle,
                            originalLanguage = item.str("original_language"),
                            rating = item.double("vote_average")?.takeIf { it > 0.0 },
                            ratingSource = item.double("vote_average")?.takeIf { it > 0.0 }?.let { "TMDB" },
                            catalogId = id,
                        )
                    }
            }.getOrDefault(emptyList())
        }.distinctBy { it.catalogId }
        val candidate = pickBest(media, candidates) ?: return null
        val id = candidate.catalogId ?: return candidate
        val detailsUrl = "https://api.themoviedb.org/3/movie/$id" +
            "?language=ru-RU&append_to_response=credits"
        val details = get(detailsUrl, token) ?: return candidate
        return parseTmdbMovieDetails(details, candidate) ?: candidate
    }

    fun parseTmdbMovieDetails(body: String, fallback: CatalogHit? = null): CatalogHit? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val id = root.int("id") ?: fallback?.catalogId ?: return@runCatching null
            val russianTitle = root.str("title") ?: fallback?.russianTitle ?: fallback?.title
                ?: return@runCatching null
            val genres = root["genres"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject.str("name") }
                .distinct()
                .take(3)
            val actors = root["credits"]?.jsonObject?.get("cast")?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .sortedBy { it.int("order") ?: Int.MAX_VALUE }
                .mapNotNull { it.str("name") }
                .distinct()
                .take(3)
            val directors = root["credits"]?.jsonObject?.get("crew")?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it.str("job").equals("Director", ignoreCase = true) }
                .mapNotNull { it.str("name") }
                .distinct()
                .take(2)
            val rating = root.double("vote_average")?.takeIf { it > 0.0 } ?: fallback?.rating
            CatalogHit(
                site = "TMDB",
                title = russianTitle,
                year = yearOf(root.str("release_date")) ?: fallback?.year,
                pageUrl = "https://www.themoviedb.org/movie/$id",
                originalTitle = root.str("original_title") ?: fallback?.originalTitle,
                russianTitle = russianTitle,
                originalLanguage = root.str("original_language") ?: fallback?.originalLanguage,
                genres = genres,
                directors = directors,
                actors = actors,
                rating = rating,
                ratingSource = when {
                    root.double("vote_average")?.let { it > 0.0 } == true -> "TMDB"
                    else -> fallback?.ratingSource ?: rating?.let { "TMDB" }
                },
                catalogId = id,
            )
        }.getOrNull()
    }

    private fun searchPoiskKinoMovie(media: MediaInfo): CatalogHit? {
        val token = poiskKinoToken() ?: return null
        val candidates = searchQueries(media.title).flatMap { query ->
            val url = "https://api.poiskkino.dev/v1.4/movie/search?query=${enc(query)}&limit=10"
            val body = get(url, apiKey = token) ?: return@flatMap emptyList()
            parsePoiskKinoSearch(body)
        }.distinctBy { it.catalogId }
        val candidate = pickBest(media, candidates) ?: return null
        val id = candidate.catalogId ?: return candidate
        val details = get("https://api.poiskkino.dev/v1.4/movie/$id", apiKey = token) ?: return candidate
        return parsePoiskKinoMovieDetails(details, candidate) ?: candidate
    }

    fun parsePoiskKinoSearch(body: String): List<CatalogHit> {
        return runCatching {
            json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray.orEmpty()
                .take(10)
                .mapNotNull { element ->
                    runCatching { parsePoiskKinoMovie(element.jsonObject) }.getOrNull()
                }
        }.getOrDefault(emptyList())
    }

    fun parsePoiskKinoMovieDetails(body: String, fallback: CatalogHit? = null): CatalogHit? {
        return runCatching {
            parsePoiskKinoMovie(json.parseToJsonElement(body).jsonObject, fallback)
        }.getOrNull()
    }

    private fun parsePoiskKinoMovie(root: JsonObject, fallback: CatalogHit? = null): CatalogHit? {
        if (root.bool("isSeries") == true) return null
        val type = root.str("type")?.lowercase()
        if (type != null && type !in setOf("movie", "cartoon", "anime")) {
            return null
        }
        val id = root.int("id") ?: fallback?.catalogId ?: return null
        val russianTitle = firstNonBlank(root.str("name"), fallback?.russianTitle, fallback?.title)
            ?: return null
        val originalTitle = firstNonBlank(
            root.str("alternativeName"),
            root.str("enName"),
            fallback?.originalTitle,
        )
        val ratingPair: Pair<Double?, String?> = poiskKinoRating(root)
            ?: fallback?.rating?.let { it to fallback.ratingSource }
            ?: (null to null)
        val rating = ratingPair.first
        val ratingSource = ratingPair.second
        val genres = runCatching { root["genres"]?.jsonArray }.getOrNull().orEmpty()
            .mapNotNull { runCatching { it.jsonObject.str("name")?.let(::prettyGenre) }.getOrNull() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
            .ifEmpty { fallback?.genres.orEmpty() }
        val actors = runCatching { root["persons"]?.jsonArray }.getOrNull().orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .filter(::isPoiskKinoActor)
            .mapNotNull { firstNonBlank(it.str("name"), it.str("enName")) }
            .distinct()
            .take(3)
            .ifEmpty { fallback?.actors.orEmpty() }
        val directors = runCatching { root["persons"]?.jsonArray }.getOrNull().orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .filter(::isPoiskKinoDirector)
            .mapNotNull { firstNonBlank(it.str("name"), it.str("enName")) }
            .distinct()
            .take(2)
            .ifEmpty { fallback?.directors.orEmpty() }
        return CatalogHit(
            site = "ПоискКино",
            title = russianTitle,
            year = root.int("year") ?: fallback?.year,
            pageUrl = "https://www.kinopoisk.ru/film/$id/",
            originalTitle = originalTitle,
            russianTitle = russianTitle,
            originalLanguage = fallback?.originalLanguage,
            genres = genres,
            directors = directors,
            actors = actors,
            rating = rating,
            ratingSource = ratingSource,
            catalogId = id,
        )
    }

    private fun poiskKinoRating(root: JsonObject): Pair<Double, String>? {
        val rating = runCatching { root["rating"]?.jsonObject }.getOrNull() ?: return null
        rating.double("kp")?.takeIf { it > 0.0 }?.let { return it to "КП" }
        rating.double("imdb")?.takeIf { it > 0.0 }?.let { return it to "IMDb" }
        rating.double("tmdb")?.takeIf { it > 0.0 }?.let { return it to "TMDB" }
        return null
    }

    private fun isPoiskKinoActor(person: JsonObject): Boolean {
        val en = person.str("enProfession")?.lowercase().orEmpty()
        val ru = person.str("profession")?.lowercase().orEmpty()
        return en == "actor" || ru == "актеры" || ru == "актёры" || ru.contains("актер")
    }

    private fun isPoiskKinoDirector(person: JsonObject): Boolean {
        val en = person.str("enProfession")?.lowercase().orEmpty()
        val ru = person.str("profession")?.lowercase().orEmpty()
        return en == "director" || ru.contains("режиссер") || ru.contains("режиссёр")
    }

    private fun prettyGenre(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return trimmed
        return trimmed.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.forLanguageTag("ru")) else char.toString()
        }
    }

    private fun searchItunes(media: MediaInfo): List<CatalogHit> {
        val entity = if (media.mediaType == MediaType.TV_EPISODE) "tvSeason" else "movie"
        val query = listOfNotNull(media.title, media.year?.toString()).joinToString(" ")
        val url = "https://itunes.apple.com/search?term=${enc(query)}&entity=$entity&limit=5"
        val body = get(url) ?: return emptyList()
        return runCatching {
            val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
            results.mapNotNull { element ->
                val item = element.jsonObject
                val title = item.str("trackName")
                    ?: item.str("collectionName")
                    ?: item.str("artistName")
                    ?: return@mapNotNull null
                CatalogHit(
                    site = "iTunes",
                    title = cleanTitle(title),
                    year = yearOf(item.str("releaseDate")),
                    pageUrl = item.str("trackViewUrl") ?: item.str("collectionViewUrl"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun searchTvMaze(media: MediaInfo): List<CatalogHit> {
        val url = "https://api.tvmaze.com/search/shows?q=${enc(media.title)}"
        val body = get(url) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                val show = element.jsonObject["show"]?.jsonObject ?: return@mapNotNull null
                val title = show.str("name") ?: return@mapNotNull null
                CatalogHit(
                    site = "TVMaze",
                    title = cleanTitle(title),
                    year = yearOf(show.str("premiered")),
                    pageUrl = show.str("url"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun searchWikipedia(media: MediaInfo, lang: String): List<CatalogHit> {
        val query = listOfNotNull(media.title, media.year?.toString()).joinToString(" ")
        val url = "https://$lang.wikipedia.org/w/api.php?action=opensearch&search=${enc(query)}&limit=5&format=json"
        val body = get(url) ?: return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(body).jsonArray
            val titles = root.getOrNull(1)?.jsonArray.orEmpty()
            val descriptions = root.getOrNull(2)?.jsonArray.orEmpty()
            val urls = root.getOrNull(3)?.jsonArray.orEmpty()
            titles.mapIndexed { index, element ->
                val rawTitle = element.jsonPrimitive.content
                val description = descriptions.getOrNull(index)?.jsonPrimitive?.contentOrNull.orEmpty()
                CatalogHit(
                    site = "Wikipedia ($lang)",
                    title = cleanTitle(rawTitle),
                    year = yearOf(description) ?: yearOf(rawTitle),
                    pageUrl = urls.getOrNull(index)?.jsonPrimitive?.contentOrNull,
                )
            }
        }.getOrDefault(emptyList())
    }

    // 1000.01 Wikipedia: «Dune (2021 film)» → «Dune», без второго года в имени файла.
    fun cleanTitle(title: String): String {
        return title
            .replace(
                Regex(
                    """(?iu)\s*\((?:[^)]*\b(?:film|movie|сериал|телесериал|мини-?сериал|фильм|tv series|television series|miniseries)\b[^)]*)\)\s*$""",
                ),
                "",
            )
            .trim()
    }

    private fun yearOf(value: String?): Int? {
        return value?.let {
            Regex("""\b(18(?:8[8-9]|9\d)|19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull()
        }
    }

    private fun JsonObject.str(key: String): String? {
        return runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    private fun JsonObject.int(key: String): Int? {
        return runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        return runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()
    }

    private fun JsonObject.double(key: String): Double? {
        return runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()
    }

    private fun enc(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private fun tmdbToken(): String? {
        val raw = System.getenv("TMDB_API_TOKEN")
            ?.takeIf(String::isNotBlank)
            ?: System.getProperty("tmdb.api.token")?.takeIf(String::isNotBlank)
        return raw?.trim()?.removePrefix("Bearer ")?.takeIf(String::isNotBlank)
    }

    private fun poiskKinoToken(): String? {
        val raw = System.getenv("POISKKINO_API_TOKEN")
            ?.takeIf(String::isNotBlank)
            ?: System.getProperty("poiskkino.api.token")?.takeIf(String::isNotBlank)
        return raw?.trim()?.takeIf(String::isNotBlank)
    }

    private fun get(url: String, bearerToken: String? = null, apiKey: String? = null): String? {
        httpRequests.incrementAndGet()
        return runCatching {
            Thread.sleep(120)
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "MovieRenamer/1.0 (home media library; title lookup)")
                .GET()
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer $bearerToken")
            }
            if (apiKey != null) {
                builder.header("X-API-KEY", apiKey)
            }
            val request = builder.build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val code = response.statusCode()
            if (code in 200..299) {
                response.body()
            } else {
                if (url.contains("themoviedb.org")) {
                    Talk.error("TMDB ответил HTTP $code")
                }
                null
            }
        }.onFailure { error ->
            if (url.contains("themoviedb.org")) {
                Talk.error("TMDB запрос не удался: ${error.message ?: error.javaClass.simpleName}")
            }
        }.getOrNull()
    }
}
