package movierenamer

import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovieRenamerTest {

    @Test
    fun `parses a movie release name`() {
        val media = MediaParser.parse(
            Path.of("The.Matrix.1999.1080p.BluRay.x264.RUS.ENG.mkv"),
        )

        assertEquals(MediaType.MOVIE, media.mediaType)
        assertEquals("The Matrix", media.title)
        assertEquals(1999, media.year)
        assertEquals("1080p", media.resolution)
        assertEquals("BluRay", media.source)
        assertEquals(listOf("RU", "EN"), media.languages)
        assertNull(media.season)
        assertNull(media.episode)
    }

    @Test
    fun `parses a tv episode and takes the series title from the parent folder`() {
        val media = MediaParser.parse(
            Path.of("Breaking Bad", "Breaking.Bad.S02E03.Bit.by.a.Dead.Bee.720p.WEB-DL.mkv"),
        )

        assertEquals(MediaType.TV_EPISODE, media.mediaType)
        assertEquals("Breaking Bad", media.title)
        assertEquals(2, media.season)
        assertEquals(3, media.episode)
        assertEquals("Bit by a Dead Bee", media.episodeTitle)
        assertEquals("720p", media.resolution)
        assertEquals("WEB-DL", media.source)
    }

    @Test
    fun `strips a leading resolution tag and detects edition`() {
        val media = MediaParser.parse(
            Path.of("[1080p] Dune 2021 Directors Cut Open Matte Remastered.mkv"),
        )

        assertEquals("Dune", media.title)
        assertEquals(2021, media.year)
        assertEquals("1080p", media.resolution)
        assertEquals(listOf("Director's Cut", "Open Matte", "Remastered"), media.editions)
    }

    @Test
    fun `parses a cyrillic movie name glued to year and language tags`() {
        val media = MediaParser.parse(
            Path.of("Дюна.2021.2160p.WEB-DL.РУС.ENG.mkv"),
        )

        assertEquals(MediaType.MOVIE, media.mediaType)
        assertEquals("Дюна", media.title)
        assertEquals(2021, media.year)
        assertEquals("2160p", media.resolution)
        assertEquals("WEB-DL", media.source)
        assertEquals(listOf("RU", "EN"), media.languages)
    }

    @Test
    fun `parses a cyrillic tv episode with unicode dashes`() {
        val media = MediaParser.parse(
            Path.of("Игра Престолов", "Игра.Престолов.S01E01.Зима.близко.1080p.WEB–DL.mkv"),
        )

        assertEquals(MediaType.TV_EPISODE, media.mediaType)
        assertEquals("Игра Престолов", media.title)
        assertEquals(1, media.season)
        assertEquals(1, media.episode)
        assertEquals("Зима близко", media.episodeTitle)
        assertEquals("1080p", media.resolution)
        assertEquals("WEB-DL", media.source)
    }

    @Test
    fun `normalizes decomposed cyrillic letters to NFC`() {
        val decomposed = Normalizer.normalize("Ёлки", Normalizer.Form.NFD)
        val media = MediaParser.parse(Path.of("$decomposed.2010.1080p.mkv"))

        assertEquals("Ёлки", media.title)
        assertEquals(2010, media.year)
    }

    @Test
    fun `finds video files with cyrillic names`() {
        val directory = Files.createTempDirectory("фильмы-")
        val video = directory.resolve("Матрица.1999.mkv")
        Files.createFile(video)
        try {
            val found = VideoScanner.findVideoFiles(directory)
            assertEquals("Матрица.1999.mkv", found.single().fileName.toString())
        } finally {
            Files.deleteIfExists(video)
            Files.deleteIfExists(directory)
        }
    }
}
