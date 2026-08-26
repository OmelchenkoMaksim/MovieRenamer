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
        if (settings.mode == WorkMode.DEBUG_REVERT) {
            revertDebugNames(settings)
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
                val extras = if (Config.useFallbackCatalogs) {
                    "; запасные каталоги: iTunes, TVMaze, Wikipedia"
                } else {
                    ""
                }
                Talk.info("Онлайн-поиск: ${catalogs.joinToString(" и ")}$extras")
            } else {
                Talk.info(
                    "TMDB и ПоискКино выключены: задайте токены для русских названий, жанров, актёров и рейтинга",
                )
                if (Config.useFallbackCatalogs) {
                    Talk.info("Запасной онлайн-поиск: iTunes, TVMaze, Wikipedia")
                }
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
        val historySnapshot = NameHistory.load()
        val remembered = if (
            historySnapshot.directory?.toAbsolutePath()?.normalize() == moviesDirectory
        ) {
            historySnapshot.pairs.toMutableMap()
        } else {
            linkedMapOf()
        }
        var historyDirectory: Path? = null

        plans.forEachIndexed { index, plan ->
            MediaPrinter.print(index, plan, settings.mode, lookupOnline)

            when (settings.mode) {
                WorkMode.PREVIEW, WorkMode.REVERT, WorkMode.DEBUG_REVERT -> Unit
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
                                currentRelative = plan.proposedName,
                                sourceRelative = plan.file.fileName.toString(),
                                sourceFileName = plan.file.fileName.toString(),
                            )
                            historyDirectory = results
                            NameHistory.save(results, remembered, Config.debugRevertCache)
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
                                currentRelative = NameHistory.relativeKey(moviesDirectory, target),
                                sourceRelative = NameHistory.relativeKey(moviesDirectory, plan.file),
                                sourceFileName = plan.file.fileName.toString(),
                            )
                            historyDirectory = moviesDirectory
                            NameHistory.save(moviesDirectory, remembered)
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
                val cache = if (debug) Config.debugRevertCache else Config.revertCache
                NameHistory.save(directory, remembered, cache)
            }
        }

        TitleCatalog.silencedServices().forEach { service ->
            Talk.error("Каталог $service отвалился посреди прогона — часть файлов осталась без данных")
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
            WorkMode.RENAME -> {
                FileGuard.rename(library, plan.file, target)
                FileGuard.renameSidecars(library, plan.file, target)
            }
            WorkMode.COPY -> {
                FileGuard.copy(library, plan.file, target)
                FileGuard.copySidecars(library, plan.file, target)
            }
            WorkMode.PREVIEW, WorkMode.DEBUG, WorkMode.DEBUG_REVERT, WorkMode.REVERT -> Unit
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

    private fun revertDebugNames(settings: LaunchSettings) {
        Talk.info("Запуск Movie Renamer")
        Talk.info("Режим: ${settings.mode.displayName}")
        val results = Config.debugResults.toAbsolutePath().normalize()
        val reverted = Config.debugReverted.toAbsolutePath().normalize()
        Talk.info("Читаем результаты DEBUG: $results")
        Talk.info("Пишем откат: $reverted")

        if (!results.isDirectory()) {
            Talk.info("debug/results пуста — сначала запустите DEBUG. DEBUG_REVERT сам его не запускает")
            printReport(
                RunReport(
                    mode = settings.mode,
                    directory = results,
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

        val videoFiles = try {
            VideoScanner.findVideoFiles(results)
        } catch (exception: Exception) {
            Talk.error("Ошибка сканирования: ${exception.message}")
            return
        }
        if (videoFiles.isEmpty()) {
            Talk.info("debug/results пуста — сначала запустите DEBUG. DEBUG_REVERT сам его не запускает")
            printReport(
                RunReport(
                    mode = settings.mode,
                    directory = results,
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

        val snapshot = loadDebugRevertSnapshot(results)
        if (snapshot.pairs.isEmpty()) {
            Talk.info("Кэш имён DEBUG пуст (${Config.debugRevertCache}) — откатывать нечего")
            printReport(
                RunReport(
                    mode = settings.mode,
                    directory = results,
                    filesRead = videoFiles.size,
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

        Talk.info("Пар в кэше DEBUG: ${snapshot.pairs.size}")
        FileGuard.prepareRevertedDirectory(reverted)
        Talk.info("Найдено файлов: ${videoFiles.size}")
        println()

        val plans = RevertPlanner.planAll(results, videoFiles, snapshot.pairs)
        val errors = mutableListOf<FileIssue>()
        var writtenResults = 0

        plans.forEachIndexed { index, plan ->
            MediaPrinter.print(index, plan, settings.mode)
            if (plan.status != PlanStatus.READY && plan.status != PlanStatus.ALREADY_OK) {
                return@forEachIndexed
            }
            try {
                FileGuard.copyToReverted(
                    sourceLibrary = results,
                    from = plan.file,
                    revertedDirectory = reverted,
                    originalName = plan.proposedName,
                )
                writtenResults++
            } catch (exception: Exception) {
                val message = exception.message ?: "неизвестная ошибка"
                Talk.error("Не удалось вернуть ${plan.file.fileName}: $message")
                errors += FileIssue(plan.file, message)
            }
        }

        Talk.info("Сравните $reverted с ${Config.debugSamples.toAbsolutePath().normalize()} — имена должны совпасть")
        printReport(
            RunReport(
                mode = settings.mode,
                directory = results,
                filesRead = videoFiles.size,
                apiRequests = 0,
                parsed = plans.count { it.status != PlanStatus.UNCLEAR },
                unclear = plans.count { it.status == PlanStatus.UNCLEAR },
                changed = 0,
                writtenResults = writtenResults,
                errors = errors,
                unclearFiles = plans.filter { it.status == PlanStatus.UNCLEAR }.map { it.file },
            ),
            resultsDirectory = reverted,
        )
    }

    private fun loadDebugRevertSnapshot(results: Path): RevertSnapshot {
        val debug = NameHistory.load(Config.debugRevertCache)
        if (debug.pairs.isNotEmpty()) return debug
        val fallback = NameHistory.load(Config.revertCache)
        val fromResults = fallback.directory?.toAbsolutePath()?.normalize() == results
        if (fallback.pairs.isNotEmpty() && fromResults) {
            Talk.info("Кэш DEBUG пуст, читаем пары из ${Config.revertCache}")
            return fallback
        }
        return debug
    }

    private fun rememberRename(
        remembered: MutableMap<String, String>,
        currentRelative: String,
        sourceRelative: String,
        sourceFileName: String,
    ) {
        if (currentRelative.isBlank() || sourceRelative.isBlank() || currentRelative == sourceRelative) return
        val original = remembered[sourceRelative]
            ?: remembered[sourceFileName]
            ?: sourceFileName
        remembered.remove(sourceRelative)
        remembered[currentRelative] = original
    }

    private fun printReport(report: RunReport, resultsDirectory: Path?) {
        println()
        ReportPrinter.print(report)
        if (
            (report.mode == WorkMode.DEBUG || report.mode == WorkMode.DEBUG_REVERT) &&
            resultsDirectory != null
        ) {
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
    val debugReverted: Path = Path.of("debug", "reverted")
    val revertCache: Path = Path.of("debug", "revert-cache.json")
    val debugRevertCache: Path = Path.of("debug", "debug-revert-cache.json")

    // iTunes и Wikipedia дают только название и год. Три лишних запроса на промах дороже пользы.
    const val useFallbackCatalogs: Boolean = false

    // Фильм без ответа каталога переименовывать нечем: получится голое имя.
    const val renameWithoutCatalog: Boolean = false

    // Печатать каждый запрос к каталогу. Включать точечно, когда фильм «не нашёлся».
    const val logCatalogQueries: Boolean = false
}

// 0001.04 Режим задаётся в Main.kt.
enum class WorkMode(val displayName: String) {
    PREVIEW("просмотр: что можно сделать"),
    RENAME("переименовать на месте"),
    COPY("копия с новым именем, оригинал оставить"),
    DEBUG("тренировка: samples читаем, results пишем"),
    DEBUG_REVERT("тренировка отката: results читаем, reverted пишем"),
    REVERT("вернуть прошлые имена"),
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
    UNCLEAR(false, "не разобрали — не трогаем"),
    BLOCKED(false, "не получится"),
}

data class RenamePlan(
    val file: Path,
    val media: MediaInfo,
    val proposedName: String,
    val status: PlanStatus,
    val reasons: List<String>,
    val catalog: CatalogHit? = null,
    val note: String? = null,
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
    val part: Int? = null,
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
    private val sidecarExtensions = listOf("srt", "ass", "sub", "idx", "nfo", "smi")
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

    fun renameSidecars(library: Path, videoFrom: Path, videoTo: Path) {
        sidecarPairs(videoFrom, videoTo).forEach { (from, to) ->
            runCatching { rename(library, from, to) }
        }
    }

    fun copySidecars(library: Path, videoFrom: Path, videoTo: Path) {
        sidecarPairs(videoFrom, videoTo).forEach { (from, to) ->
            runCatching { copy(library, from, to) }
        }
    }

    private fun sidecarPairs(videoFrom: Path, videoTo: Path): List<Pair<Path, Path>> {
        val parent = videoFrom.parent ?: return emptyList()
        val fromStem = videoFrom.nameWithoutExtension
        val toStem = videoTo.nameWithoutExtension
        if (fromStem.isBlank() || fromStem == toStem) return emptyList()
        return sidecarExtensions.mapNotNull { ext ->
            val from = parent.resolve("$fromStem.$ext")
            if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
            from to parent.resolve("$toStem.$ext")
        }
    }

    fun isDebugResultsPath(directory: Path): Boolean {
        return isPinnedDebugPath(directory, Config.debugResults)
    }

    fun isDebugRevertedPath(directory: Path): Boolean {
        return isPinnedDebugPath(directory, Config.debugReverted)
    }

    private fun isPinnedDebugPath(directory: Path, expected: Path): Boolean {
        return directory.toAbsolutePath().normalize() == expected.toAbsolutePath().normalize()
    }

    // 0100.02.01 Только debug/results. Исходники samples не чистим и не пишем.
    fun prepareResultsDirectory(directory: Path) {
        prepareDebugOutputDirectory(directory, Config.debugResults, "debug/results")
    }

    fun prepareRevertedDirectory(directory: Path) {
        prepareDebugOutputDirectory(directory, Config.debugReverted, "debug/reverted")
    }

    private fun prepareDebugOutputDirectory(directory: Path, expected: Path, label: String) {
        val root = directory.toAbsolutePath().normalize()
        checkDebugOutputDirectory(root, expected, label)
        Files.createDirectories(root)
        check(!Files.isSymbolicLink(root) && isInside(Path.of("").toAbsolutePath(), root)) {
            "$label не должен быть симлинком или вести за пределы проекта: $root"
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
        copyToDebugOutput(
            sourceLibrary = sourceLibrary,
            from = from,
            outputDirectory = resultsDirectory,
            expectedOutput = Config.debugResults,
            newName = newName,
            label = "debug/results",
        )
    }

    fun copyToReverted(
        sourceLibrary: Path,
        from: Path,
        revertedDirectory: Path,
        originalName: String,
    ) {
        copyToDebugOutput(
            sourceLibrary = sourceLibrary,
            from = from,
            outputDirectory = revertedDirectory,
            expectedOutput = Config.debugReverted,
            newName = originalName,
            label = "debug/reverted",
        )
    }

    private fun copyToDebugOutput(
        sourceLibrary: Path,
        from: Path,
        outputDirectory: Path,
        expectedOutput: Path,
        newName: String,
        label: String,
    ) {
        val destRoot = outputDirectory.toAbsolutePath().normalize()
        checkDebugOutputDirectory(destRoot, expectedOutput, label)
        check(Files.isDirectory(destRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(destRoot)) {
            "Папка $label не готова или является симлинком: $destRoot"
        }
        val source = from.toAbsolutePath().normalize()
        check(isInside(sourceLibrary, source)) {
            "Читаем только файлы из $sourceLibrary: $source"
        }
        val target = destRoot.resolve(newName).normalize()
        check(isInside(destRoot, target)) {
            "Не выходим из $label"
        }
        check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            "Источник не обычный файл: $source"
        }
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "Не перезаписываем существующий результат: $target"
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
    }

    private fun checkDebugOutputDirectory(directory: Path, expected: Path, label: String) {
        check(isPinnedDebugPath(directory, expected)) {
            "Пишем только в ${expected.toAbsolutePath().normalize()}, а не в $directory"
        }
        val projectRoot = Path.of("").toAbsolutePath().normalize()
        val parent = directory.parent
        check(parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            "Родительская папка $label не найдена: $parent"
        }
        check(!Files.isSymbolicLink(parent) && isInside(projectRoot, directory)) {
            "$label не должен вести за пределы проекта: $directory"
        }
        check(!Files.isSymbolicLink(directory)) {
            "$label не должен быть симлинком: $directory"
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
    private const val MIN_MOVIE_BYTES = 150L * 1024 * 1024
    private val junkNames = Regex("""(?iu)^(sample|trailer|rarbg|proof|screens?)\b""")
    private val junkFolders = setOf("extras", "featurettes", "trailers", "proof", "screens")

    fun findVideoFiles(directory: Path): List<Path> {
        val root = directory.toAbsolutePath().normalize()
        return FileGuard.listRegularFiles(directory)
            .filter { it.extension.lowercase() in Config.videoExtensions }
            .filterNot { isJunkVideo(root, it) }
            .sortedBy { it.toString().lowercase() }
    }

    private fun isJunkVideo(root: Path, path: Path): Boolean {
        val insideJunkFolder = generateSequence(path.parent) { it.parent }
            .takeWhile { it.startsWith(root) && it != root }
            .any { it.fileName?.toString()?.lowercase() in junkFolders }
        if (insideJunkFolder) return true
        if (!junkNames.containsMatchIn(path.nameWithoutExtension)) return false
        val size = runCatching { Files.size(path) }.getOrDefault(Long.MAX_VALUE)
        return size < MIN_MOVIE_BYTES
    }
}

object MediaParser {
    private val asciiFlags = setOf(RegexOption.IGNORE_CASE)

    // 0100.03 Граница ASCII-токена: «Дюна2021» и «СериалS01E01» всё ещё режутся верно.
    private const val ASCII_START = """(?<![A-Za-z0-9])"""
    private const val ASCII_END = """(?![A-Za-z0-9])"""

    private val yearRegex = Regex("""$ASCII_START(18(?:8[8-9]|9\d)|19\d{2}|20\d{2})$ASCII_END""")
    private val fourDigitNumberRegex = Regex("""$ASCII_START(\d{4})$ASCII_END""")
    private val seasonEpisodeRegex = Regex("""${ASCII_START}S(\d{1,2})E(\d{1,3})$ASCII_END""", asciiFlags)
    private val seasonRegex = Regex("""${ASCII_START}S\d{1,2}$ASCII_END""", asciiFlags)
    private val resolutionRegex = Regex(
        """$ASCII_START(480i|480p|540p|576i|576p|720p|1080p|1440p|2160p|480|720|1080|2160|2K|4K|UHD|FullHD)$ASCII_END""",
        asciiFlags,
    )
    private val leadingResolutionRegex = Regex(
        """^[\p{Zs}\s]*\[(480i|480p|540p|576i|576p|720p|1080p|1440p|2160p|480|720|1080|2160|2K|4K|UHD|FullHD)][\p{Zs}\s]*""",
        asciiFlags,
    )
    private val sourceRegex = Regex(
        """$ASCII_START(WEB[ .\-\p{Pd}]?DL(?:[ .\-\p{Pd}]?RIP)?|WEB[ .\-\p{Pd}]?RIP|BLU[ .\-\p{Pd}]?RAY|BDRIP|BRRIP|HDDVD(?:[ .\-\p{Pd}]?RIP)?|HD[ .\-\p{Pd}]DVD(?:[ .\-\p{Pd}]?RIP)?|HDRIP|DVDRIP|HDTV(?:[ .\-\p{Pd}]?RIP)?|4KRIP|UHDRIP|BDREMUX|DVDREMUX|REMUX|CAMRIP|SATRIP|DCPRIP|TVRIP|VHSRIP)$ASCII_END""",
        asciiFlags,
    )
    private val techTagRegex = Regex(
        """$ASCII_START(DVD[59]|DXVA|AMZN|NF|IVI|X264|X265|H[.\-]?264|H[.\-]?265|HEVC|10BIT|TRUEHD|DTS|AC3|AAC|FLAC|XVID|DIVX|FPS)$ASCII_END""",
        asciiFlags,
    )
    private val sitePrefixRegex = Regex(
        """^(?:www )?[\p{L}\p{N}-]+ (?:org|com|net|ru|info|tv|me|cc|biz) """,
        asciiFlags,
    )
    private val siteSuffixRegex = Regex(
        """(?iu)[\s._]*[\[(](?:www[.\s])?[\p{L}\p{N}-]+\.(?:org|com|net|ru|info|tv|me|cc|biz)[)\]]+$""",
    )
    private val trailingSceneGroupRegex = Regex(
        """[\s._]+([A-Za-z]{2,5}-[A-Za-z0-9]{2,5}|[A-Z]{5,}|[A-Za-z]+\d{2,})$""",
    )
    // (2001), (2001г), (2001 г.)
    private val wrappedYearRegex = Regex(
        """[(\[]\s*(18(?:8[8-9]|9\d)|19\d{2}|20\d{2})(?:\s*г(?:ода?)?\.?)?\s*[)\]]""",
    )
    // (2001г 1080p) — год и теги качества в одних скобках.
    private val wrappedYearWithTagsRegex = Regex(
        """[(\[]\s*(18(?:8[8-9]|9\d)|19\d{2}|20\d{2})(?:\s*г(?:ода?)?\.?)?\s+([^)\]]+)[)\]]""",
    )
    // «Название 1984 (1984)» — год продублировали. Схлопываем, иначе первый уедет в название.
    private val repeatedYearRegex = Regex(
        """(?<![A-Za-z0-9])(1[89]\d{2}|20\d{2})(?:[\s,]+\1)+(?![A-Za-z0-9])""",
    )
    private val leadingBracketGroupRegex = Regex("""^\s*[\[(]([^)\]]{1,40})[)\]]\s*""")
    private val pixelSizeRegex = Regex("""$ASCII_START(\d{3,4})x(\d{3,4})$ASCII_END""", asciiFlags)
    private val tmdbHintRegex = Regex("""(?i)[\[{(]tmdb[-=: ](\d{1,9})[]})]""")
    private val latinPartRegex = Regex(
        """(?iu)(?<![\p{L}\p{N}])(?:CD|DISC|DISK|PT)[ .\-_]?(\d{1,2})(?![\p{L}\p{N}])""",
    )
    private val russianPartRegex = Regex(
        """(?iu)(?<![\p{L}\p{N}])(\d{1,2})\s*-?\s*(?:я|ая|ья|ой|ый|е|ое)?\s*(?:серия|часть|диск)(?![\p{L}\p{N}])""",
    )
    private val russianPartWordFirstRegex = Regex(
        """(?iu)(?<![\p{L}\p{N}])(?:серия|часть|диск)\s*№?\s*(\d{1,2})(?![\p{L}\p{N}])""",
    )
    private val genericFolderNames = setOf(
        "samples", "sample", "movies", "movie", "video", "videos",
        "tv", "series", "shows", "downloads", "download", "media",
        "library", "debug", "temp", "tmp", "files",
        "фильмы", "сериалы", "видео", "загрузки",
    )
    private val seasonFolderRegex = Regex("""(?iu)^(?:(?:season|сезон)\s*\d{1,3}|S\d{1,2})$""")
    private val editionRegex = Regex(
        """$ASCII_START(""" +
            """(?:THE[ .\-\p{Pd}]+)?(?:PENULTIMATE|FINAL|ULTIMATE)[ .\-\p{Pd}]+CUT|""" +
            """SPECIAL[ .\-\p{Pd}]?EDITION|COLLECTOR'?S[ .\-\p{Pd}]?(?:EDITION|CUT)|""" +
            """ULTIMATE[ .\-\p{Pd}]?EDITION|ANNIVERSARY[ .\-\p{Pd}]?EDITION|""" +
            """DELUXE[ .\-\p{Pd}]?EDITION|UNCUT|REDUX|IMAX|""" +
            """OPEN[ .\-\p{Pd}]?MATTE|UNRATED|EXTENDED(?:[ .\-\p{Pd}]?EDITION)?|""" +
            """(?:THE[ .\-\p{Pd}]+)?DIRECTOR'?S[ .\-\p{Pd}]?CUT|X[ .\-\p{Pd}]?CUT|""" +
            """THEATRICAL(?:[ .\-\p{Pd}]?CUT)?|REMASTERED|RECOBBLED[ .\-\p{Pd}]?CUT|""" +
            """MARK[ .\-\p{Pd}]?(?:IV|4|V|5)|FULL[ .\-\p{Pd}]?SCREEN""" +
            """)$ASCII_END""",
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
    // .by.Junk666 / .by.Martokc — одна метка группы в конце, не «by» внутри названия.
    private val sceneByGroupRegex = Regex("""(?iu)[._]by[._]([\p{L}\p{N}-]+)$""")
    private val spacedByGroupRegex = Regex("""(?iu)[\p{Zs}\s]+by[\p{Zs}\s]+([\p{L}\p{N}-]+)$""")

    fun tmdbHint(path: Path): Int? =
        tmdbHintRegex.find(normalizeUnicode(path.nameWithoutExtension))
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

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
            resolution = (
                resolutionRegex.find(metadataText)?.value
                    ?: pixelSizeRegex.find(metadataText)?.let { heightToResolution(it.groupValues[2]) }
                    ?: leadingResolution
                )?.let(::normalizeResolution),
            source = sourceRegex.find(metadataText)?.value?.let(::normalizeSource),
            editions = editionRegex.findAll(metadataText)
                .filter { it.range.first > 0 }
                .map { normalizeEdition(it.value) }
                .distinct()
                .toList(),
            // Язык берём только из имени файла: название родительской папки может быть Russian Doll.
            languages = languageMatches(fileName)
                .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeLanguage) }
                .distinct()
                .toList(),
            part = extractPart(fileName),
        )
    }

    // 0100.03.03 Название фильма — всё до года релиза (последний год перед качеством).
    // Если до года ничего нет (1917.1080p), сам год — это название, не мусор с тегами.
    private fun extractMovieTitle(fileName: String): String {
        val title = fileName
            .substring(0, movieTitleEndIndex(fileName))
            .trimReleaseSeparators()
        if (title.isNotBlank()) return title
        return lastPlausibleYear(fileName.substring(0, firstQualityIndex(fileName)))?.value
            ?: fileName
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
            firstEditionIndex(value),
            firstPartIndex(value),
            pixelSizeRegex.find(value)?.range?.first,
        ).minOrNull() ?: value.length
    }

    // 0100.03.05 Год релиза — последний 1888…сейчас+1 до тегов качества: 2001 и 2049 остаются в названии.
    // Издание (Penultimate Cut) режет название, но год после него всё равно читаем.
    private fun movieTitleEndIndex(fileName: String): Int {
        val qualityIndex = firstQualityIndex(fileName)
        val editionIndex = firstEditionIndex(fileName) ?: qualityIndex
        val partIndex = firstPartIndex(fileName) ?: qualityIndex
        val lastYear = lastPlausibleYear(fileName.substring(0, qualityIndex))
        val yearIndex = lastYear?.range?.first ?: qualityIndex
        return minOf(qualityIndex, editionIndex, partIndex, yearIndex)
    }

    private fun extractReleaseYear(text: String): Int? {
        val region = text.substring(0, firstQualityIndex(text))
        confidentYearFromFourDigits(region)?.let { return it }
        return releaseYearFallback(region)
    }

    // Ровно одна четвёрка цифр 1901…2049 — с высокой вероятностью год релиза.
    // Две такие — не гадаем: это часто часть названия (2001, 2049).
    private fun confidentYearFromFourDigits(region: String): Int? {
        val matches = fourDigitNumberRegex.findAll(region)
            .filter { match ->
                val value = match.value.toInt()
                value > 1900 && value < 2050
            }
            .toList()
        if (matches.size != 1) return null
        val match = matches.single()
        val beforeYear = region.substring(0, match.range.first).trimReleaseSeparators()
        if (beforeYear.isBlank()) return null
        return match.value.toInt()
    }

    private fun releaseYearFallback(region: String): Int? {
        val years = plausibleYears(region)
        if (years.isEmpty()) return null
        if (years.size >= 2) return years.last().value.toInt()
        val beforeYear = region.substring(0, years.first().range.first).trimReleaseSeparators()
        return if (beforeYear.isBlank()) null else years.first().value.toInt()
    }

    private fun firstQualityIndex(value: String): Int {
        return listOfNotNull(
            seasonEpisodeRegex.find(value)?.range?.first,
            seasonRegex.find(value)?.range?.first,
            resolutionRegex.find(value)?.range?.first,
            sourceRegex.find(value)?.range?.first,
            techTagRegex.find(value)?.range?.first,
            pixelSizeRegex.find(value)?.range?.first,
        ).minOrNull() ?: value.length
    }

    // Издание в начале имени — это название фильма (The Final Cut), а не тег.
    private fun firstEditionIndex(value: String): Int? {
        return editionRegex.findAll(value).firstOrNull { it.range.first > 0 }?.range?.first
    }

    private fun firstPartIndex(value: String): Int? {
        return listOf(latinPartRegex, russianPartWordFirstRegex, russianPartRegex)
            .mapNotNull { regex -> regex.find(value)?.range?.first }
            .filter { it > 0 }
            .minOrNull()
    }

    private fun extractPart(value: String): Int? {
        return listOf(latinPartRegex, russianPartWordFirstRegex, russianPartRegex)
            .firstNotNullOfOrNull { it.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?.takeIf { it in 1..20 }
    }

    private fun languageMatches(value: String): Sequence<MatchResult> {
        val metadataStart = listOfNotNull(
            firstPlausibleYear(value)?.range?.first,
            resolutionRegex.find(value)?.range?.first,
            sourceRegex.find(value)?.range?.first,
            firstEditionIndex(value),
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
            .replace(tmdbHintRegex, " ")
            .replace(leadingResolutionRegex, "")
            .let(::stripLeadingGroup)
            .let(::stripReleaseGroupSuffix)
            .replace(Regex("""\p{Pd}"""), "-")
            .replace('.', ' ')
            .replace('\uFF0E', ' ')
            .replace('_', ' ')
            .replace('\uFF3F', ' ')
            .replace(wrappedYearWithTagsRegex, " $1 $2 ")
            .replace(wrappedYearRegex, " $1 ")
            .replace(Regex("""[\p{Zs}\s]+"""), " ")
            .replace(repeatedYearRegex) { it.groupValues[1] }
            .replace(sitePrefixRegex, "")
            .replace(siteSuffixRegex, "")
            .let(::stripReleaseGroupSuffix)
            .replace(Regex("""[\p{Zs}\s]+"""), " ")
            .trim()
    }

    private fun stripLeadingGroup(value: String): String {
        val match = leadingBracketGroupRegex.find(value) ?: return value
        val inside = match.groupValues[1]
        if (plausibleYears(inside).isNotEmpty()) return value
        if (isQualityToken(inside)) return value
        if (inside.none(Char::isLetter)) return value
        return value.removeRange(match.range).trim()
    }

    private fun stripReleaseGroupSuffix(value: String): String {
        var text = value.trim('.', '_', ' ')
        repeat(4) {
            val next = stripReleaseGroupSuffixOnce(text)
            if (next == text) return text
            text = next
        }
        return text
    }

    private fun stripReleaseGroupSuffixOnce(value: String): String {
        val scene = sceneByGroupRegex.find(value)
        if (scene != null && looksLikeReleaseGroup(scene.groupValues[1])) {
            return value.removeRange(scene.range).trim('.', '_', ' ')
        }
        val spaced = spacedByGroupRegex.find(value)
        if (spaced != null && looksLikeReleaseGroup(spaced.groupValues[1])) {
            return value.removeRange(spaced.range).trim('.', '_', ' ')
        }
        val trailing = trailingSceneGroupRegex.find(value)
        if (trailing != null && looksLikeSceneTag(trailing.groupValues[1])) {
            return value.removeRange(trailing.range).trim('.', '_', ' ')
        }
        val site = siteSuffixRegex.find(value)
        if (site != null) {
            return value.removeRange(site.range).trim('.', '_', ' ', '[', ']')
        }
        return value
    }

    private fun looksLikeSceneTag(token: String): Boolean {
        if (isQualityToken(token)) return false
        if (token.any { it.isDigit() }) return true
        if (token.length >= 5 && token.all { it.isLetter() } && token == token.uppercase()) return true
        if (token.contains('-') && token.none { it.isUpperCase() } && token.length <= 12) return true
        return false
    }

    private fun isQualityToken(token: String): Boolean {
        return resolutionRegex.containsMatchIn(token) ||
            sourceRegex.containsMatchIn(token) ||
            editionRegex.containsMatchIn(token) ||
            techTagRegex.containsMatchIn(token) ||
            pixelSizeRegex.containsMatchIn(token)
    }

    private fun looksLikeReleaseGroup(token: String): Boolean {
        return token.any { it.isDigit() } || token.length >= 5
    }

    private fun normalizeUnicode(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u200B-\u200D\u2060\uFEFF\u00AD]"""), "")
    }

    private fun normalizeResolution(value: String): String {
        return when (value.uppercase()) {
            "4K" -> "4K"
            "UHD" -> "UHD"
            "2K" -> "2K"
            "FULLHD" -> "1080p"
            "480" -> "480p"
            "720" -> "720p"
            "1080" -> "1080p"
            "2160" -> "2160p"
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
            "HDDVDRIP" -> "HDDVDRip"
            "HDDVD" -> "HDDVD"
            "HDRIP" -> "HDRip"
            "DVDRIP" -> "DVDRip"
            "HDTVRIP" -> "HDTVRip"
            "HDTV" -> "HDTV"
            "4KRIP" -> "4KRip"
            "UHDRIP" -> "UHDRip"
            "BDREMUX" -> "BDRemux"
            "DVDREMUX" -> "DVDRemux"
            "REMUX" -> "Remux"
            "CAMRIP" -> "CAMRip"
            "SATRIP" -> "SATRip"
            "DCPRIP" -> "DCPRip"
            "TVRIP" -> "TVRip"
            "VHSRIP" -> "VHSRip"
            else -> value
        }
    }

    private fun heightToResolution(height: String): String? {
        val value = height.toIntOrNull() ?: return null
        return when (value) {
            in 2100..2200 -> "2160p"
            in 1400..1500 -> "1440p"
            in 1000..1100 -> "1080p"
            in 700..800 -> "720p"
            in 560..600 -> "576p"
            in 470..500 -> "480p"
            else -> null
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
            compact == "UNCUT" -> "Uncut"
            compact == "REDUX" -> "Redux"
            compact == "IMAX" -> "IMAX"
            compact.startsWith("SPECIAL") -> "Special Edition"
            compact.startsWith("COLLECTOR") -> "Collector's Edition"
            compact.startsWith("ANNIVERSARY") -> "Anniversary Edition"
            compact.startsWith("DELUXE") -> "Deluxe Edition"
            compact == "ULTIMATE EDITION" -> "Ultimate Edition"
            compact.startsWith("EXTENDED") -> "Extended"
            compact.contains("DIRECTOR") -> "Director's Cut"
            compact.contains("PENULTIMATE") -> "Penultimate Cut"
            compact.contains("ULTIMATE") && compact.contains("CUT") -> "Ultimate Cut"
            compact.contains("FINAL") && compact.contains("CUT") -> "Final Cut"
            compact == "X CUT" -> "X Cut"
            compact.startsWith("THEATRICAL") -> "Theatrical"
            compact == "REMASTERED" -> "Remastered"
            compact.startsWith("RECOBBLED") -> "Recobbled Cut"
            compact == "MARK IV" || compact == "MARK 4" -> "Mark IV"
            compact == "MARK V" || compact == "MARK 5" -> "Mark V"
            compact == "FULL SCREEN" -> "Full Screen"
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
    }.trimStart(')', ']').trimEnd('(', '[').trim()
}

object NameFormatter {
    private const val MAX_FILE_NAME_LENGTH = 240
    private const val KEEP_EDITIONS = true
    private const val KEEP_SOURCE = true
    private const val KEEP_LANGUAGES = false

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
                add("[${media.genres.take(3).joinToString(", ", transform = ::prettyGenre)}]")
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
            addAll(technicalTail(media))
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

    private fun prettyGenre(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return trimmed
        return trimmed.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.forLanguageTag("ru")) else char.toString()
        }
    }

    private fun episodeStem(media: MediaInfo): String {
        val code = "S%02dE%02d".format(media.season ?: 0, media.episode ?: 0)
        return buildList {
            add(media.title)
            add(code)
            media.episodeTitle?.let(::add)
            addAll(technicalTail(media))
        }.joinToString(" ")
    }

    // Extended / 1080p / WEB-DL — парсер читает хвост обратно, второй прогон даёт то же имя.
    private fun technicalTail(media: MediaInfo): List<String> {
        return buildList {
            if (KEEP_EDITIONS) media.editions.forEach { add(it) }
            media.resolution?.let(::add)
            if (KEEP_SOURCE) media.source?.let(::add)
            if (KEEP_LANGUAGES) {
                val tags = media.languages.mapNotNull(::languageTag)
                if (tags.isNotEmpty()) add(tags.joinToString("+"))
            }
            media.part?.let { add("[Часть $it]") }
        }.filter { it.isNotBlank() }
    }

    private fun languageTag(code: String): String? = when (code.uppercase()) {
        "RU" -> "RUS"
        "EN" -> "ENG"
        "UK" -> "UKR"
        "DE" -> "GER"
        "FR" -> "FRE"
        "JA" -> "JPN"
        else -> null
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
        val hint = MediaParser.tmdbHint(file)
        val catalog = when {
            !lookupOnline -> null
            hint != null -> TitleCatalog.findById(hint, parsed.mediaType == MediaType.TV_EPISODE)
            else -> TitleCatalog.find(parsed)
        }
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
        val catalogMissed = lookupOnline &&
            catalog == null &&
            media.mediaType == MediaType.MOVIE
        if (catalogMissed && !Config.renameWithoutCatalog) {
            reasons += "каталог не нашёл фильм — в имени будет пусто"
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
            note = if (catalog == null) TitleCatalog.noteFor(parsed) else null,
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
        val absolute = file.toAbsolutePath().normalize()
        absolute.parent?.let(Files::createDirectories)
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
        val temporary = absolute.resolveSibling(absolute.fileName.toString() + ".tmp")
        Files.writeString(temporary, payload.toString() + "\n", StandardCharsets.UTF_8)
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
    }
}

data class RevertSnapshot(
    val directory: Path?,
    val pairs: Map<String, String>,
)

object MediaPrinter {
    fun print(index: Int, plan: RenamePlan, mode: WorkMode, lookupOnline: Boolean = false) {
        val media = plan.media
        println("==================================================")
        println("${index + 1}. ${plan.file.fileName}")
        println("Статус: ${plan.status.displayName}")
        if (plan.status == PlanStatus.UNCLEAR) {
            println("Имя не разбирается — оставляем файл как есть")
            if (plan.reasons.isNotEmpty()) {
                println("Почему: ${plan.reasons.joinToString("; ")}")
            }
            if (media.title.isNotBlank() && media.title != "Название не определено") {
                println("Название: ${media.title}")
            }
            printCatalogLine(plan, lookupOnline)
            if (mode == WorkMode.DEBUG || mode == WorkMode.DEBUG_REVERT) {
                println("Полный путь: ${plan.file}")
            }
            return
        }
        println("Новое имя: ${plan.proposedName}")
        if (plan.reasons.isNotEmpty()) {
            println("Почему: ${plan.reasons.joinToString("; ")}")
        }
        printCatalogLine(plan, lookupOnline)

        if (mode == WorkMode.DEBUG || mode == WorkMode.DEBUG_REVERT) {
            println("Полный путь: ${plan.file}")
            if (mode == WorkMode.DEBUG_REVERT) {
                return
            }
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

    private fun printCatalogLine(plan: RenamePlan, lookupOnline: Boolean) {
        val media = plan.media
        val hit = plan.catalog
        if (hit != null) {
            println("Каталог: ${hit.site} — ${hit.title}${hit.year?.let { " ($it)" } ?: ""}")
        } else if (!plan.note.isNullOrBlank()) {
            println("Каталог: ${plan.note}")
        } else if (lookupOnline && media.title.isNotBlank() && media.title != "Название не определено") {
            val year = media.year?.toString() ?: "без года"
            println("Каталог: не нашли «${media.title}» ($year)")
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
            add("Не разобрали — не трогаем: ${report.unclear}")
            if (report.unclearFiles.isNotEmpty()) {
                add("Эти файлы оставили как есть:")
                report.unclearFiles.forEach { add("  ${it.fileName}") }
            }
            add("Файлов изменено в библиотеке: ${report.changed}")
            if (report.mode == WorkMode.DEBUG) {
                add("Записано в debug/results: ${report.writtenResults}")
            }
            if (report.mode == WorkMode.DEBUG_REVERT) {
                add("Записано в debug/reverted: ${report.writtenResults}")
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
    private val notes = mutableMapOf<String, String>()
    private val httpRequests = AtomicInteger(0)
    private val blockedHosts = mutableSetOf<String>()
    private val reportedProblems = mutableSetOf<String>()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun resetStats() {
        httpRequests.set(0)
        cache.clear()
        notes.clear()
        blockedHosts.clear()
        reportedProblems.clear()
    }

    fun requestCount(): Int = httpRequests.get()

    fun silencedServices(): List<String> = blockedHosts.map(::serviceName).distinct().sorted()

    fun cacheKey(media: MediaInfo): String =
        "${media.mediaType}|${media.title.lowercase()}|${media.year}"

    fun noteFor(media: MediaInfo): String? = notes[cacheKey(media)]

    private fun serviceName(host: String): String = when {
        host.contains("themoviedb") -> "TMDB"
        host.contains("poiskkino") -> "ПоискКино"
        host.contains("itunes") -> "iTunes"
        host.contains("tvmaze") -> "TVMaze"
        host.contains("wikipedia") -> "Wikipedia"
        else -> host.ifBlank { "каталог" }
    }

    private fun complain(host: String, message: String) {
        if (reportedProblems.add("$host|$message")) {
            Talk.error(message)
        }
    }

    fun isTmdbConfigured(): Boolean = tmdbToken() != null

    fun isPoiskKinoConfigured(): Boolean = poiskKinoToken() != null

    fun find(media: MediaInfo): CatalogHit? {
        if (media.title.isBlank() || media.title == "Название не определено") {
            return null
        }
        val key = cacheKey(media)
        if (cache.containsKey(key)) return cache[key]
        val tmdb = searchTmdb(media)
        val poiskKino = if (movieHitNeedsMoreData(tmdb) && isPoiskKinoConfigured()) {
            searchPoiskKinoMovie(media, tmdb)
        } else {
            null
        }
        val fromPrimaryCatalogs = chooseMovieHit(media, tmdb, poiskKino, fallbackHits = emptyList())
        val hit = fromPrimaryCatalogs ?: chooseMovieHit(
            media,
            tmdb = null,
            poiskKino = null,
            fallbackHits = searchFallbackCatalogs(media),
        )
        cache[key] = hit
        if (hit != null) notes.remove(key)
        return hit
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
            cyrillicTitle(hit.russianTitle) == null ||
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
            russianTitle = preferredRussianTitle(primary.russianTitle, secondary.russianTitle),
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
        if (!Config.useFallbackCatalogs) return emptyList()
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

    // «FLOW» от TMDB ru-RU — не русское имя. Берём кириллицу, если она есть у любого из сервисов.
    private fun preferredRussianTitle(vararg values: String?): String? {
        val titles = values.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        return titles.firstOrNull(::hasCyrillicLetters) ?: titles.firstOrNull()
    }

    private fun cyrillicTitle(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotEmpty() && hasCyrillicLetters(it) }
    }

    fun pickBest(local: MediaInfo, hits: List<CatalogHit>): CatalogHit? {
        val scored = hits
            .map { it to score(local, it) }
            .filter { it.second >= 45 }
        if (scored.isEmpty()) return null
        val best = scored.maxOf { it.second }
        val winners = scored.filter { it.second == best }
        if (local.year == null) {
            val rivals = winners.distinctBy { (hit, _) ->
                hit.catalogId?.toString() ?: "${hit.site}|${hit.title}|${hit.year}"
            }
            val years = rivals.mapNotNull { (hit, _) -> hit.year }.distinct()
            if (years.size > 1) {
                val list = rivals.take(3).joinToString("; ") { (hit, _) ->
                    hit.title + (hit.year?.let { " ($it)" } ?: "")
                }
                notes[cacheKey(local)] =
                    "без года подходит несколько — $list. Допишите год в имя файла"
                return null
            }
            if (rivals.size > 1) {
                if (years.size == 1) {
                    return rivals.first { (hit, _) -> hit.year == years.single() }.first
                }
                val list = rivals.take(3).joinToString("; ") { (hit, _) ->
                    hit.title + (hit.year?.let { " ($it)" } ?: "")
                }
                notes[cacheKey(local)] =
                    "без года подходит несколько — $list. Допишите год в имя файла"
                return null
            }
        }
        return winners.first().first
    }

    private fun score(local: MediaInfo, hit: CatalogHit): Int {
        val localTitles = titleVariants(local.title)
        val hitTitles = listOfNotNull(hit.title, hit.originalTitle, hit.russianTitle)
            .flatMap(::titleVariants)
            .distinct()
        if (localTitles.isEmpty() || hitTitles.isEmpty()) return -1
        return localTitles.maxOf { localTitle ->
            hitTitles.maxOf { candidate -> scoreTitle(local.year, localTitle, candidate, hit.year) }
        }
    }

    private fun scoreTitle(localYear: Int?, localTitle: String, candidateTitle: String, candidateYear: Int?): Int {
        val localKeys = titleKeys(localTitle)
        val hitKeys = titleKeys(candidateTitle)
        if (localKeys.isEmpty() || hitKeys.isEmpty()) return -1

        val exactTitle = localKeys.intersect(hitKeys).isNotEmpty() ||
            localKeys.any { local -> hitKeys.any { hit -> isLongPrefixTitle(local, hit) } } ||
            isSequelTitleMatch(localTitle, candidateTitle)
        val noiseTail = !exactTitle &&
            stripTailNoise(localTitle)
                ?.let { titleKeys(it).intersect(hitKeys).isNotEmpty() } == true
        val firstInstallment = !exactTitle && !noiseTail &&
            stripFirstInstallment(localTitle)
                ?.let { titleKeys(it).intersect(hitKeys).isNotEmpty() } == true
        val shortHitPrefix = localYear != null && localYear == candidateYear &&
            localKeys.any { local -> hitKeys.any { hit -> isShortTailPrefix(local, hit) } }
        val strippedMatch = localYear != null &&
            candidateYear != null &&
            isNumberStrippedTitleMatch(localTitle, candidateTitle)
        if (localYear == null && !exactTitle && !noiseTail && !firstInstallment) return -1

        var points = when {
            exactTitle -> 60
            noiseTail -> 58
            firstInstallment -> 55
            strippedMatch -> 50
            shortHitPrefix -> 50
            else -> {
                val localWords = localKeys.maxBy { it.length }.split(" ").filter(String::isNotBlank).toSet()
                val hitWords = hitKeys.maxBy { it.length }.split(" ").filter(String::isNotBlank).toSet()
                if (localWords.size <= 1 || hitWords.isEmpty()) return -1
                val matched = localWords.intersect(hitWords).size
                val coverage = matched.toDouble() / localWords.size
                val precision = matched.toDouble() / hitWords.size
                if (coverage < 0.75 || precision < 0.6) return -1
                (coverage * 30 + precision * 20).toInt()
            }
        }

        if (localYear != null && candidateYear != null) {
            val delta = kotlin.math.abs(localYear - candidateYear)
            points += when {
                delta == 0 -> 25
                delta == 1 && exactTitle -> 5
                else -> -40
            }
        }
        return points
    }

    // «Клик с пультом» → «Клик: С пультом по жизни». Не «The Father» → «The Father of the Bride».
    private fun isLongPrefixTitle(local: String, hit: String): Boolean {
        if (local == hit) return true
        if (!hit.startsWith("$local ")) return false
        val rest = hit.removePrefix("$local ").trim()
        if (rest.isEmpty()) return true
        if (rest.first().isDigit()) return false
        val localWords = local.split(" ").filter { it.isNotBlank() }
        return localWords.size >= 3
    }

    // Хвост в файле, которого нет в каталоге. Год обязан совпасть точно,
    // иначе «Rocky II» (1979) немедленно уедет на «Rocky» (1976).
    private fun isShortTailPrefix(local: String, hit: String): Boolean {
        if (local == hit) return true
        if (!hit.any(Char::isLetterOrDigit) || hit.length < 2) return false
        if (!local.startsWith("$hit ")) return false
        val tail = local.removePrefix("$hit ").split(" ").filter { it.isNotBlank() }
        if (tail.isEmpty()) return true
        if (tail.size > 2) return false
        return tail.none { it.first().isDigit() || isNumberingToken(it) }
    }

    private val tailNoiseWords = setOf("movie", "film", "фильм", "кино")

    // «F1 The Movie» → «F1». Только служебный хвост, содержательные слова не трогаем.
    fun stripTailNoise(title: String): String? {
        var words = title.split(Regex("""\s+""")).filter { it.isNotBlank() }
        while (words.size >= 2) {
            val last = foldTitle(words.last())
            if (last !in tailNoiseWords && last !in englishArticles) break
            words = words.dropLast(1)
        }
        val result = words.joinToString(" ").trim().trimEnd(':', '-', ',', '.')
        return result.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
    }

    private val partWords = setOf(
        "part", "vol", "volume", "chapter", "episode",
        "часть", "том", "глава", "эпизод",
    )
    private val firstInstallmentTokens = setOf("1", "i", "one", "первая", "первый")

    // «The Godfather Part I» → «The Godfather», «SAW I» → «SAW».
    // Только для «1»/«I»: у второго и дальше номер в каталоге настоящий.
    fun stripFirstInstallment(title: String): String? {
        val words = title.split(Regex("""\s+""")).filter { it.isNotBlank() }.toMutableList()
        if (words.size < 2) return null
        if (foldTitle(words.last()) !in firstInstallmentTokens) return null
        words.removeAt(words.lastIndex)
        if (words.size >= 2 && foldTitle(words.last()) in partWords) {
            words.removeAt(words.lastIndex)
        }
        val result = words.joinToString(" ").trim().trimEnd(':', '-', ',', '.')
        return result.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
    }

    private val sequelTokens = setOf(
        "продолжение", "prodolzenie", "prodolzhenie", "sequel",
    )
    private val sequelSkipTokens = sequelTokens + setOf("ещё", "еще", "2", "ii")
    private val numberingTokens = sequelTokens + setOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
        "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x",
    )

    private fun isSequelTitleMatch(localTitle: String, candidateTitle: String): Boolean {
        if (!hasSequelToken(localTitle)) return false
        if (!looksLikeSequelTitle(candidateTitle)) return false
        return sequelCoreKeys(localTitle).intersect(sequelCoreKeys(candidateTitle)).isNotEmpty()
    }

    private fun hasSequelToken(title: String): Boolean {
        val words = foldTitle(title).split(" ").filter(String::isNotBlank) +
            foldTitle(latinToCyrillic(title)).split(" ").filter(String::isNotBlank)
        return words.any { it in sequelTokens }
    }

    private fun looksLikeSequelTitle(title: String): Boolean {
        if (hasSequelToken(title)) return true
        val words = foldTitle(title).split(" ").filter(String::isNotBlank)
        if (words.any { it == "ещё" || it == "еще" }) return true
        val last = words.lastOrNull() ?: return false
        return words.size >= 2 && (last == "2" || last == "ii")
    }

    private fun sequelCoreKeys(title: String): Set<String> {
        fun strip(value: String): String {
            return foldSoft(value)
                .split(" ")
                .filter { it.isNotBlank() && it !in sequelSkipTokens }
                .joinToString(" ")
        }
        return setOf(strip(title), strip(latinToCyrillic(title)))
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun isNumberingToken(word: String): Boolean {
        val folded = foldTitle(word)
        val cyr = foldTitle(latinToCyrillic(word))
        return folded in numberingTokens || cyr in numberingTokens
    }

    fun stripNumberingTokens(title: String): String {
        return title.split(Regex("""\s+"""))
            .filter { it.isNotBlank() && !isNumberingToken(it) }
            .joinToString(" ")
    }

    private fun isNumberStrippedTitleMatch(localTitle: String, candidateTitle: String): Boolean {
        val lastWord = foldTitle(localTitle).split(" ").filter { it.isNotBlank() }.lastOrNull()
        if (lastWord != null && lastWord in numberingTokens && lastWord !in firstInstallmentTokens) {
            return false
        }
        val stripped = stripNumberingTokens(localTitle)
        if (stripped.isBlank() || foldTitle(stripped) == foldTitle(localTitle)) return false
        val localKeys = titleKeys(stripped)
        val hitKeys = titleKeys(candidateTitle)
        if (localKeys.isEmpty() || hitKeys.isEmpty()) return false
        return localKeys.intersect(hitKeys).isNotEmpty() ||
            localKeys.any { local -> hitKeys.any { hit -> isLongPrefixTitle(local, hit) } }
    }

    private val apostropheRegex = Regex("""['’‘ʼ`´ʻ]""")

    private fun foldTitle(value: String): String {
        return value.lowercase()
            .replace('ё', 'е')
            .replace('э', 'е')
            .replace(apostropheRegex, "")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun foldSoft(value: String): String {
        return foldTitle(value).replace("ь", "").replace("ъ", "")
    }

    fun titleKeys(value: String): Set<String> {
        val translit = latinToCyrillic(value)
        val bases = setOf(foldTitle(value), foldTitle(translit), foldSoft(value), foldSoft(translit))
            .filter { it.isNotBlank() }
        return (bases + bases.mapNotNull(::stripLeadingArticle)).toSet()
    }

    private val englishArticles = setOf("the", "a", "an")

    private fun stripLeadingArticle(folded: String): String? {
        val words = folded.split(" ").filter { it.isNotBlank() }
        if (words.size < 2) return null
        if (words.first() !in englishArticles) return null
        return words.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
    }

    // Русское и английское имя в одном файле: ищем и сравниваем каждую часть отдельно.
    private val bracketContentRegex = Regex("""[(\[]([^)\]]{2,60})[)\]]""")

    private fun bracketedTitles(title: String): List<String> {
        return bracketContentRegex.findAll(title)
            .map { it.groupValues[1].trim() }
            .filter { part ->
                part.count(Char::isLetter) >= 3 &&
                    part.none(Char::isDigit) &&
                    part.split(Regex("""\s+""")).size <= 6
            }
            .toList()
    }

    fun titleVariants(title: String): List<String> {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return emptyList()
        val withoutBrackets = trimmed
            .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return (listOf(withoutBrackets, trimmed) + bracketedTitles(trimmed) + scriptParts(trimmed))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { foldTitle(it).ifBlank { it.lowercase() } }
    }

    private fun scriptParts(title: String): List<String> {
        val words = title
            .replace(Regex("""[\[\](){}/|]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val runs = mutableListOf<Pair<CharScript, MutableList<String>>>()
        for (word in words) {
            val script = wordScript(word)
            if (script == CharScript.OTHER) {
                if (runs.isNotEmpty()) {
                    runs.last().second.add(word)
                }
                continue
            }
            val last = runs.lastOrNull()
            if (last != null && last.first == script) {
                last.second.add(word)
            } else {
                runs += script to mutableListOf(word)
            }
        }
        return runs
            .map { it.second.joinToString(" ") }
            .filter { part -> part.any(Char::isLetter) }
    }

    private enum class CharScript { CYRILLIC, LATIN, OTHER }

    private fun wordScript(word: String): CharScript {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return CharScript.OTHER
        val cyrillic = letters.count { it in '\u0400'..'\u04FF' }
        val latin = letters.count { it in 'A'..'Z' || it in 'a'..'z' }
        return when {
            cyrillic > 0 && cyrillic >= latin -> CharScript.CYRILLIC
            latin > 0 -> CharScript.LATIN
            else -> CharScript.OTHER
        }
    }

    fun searchQueries(title: String): List<String> {
        val variants = titleVariants(title)
        val latin = variants.filter { it.isNotEmpty() }
        return (latin + transliteratedQueries(title)).distinctBy { it.lowercase() }
    }

    fun searchQueryLadder(title: String): List<String> =
        (primaryQueries(title) + shortenedQueries(title)).distinctBy { it.lowercase() }

    fun primaryQueries(title: String): List<String> {
        val withoutNumbering = stripNumberingTokens(title)
        val numbering = if (withoutNumbering.isNotBlank() && withoutNumbering != title) {
            searchQueries(withoutNumbering)
        } else {
            emptyList()
        }
        val firstPart = stripFirstInstallment(title)?.let(::searchQueries).orEmpty()
        val withoutTail = stripTailNoise(title)?.let(::searchQueries).orEmpty()
        val withThe = if (shouldTryLeadingThe(title)) searchQueries("The $title") else emptyList()
        return (searchQueries(title) + firstPart + withoutTail + numbering + withThe)
            .distinctBy { it.lowercase() }
    }

    private fun shouldTryLeadingThe(title: String): Boolean {
        if (title.any { it in '\u0400'..'\u04FF' }) return false
        if (stripFirstInstallment(title) != null) return false
        val words = foldTitle(title).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return false
        return words.none { it in englishArticles }
    }

    fun shortenedQueries(title: String): List<String> {
        return (titleVariants(title) + transliteratedQueries(title))
            .flatMap { variant ->
                val words = variant.split(" ").filter { it.isNotBlank() }
                if (words.size < 3) return@flatMap emptyList()
                listOf(
                    words.drop(1),
                    words.dropLast(1),
                    words.takeLast(2),
                    words.take(2),
                ).map { it.joinToString(" ") }
            }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    fun transliteratedQueries(title: String): List<String> {
        if (looksMostlyEnglish(title)) return emptyList()
        return titleVariants(title)
            .map { latinToCyrillic(it).trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    private val englishCueWords = setOf(
        "the", "of", "and", "a", "an", "in", "on", "to", "for", "movie", "part",
    )

    fun looksMostlyEnglish(title: String): Boolean {
        if (title.any { it in '\u0400'..'\u04FF' }) return false
        val words = title.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        return words.any { it in englishCueWords }
    }

    // Латиница из релизов: brat → брат. Не перевод: dune не станет «Дюна».
    fun latinToCyrillic(value: String): String {
        var text = value.lowercase()
        val endings = listOf(
            Regex("""skiy\b""") to "ский",
            Regex("""skij\b""") to "ский",
            Regex("""sky\b""") to "ский",
            Regex("""iy\b""") to "ий",
            Regex("""yy\b""") to "ый",
            Regex("""zenie\b""") to "жение",
        )
        for ((from, to) in endings) {
            text = text.replace(from, to)
        }
        // Согласная + y + гласная — йотация, кроме окончания -ые: mertvye → мёртвые.
        text = text.replace(Regex("""(?<=[bcdfghklmnpqrstvwxz])y(?=[aeou])(?!e\b)"""), "j")
        // Гласная + y перед согласной — «й»: neznayka → незнайка.
        text = text.replace(Regex("""(?<=[aeiou])y(?![aeiouy])"""), "й")
        val digraphs = listOf(
            "shch" to "щ",
            "yo" to "ё",
            "jo" to "ё",
            "je" to "е",
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
        val vowels = setOf(
            'a', 'e', 'i', 'o', 'u', 'y',
            'а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я',
        )
        fun isVowel(char: Char) = char in vowels
        return buildString {
            for (index in text.indices) {
                val char = text[index]
                val prev = text.getOrNull(index - 1)
                val next = text.getOrNull(index + 1)
                when {
                    char == 'w' && prev != null && next != null && isVowel(prev) && isVowel(next) -> append('щ')
                    else -> append(letters[char] ?: char)
                }
            }
        }
    }

    private fun searchTmdb(media: MediaInfo): CatalogHit? {
        val token = tmdbToken() ?: return null
        val movie = if (media.mediaType == MediaType.MOVIE) {
            tmdbPick(media, token, "ru-RU", tv = false)
                ?: tmdbPick(media, token, "en-US", tv = false)
        } else {
            null
        }
        val candidate = movie
            ?: tmdbPick(media, token, "ru-RU", tv = true)
            ?: tmdbPick(media, token, "en-US", tv = true)
            ?: return null
        val id = candidate.catalogId ?: return candidate
        val tv = candidate.pageUrl?.contains("/tv/") == true
        val detailsUrl = if (tv) {
            "https://api.themoviedb.org/3/tv/$id?language=ru-RU&append_to_response=credits,translations"
        } else {
            "https://api.themoviedb.org/3/movie/$id?language=ru-RU&append_to_response=credits,translations"
        }
        val details = get(detailsUrl, token) ?: return candidate
        return if (tv) {
            parseTmdbTvDetails(details, candidate) ?: candidate
        } else {
            parseTmdbMovieDetails(details, candidate) ?: candidate
        }
    }

    private fun tmdbPick(media: MediaInfo, token: String, language: String, tv: Boolean): CatalogHit? {
        val collected = mutableListOf<CatalogHit>()
        var answered = 0
        for (query in primaryQueries(media.title)) {
            val found = tmdbSearchOnce(query, media, token, language, tv)
            collected += found
            pickBest(media, collected)?.let { return it }
            if (found.isNotEmpty()) answered++
        }
        if (answered > 0) return null
        for (query in shortenedQueries(media.title)) {
            val found = tmdbSearchOnce(query, media, token, language, tv)
            collected += found
            pickBest(media, collected)?.let { return it }
            if (found.isNotEmpty()) return null
        }
        return null
    }

    private fun tmdbSearchOnce(
        query: String,
        media: MediaInfo,
        token: String,
        language: String,
        tv: Boolean,
    ): List<CatalogHit> {
        val year = media.year?.let { if (tv) "&first_air_date_year=$it" else "&year=$it" }.orEmpty()
        val path = if (tv) "search/tv" else "search/movie"
        val url = "https://api.themoviedb.org/3/$path" +
            "?query=${enc(query)}&language=$language&include_adult=false$year"
        val body = get(url, token) ?: return emptyList()
        val hits = runCatching {
            json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
                .take(10)
                .mapNotNull { element ->
                    val item = element.jsonObject
                    val id = item.int("id") ?: return@mapNotNull null
                    val localizedTitle = (if (tv) item.str("name") else item.str("title"))
                        ?: return@mapNotNull null
                    val original = if (tv) item.str("original_name") else item.str("original_title")
                    val date = if (tv) item.str("first_air_date") else item.str("release_date")
                    CatalogHit(
                        site = "TMDB",
                        title = localizedTitle,
                        year = yearOf(date),
                        pageUrl = if (tv) {
                            "https://www.themoviedb.org/tv/$id"
                        } else {
                            "https://www.themoviedb.org/movie/$id"
                        },
                        originalTitle = original,
                        russianTitle = localizedTitle.takeIf { language.startsWith("ru") && hasCyrillicLetters(it) },
                        originalLanguage = item.str("original_language"),
                        rating = item.double("vote_average")?.takeIf { it > 0.0 },
                        ratingSource = item.double("vote_average")?.takeIf { it > 0.0 }?.let { "TMDB" },
                        catalogId = id,
                    )
                }
        }.getOrDefault(emptyList())
        if (Config.logCatalogQueries) {
            Talk.info(
                "← «$query»: ${hits.size} шт. " +
                    hits.take(3).joinToString { "${it.title} (${it.year})" },
            )
        }
        return hits
    }

    fun parseTmdbMovieDetails(body: String, fallback: CatalogHit? = null): CatalogHit? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val id = root.int("id") ?: fallback?.catalogId ?: return@runCatching null
            val apiTitle = root.str("title")
            val russianTitle = preferredRussianTitle(
                apiTitle,
                tmdbTranslatedTitle(root, "ru"),
                fallback?.russianTitle,
            ) ?: apiTitle ?: fallback?.russianTitle ?: fallback?.title
                ?: return@runCatching null
            val apiOriginalTitle = root.str("original_title") ?: fallback?.originalTitle
            val englishTitle = tmdbTranslatedTitle(root)
                ?: fallback?.originalTitle?.takeIf(::hasLatinLetters)
                ?: fallback?.title?.takeIf(::hasLatinLetters)
            val originalTitle = preferredOriginalTitle(apiOriginalTitle, englishTitle)
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
                originalTitle = originalTitle,
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

    fun parseTmdbTvDetails(body: String, fallback: CatalogHit? = null): CatalogHit? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val id = root.int("id") ?: fallback?.catalogId ?: return@runCatching null
            val apiTitle = root.str("name")
            val russianTitle = preferredRussianTitle(
                apiTitle,
                tmdbTranslatedTitle(root, "ru"),
                fallback?.russianTitle,
            ) ?: apiTitle ?: fallback?.russianTitle ?: fallback?.title
                ?: return@runCatching null
            val apiOriginalTitle = root.str("original_name") ?: fallback?.originalTitle
            val englishTitle = tmdbTranslatedTitle(root)
                ?: fallback?.originalTitle?.takeIf(::hasLatinLetters)
                ?: fallback?.title?.takeIf(::hasLatinLetters)
            val originalTitle = preferredOriginalTitle(apiOriginalTitle, englishTitle)
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
            val directors = root["created_by"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject.str("name") }
                .distinct()
                .take(2)
                .ifEmpty {
                    root["credits"]?.jsonObject?.get("crew")?.jsonArray.orEmpty()
                        .map { it.jsonObject }
                        .filter { it.str("job").equals("Director", ignoreCase = true) }
                        .mapNotNull { it.str("name") }
                        .distinct()
                        .take(2)
                }
            val rating = root.double("vote_average")?.takeIf { it > 0.0 } ?: fallback?.rating
            CatalogHit(
                site = "TMDB",
                title = russianTitle,
                year = yearOf(root.str("first_air_date")) ?: fallback?.year,
                pageUrl = "https://www.themoviedb.org/tv/$id",
                originalTitle = originalTitle,
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

    private fun tmdbTranslatedTitle(root: JsonObject, language: String = "en"): String? {
        val translations = runCatching {
            root["translations"]?.jsonObject?.get("translations")?.jsonArray
        }.getOrNull().orEmpty()
        return translations.firstNotNullOfOrNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (!item.str("iso_639_1").equals(language, ignoreCase = true)) return@firstNotNullOfOrNull null
            item["data"]?.jsonObject?.str("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: item["data"]?.jsonObject?.str("name")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun preferredOriginalTitle(original: String?, english: String?): String? {
        val orig = original?.trim()?.takeIf { it.isNotEmpty() }
        val eng = english?.trim()?.takeIf { it.isNotEmpty() }
        if (orig != null && hasLatinOrCyrillicLetters(orig)) return orig
        return eng ?: orig
    }

    private fun hasLatinLetters(value: String): Boolean = letterShare(value) { char ->
        char in 'A'..'Z' || char in 'a'..'z'
    }

    private fun hasCyrillicLetters(value: String): Boolean = letterShare(value) { char ->
        char in '\u0400'..'\u04FF'
    }

    private fun hasLatinOrCyrillicLetters(value: String): Boolean = letterShare(value) { char ->
        char in 'A'..'Z' || char in 'a'..'z' || char in '\u0400'..'\u04FF'
    }

    private fun letterShare(value: String, allowed: (Char) -> Boolean): Boolean {
        val letters = value.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count(allowed) * 2 >= letters.length
    }

    fun findById(id: Int, tv: Boolean = false): CatalogHit? {
        val token = tmdbToken() ?: return null
        val kind = if (tv) "tv" else "movie"
        val body = get(
            "https://api.themoviedb.org/3/$kind/$id?language=ru-RU&append_to_response=credits,translations",
            token,
        ) ?: return null
        return if (tv) parseTmdbTvDetails(body) else parseTmdbMovieDetails(body)
    }

    private fun poiskKinoByTmdbId(tmdbId: Int, token: String): CatalogHit? {
        val url = "https://api.poiskkino.dev/v1.4/movie?externalId.tmdb=$tmdbId&limit=1"
        val body = get(url, apiKey = token) ?: return null
        val short = parsePoiskKinoSearch(body).firstOrNull()
            ?: runCatching {
                parsePoiskKinoMovie(json.parseToJsonElement(body).jsonObject)
            }.getOrNull()
            ?: return null
        if (short.actors.isNotEmpty() && short.genres.isNotEmpty()) return short
        val id = short.catalogId ?: return short
        val details = get("https://api.poiskkino.dev/v1.4/movie/$id", apiKey = token) ?: return short
        return parsePoiskKinoMovieDetails(details, short) ?: short
    }

    private fun searchPoiskKinoMovie(media: MediaInfo, known: CatalogHit? = null): CatalogHit? {
        val token = poiskKinoToken() ?: return null
        if (known?.site == "TMDB") {
            known.catalogId?.let { id -> poiskKinoByTmdbId(id, token)?.let { return it } }
        }
        val probe = if (known != null) {
            media.copy(
                title = firstNonBlank(known.originalTitle, known.title) ?: media.title,
                year = media.year ?: known.year,
            )
        } else {
            media
        }
        val queries = if (known != null) {
            (listOfNotNull(known.originalTitle, known.title, known.russianTitle)
                .map(String::trim)
                .filter(String::isNotEmpty) + primaryQueries(media.title))
                .distinctBy { it.lowercase() }
        } else {
            primaryQueries(media.title)
        }
        val collected = mutableListOf<CatalogHit>()
        var answered = 0
        for (query in queries) {
            val body = get(
                "https://api.poiskkino.dev/v1.4/movie/search?query=${enc(query)}&limit=10",
                apiKey = token,
            ) ?: continue
            val found = parsePoiskKinoSearch(body)
            collected += found
            val best = pickBest(probe, collected.distinctBy { it.catalogId })
            if (best != null) {
                val id = best.catalogId ?: return best
                val details = get("https://api.poiskkino.dev/v1.4/movie/$id", apiKey = token) ?: return best
                return parsePoiskKinoMovieDetails(details, best) ?: best
            }
            if (found.isNotEmpty()) answered++
            if (answered >= 2) return null
        }
        return null
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
        val type = root.str("type")?.lowercase()
        if (type != null && type !in setOf(
                "movie", "cartoon", "anime", "tv-series", "animated-series", "mini-series", "anime-serial",
            )
        ) {
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
                    """(?iu)\s*\([^)]*\b(?:film|movie|сериал|телесериал|мини-?сериал|фильм|tv series|television series|miniseries)\b[^)]*\)\s*$""",
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
        val host = runCatching { URI.create(url).host }.getOrNull().orEmpty()
        val service = serviceName(host)
        if (Config.logCatalogQueries) {
            val query = runCatching { URI.create(url).query }.getOrNull().orEmpty()
            Talk.info("→ $service ${query.ifBlank { url.substringAfterLast('/') }}")
        }
        if (host in blockedHosts) return null
        return runCatching {
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
            Thread.sleep(120)
            httpRequests.incrementAndGet()
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() == 429) {
                Thread.sleep(2000)
                httpRequests.incrementAndGet()
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            }
            val code = response.statusCode()
            when {
                code in 200..299 -> response.body()
                code == 401 || code == 403 -> {
                    blockedHosts += host
                    complain(
                        host,
                        "$service: ключ не принят или исчерпан лимит (HTTP $code). " +
                            "Дальше этот каталог не спрашиваем, иначе «не нашли» будет неправдой",
                    )
                    null
                }
                code == 429 -> {
                    blockedHosts += host
                    complain(host, "$service: слишком много запросов (HTTP 429). Дальше этот каталог не спрашиваем")
                    null
                }
                else -> {
                    complain(host, "$service ответил HTTP $code")
                    null
                }
            }
        }.onFailure { error ->
            complain(host, "$service: запрос не удался — ${error.message ?: error.javaClass.simpleName}")
        }.getOrNull()
    }
}
