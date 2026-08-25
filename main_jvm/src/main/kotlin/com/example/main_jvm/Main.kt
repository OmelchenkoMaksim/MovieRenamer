package com.example.main_jvm

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

private val moviesDirectory: Path = Path.of(
    """H:\TestMovies"""
)

private val supportedExtensions: Set<String> = setOf(
    "mkv",
    "mp4",
    "avi",
    "m4v",
    "mov",
    "webm",
    "ts",
    "m2ts",
)

private val yearRegex = Regex(
    pattern = """\b(19\d{2}|20\d{2})\b""",
)

private val seasonEpisodeRegex = Regex(
    pattern = """\bS(\d{1,2})E(\d{1,3})\b""",
    option = RegexOption.IGNORE_CASE,
)

private val seasonRegex = Regex(
    pattern = """\bS\d{1,2}\b""",
    option = RegexOption.IGNORE_CASE,
)

private val resolutionRegex = Regex(
    pattern = """\b(480p|720p|1080p|2160p|4K|UHD)\b""",
    option = RegexOption.IGNORE_CASE,
)

private val sourceRegex = Regex(
    pattern = """
        \b(
            WEB[ .-]?DL |
            WEB[ .-]?RIP |
            BLU[ .-]?RAY |
            BDRIP |
            BRRIP |
            HDRIP |
            DVDRIP |
            HDTV
        )\b
    """.trimIndent().replace("\n", "").replace(" ", ""),
    option = RegexOption.IGNORE_CASE,
)

private val editionRegex = Regex(
    pattern = """
        \b(
            OPEN[ .-]?MATTE |
            UNRATED |
            EXTENDED(?:[ .-]?EDITION)? |
            DIRECTOR'?S[ .-]?CUT |
            THEATRICAL |
            REMASTERED
        )\b
    """.trimIndent().replace("\n", "").replace(" ", ""),
    option = RegexOption.IGNORE_CASE,
)

private val languageRegex = Regex(
    pattern = """
        \b(?:\d+x)?(
            RUS |
            RUSSIAN |
            ENG |
            ENGLISH |
            UKR |
            UKRAINIAN |
            GER |
            FRE |
            JPN
        )\b
    """.trimIndent().replace("\n", "").replace(" ", ""),
    option = RegexOption.IGNORE_CASE,
)

private val logTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss")

fun main() {
    configureUtf8Console()

    println("Проверка кодировки: Привет, мир!")
    println("Default charset: ${java.nio.charset.Charset.defaultCharset()}")

    logInfo("mylog 001 Запуск Movie File Renamer")

    logInfo("mylog 002 Рабочая директория: $moviesDirectory")

    if (!prepareMoviesDirectory(moviesDirectory)) {
        logError("mylog 003 Продолжение невозможно")
        return
    }

    val videoFiles = try {
        findVideoFiles(moviesDirectory)
    } catch (exception: Exception) {
        logError(
            message = "mylog 004 Ошибка сканирования: " +
                    "${exception.message}",
        )
        return
    }

    if (videoFiles.isEmpty()) {
        logInfo(
            "mylog 005 В директории нет поддерживаемых видеофайлов",
        )
        return
    }

    logInfo("mylog 006 Найдено файлов: ${videoFiles.size}")
    println()

    videoFiles.forEachIndexed { index, file ->
        val mediaInfo = parseMediaFile(file)

        printMediaInfo(
            index = index,
            file = file,
            mediaInfo = mediaInfo,
        )
    }

    println()
    logInfo("mylog 007 Анализ завершён")
    logInfo("mylog 008 Файлы не изменялись")
}

private fun prepareMoviesDirectory(
    directory: Path,
): Boolean {
    return try {
        if (Files.exists(directory)) {
            if (!directory.isDirectory()) {
                logError(
                    "Путь существует, но это не директория: $directory",
                )
                return false
            }

            logInfo("Директория существует: $directory")
            true
        } else {
            logInfo(
                "Директория отсутствует. Создаём: $directory",
            )

            Files.createDirectories(directory)

            logInfo(
                "Пустая директория успешно создана: $directory",
            )
            true
        }
    } catch (exception: Exception) {
        logError(
            message = "Не удалось создать директорию $directory. " +
                    "Причина: ${exception.message}",
        )
        false
    }
}

private fun findVideoFiles(
    directory: Path,
): List<Path> {
    return Files.walk(directory).use { paths ->
        paths
            .asSequence()
            .filter { path ->
                path.isRegularFile()
            }
            .filter { path ->
                path.extensionLowercase() in supportedExtensions
            }
            .sortedBy { path ->
                path.toString().lowercase()
            }
            .toList()
    }
}

