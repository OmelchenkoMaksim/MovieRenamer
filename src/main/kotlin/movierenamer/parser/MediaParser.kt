package movierenamer.parser

import movierenamer.model.MediaInfo
import movierenamer.model.MediaType
import java.nio.file.Path
import java.text.Normalizer
import kotlin.io.path.nameWithoutExtension

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
