package movierenamer.parser

import movierenamer.model.MediaInfo
import movierenamer.model.MediaType
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

object MediaParser {
    private val yearRegex = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val seasonEpisodeRegex = Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val seasonRegex = Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE)
    private val resolutionRegex = Regex("""\b(480p|720p|1080p|2160p|4K|UHD)\b""", RegexOption.IGNORE_CASE)
    private val leadingResolutionRegex = Regex(
        """^\s*\[(480p|720p|1080p|2160p|4K|UHD)\]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val sourceRegex = Regex(
        """\b(WEB[ .-]?DL|WEB[ .-]?RIP|BLU[ .-]?RAY|BDRIP|BRRIP|HDRIP|DVDRIP|HDTV)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val editionRegex = Regex(
        """\b(OPEN[ .-]?MATTE|UNRATED|EXTENDED(?:[ .-]?EDITION)?|DIRECTOR'?S[ .-]?CUT|THEATRICAL|REMASTERED)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val languageRegex = Regex(
        """\b(?:\d+x)?(RUS|RUSSIAN|ENG|ENGLISH|UKR|UKRAINIAN|GER|FRE|JPN)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(path: Path): MediaInfo {
        val originalName = path.nameWithoutExtension
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
        return value
            .replace(leadingResolutionRegex, "")
            .replace(".", " ")
            .replace("_", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeResolution(value: String): String {
        return when (value.uppercase()) {
            "4K" -> "4K"
            "UHD" -> "UHD"
            else -> value.lowercase()
        }
    }

    private fun normalizeSource(value: String): String {
        return when (value.uppercase().replace(Regex("""[ .\-]"""), "")) {
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
        val compact = value.uppercase().replace(".", " ").replace("-", " ").replace(Regex("""\s+"""), " ").trim()
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
            "RUS", "RUSSIAN" -> "RU"
            "ENG", "ENGLISH" -> "EN"
            "UKR", "UKRAINIAN" -> "UK"
            "GER" -> "DE"
            "FRE" -> "FR"
            "JPN" -> "JA"
            else -> null
        }
    }
}

private fun String.trimReleaseSeparators(): String = trim(' ', '-', '.', '_')