private fun parseMediaFile(
    path: Path,
): ParsedMediaInfo {
    val originalName = path.fileNameWithoutExtension()
    val normalizedName = normalizeReleaseName(originalName)

    val seasonEpisodeMatch =
        seasonEpisodeRegex.find(normalizedName)

    val isTvEpisode = seasonEpisodeMatch != null

    val parentName = path.parent
        ?.fileName
        ?.toString()
        .orEmpty()

    val normalizedParentName =
        normalizeReleaseName(parentName)

    val metadataText = if (isTvEpisode) {
        "$normalizedName $normalizedParentName"
    } else {
        normalizedName
    }

    val probableTitle = if (isTvEpisode) {
        extractSeriesTitle(
            normalizedFileName = normalizedName,
            normalizedParentName = normalizedParentName,
        )
    } else {
        extractMovieTitle(normalizedName)
    }

    val year = yearRegex
        .find(metadataText)
        ?.value
        ?.toIntOrNull()

    val season = seasonEpisodeMatch
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    val episode = seasonEpisodeMatch
        ?.groupValues
        ?.getOrNull(2)
        ?.toIntOrNull()

    val episodeTitleHint = seasonEpisodeMatch
        ?.let { match ->
            extractEpisodeTitle(
                normalizedName = normalizedName,
                seasonEpisodeMatch = match,
            )
        }

    val resolution = resolutionRegex
        .find(metadataText)
        ?.value
        ?.let(::normalizeResolution)

    val source = sourceRegex
        .find(metadataText)
        ?.value
        ?.let(::normalizeSource)

    val editions = editionRegex
        .findAll(metadataText)
        .map { match ->
            normalizeEdition(match.value)
        }
        .distinct()
        .toList()

    val languages = languageRegex
        .findAll(metadataText)
        .mapNotNull { match ->
            match.groupValues
                .getOrNull(1)
                ?.let(::normalizeLanguage)
        }
        .distinct()
        .toList()

    return ParsedMediaInfo(
        mediaType = if (isTvEpisode) {
            MediaType.TV_EPISODE
        } else {
            MediaType.MOVIE
        },
        probableTitle = probableTitle,
        year = year,
        season = season,
        episode = episode,
        episodeTitleHint = episodeTitleHint,
        resolution = resolution,
        source = source,
        editions = editions,
        languages = languages,
    )
}

private fun extractMovieTitle(
    normalizedName: String,
): String {
    val metadataIndex =
        findFirstMetadataIndex(normalizedName)

    return normalizedName
        .substring(startIndex = 0, endIndex = metadataIndex)
        .trimReleaseSeparators()
        .ifBlank {
            normalizedName
        }
}

private fun extractSeriesTitle(
    normalizedFileName: String,
    normalizedParentName: String,
): String {
    val titleFromParent = normalizedParentName
        .substring(
            startIndex = 0,
            endIndex = findFirstMetadataIndex(
                normalizedParentName,
            ),
        )
        .trimReleaseSeparators()

    if (titleFromParent.isNotBlank()) {
        return titleFromParent
    }

    return normalizedFileName
        .substring(
            startIndex = 0,
            endIndex = findFirstMetadataIndex(
                normalizedFileName,
            ),
        )
        .trimReleaseSeparators()
        .ifBlank {
            "Название не определено"
        }
}

private fun extractEpisodeTitle(
    normalizedName: String,
    seasonEpisodeMatch: MatchResult,
): String? {
    val textAfterEpisodeCode = normalizedName
        .substring(
            startIndex = seasonEpisodeMatch.range.last + 1,
        )
        .trimReleaseSeparators()

    if (textAfterEpisodeCode.isBlank()) {
        return null
    }

    val metadataIndex =
        findFirstMetadataIndex(textAfterEpisodeCode)

    return textAfterEpisodeCode
        .substring(
            startIndex = 0,
            endIndex = metadataIndex,
        )
        .trimReleaseSeparators()
        .ifBlank {
            null
        }
}

private fun findFirstMetadataIndex(
    value: String,
): Int {
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

private fun normalizeReleaseName(
    value: String,
): String {
    return value
        .replace(
            regex = Regex(
                pattern =
                    """^\s*\[(480p|720p|1080p|2160p|4K|UHD)\]\s*""",
                option = RegexOption.IGNORE_CASE,
            ),
            replacement = "",
        )
        .replace(".", " ")
        .replace("_", " ")
        .replace(
            regex = Regex("""\s+"""),
            replacement = " ",
        )
        .trim()
}

private fun normalizeResolution(
    value: String,
): String {
    return when (value.uppercase()) {
        "4K" -> "4K"
        "UHD" -> "UHD"
        else -> value.lowercase()
    }
}

private fun normalizeSource(
    value: String,
): String {
    val compactValue = value
        .uppercase()
        .replace(" ", "")
        .replace(".", "")
        .replace("-", "")

    return when (compactValue) {
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

private fun normalizeEdition(
    value: String,
): String {
    val compactValue = value
        .uppercase()
        .replace(".", " ")
        .replace("-", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    return when {
        compactValue == "OPEN MATTE" -> "Open Matte"
        compactValue == "UNRATED" -> "Unrated"
        compactValue.startsWith("EXTENDED") -> "Extended"
        compactValue.contains("DIRECTOR") -> "Director's Cut"
        compactValue == "THEATRICAL" -> "Theatrical"
        compactValue == "REMASTERED" -> "Remastered"
        else -> value
    }
}

private fun normalizeLanguage(
    value: String,
): String? {
    return when (value.uppercase()) {
        "RUS", "RUSSIAN" -> "RU"
        "ENG", "ENGLISH" -> "EN"
        "UKR", "UKRAINIAN" -> "UK"
        "GER" -> "DE"
        "FRE" -> "FR"
        "JPN" -> "JA"
        else -> null
    }
}

private fun printMediaInfo(
    index: Int,
    file: Path,
    mediaInfo: ParsedMediaInfo,
) {
    println("==================================================")
    println("${index + 1}. ${file.fileName}")
    println("Полный путь: $file")
    println("Тип: ${mediaInfo.mediaType.displayName}")
    println("Предполагаемое название: ${mediaInfo.probableTitle}")
    println("Год: ${mediaInfo.year ?: "не найден"}")

    if (mediaInfo.mediaType == MediaType.TV_EPISODE) {
        println("Сезон: ${mediaInfo.season ?: "не найден"}")
        println("Эпизод: ${mediaInfo.episode ?: "не найден"}")
        println(
            "Название эпизода: " +
                    (mediaInfo.episodeTitleHint ?: "не найдено"),
        )
    }

    println(
        "Разрешение: ${mediaInfo.resolution ?: "не найдено"}",
    )
    println(
        "Источник: ${mediaInfo.source ?: "не найден"}",
    )
    println(
        "Версия: ${
            mediaInfo.editions
                .ifEmpty { listOf("не найдена") }
                .joinToString()
        }",
    )
    println(
        "Языки: ${
            mediaInfo.languages
                .ifEmpty { listOf("не найдены") }
                .joinToString()
        }",
    )
}

private fun Path.extensionLowercase(): String {
    return fileName
        .toString()
        .substringAfterLast(
            delimiter = ".",
            missingDelimiterValue = "",
        )
        .lowercase()
}

private fun Path.fileNameWithoutExtension(): String {
    val completeFileName = fileName.toString()

    return completeFileName.substringBeforeLast(
        delimiter = ".",
        missingDelimiterValue = completeFileName,
    )
}

private fun String.trimReleaseSeparators(): String {
    return trim(' ', '-', '.', '_')
}

private fun logInfo(
    message: String,
) {
    println(
        "${currentLogTime()} [INFO] $message",
    )
}

private fun logError(
    message: String,
) {
    System.err.println(
        "${currentLogTime()} [ERROR] $message",
    )
}

private fun currentLogTime(): String {
    return LocalTime.now().format(logTimeFormatter)
}

private data class ParsedMediaInfo(
    val mediaType: MediaType,
    val probableTitle: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val episodeTitleHint: String?,
    val resolution: String?,
    val source: String?,
    val editions: List<String>,
    val languages: List<String>,
)

private enum class MediaType(
    val displayName: String,
) {
    MOVIE(
        displayName = "Фильм",
    ),
    TV_EPISODE(
        displayName = "Эпизод сериала",
    ),
}
private fun configureUtf8Console() {
    System.setOut(
        PrintStream(
            FileOutputStream(FileDescriptor.out),
            true,
            StandardCharsets.UTF_8,
        ),
    )

    System.setErr(
        PrintStream(
            FileOutputStream(FileDescriptor.err),
            true,
            StandardCharsets.UTF_8,
        ),
    )
}