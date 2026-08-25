package movierenamer

import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `file guard does not treat paths outside the library as inside`() {
        val library = Files.createTempDirectory("library-")
        val outside = Files.createTempDirectory("outside-")
        try {
            val inside = library.resolve("фильм.mkv")
            Files.createFile(inside)

            assertTrue(FileGuard.isInside(library, inside))
            assertFalse(FileGuard.isInside(library, outside.resolve("чужой.mkv")))
        } finally {
            Files.deleteIfExists(library.resolve("фильм.mkv"))
            Files.deleteIfExists(library)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `file guard renames inside the library and never deletes the file`() {
        val library = Files.createTempDirectory("library-")
        val video = library.resolve("old.mkv")
        Files.writeString(video, "media")
        val renamed = library.resolve("new.mkv")
        try {
            FileGuard.rename(library, video, renamed)
            assertFalse(Files.exists(video))
            assertEquals("media", Files.readString(renamed))
        } finally {
            Files.deleteIfExists(video)
            Files.deleteIfExists(renamed)
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `file guard copies with a new name and keeps the original`() {
        val library = Files.createTempDirectory("library-")
        val video = library.resolve("old.mkv")
        Files.writeString(video, "media")
        val copy = library.resolve("new.mkv")
        try {
            FileGuard.copy(library, video, copy)
            assertEquals("media", Files.readString(video))
            assertEquals("media", Files.readString(copy))
        } finally {
            Files.deleteIfExists(video)
            Files.deleteIfExists(copy)
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `formats a movie target name`() {
        val media = MediaParser.parse(
            Path.of("The.Matrix.1999.1080p.BluRay.x264.RUS.ENG.mkv"),
        )
        assertEquals(
            "The Matrix (1999) 1080p BluRay RU EN.mkv",
            NameFormatter.fileName(media, "mkv"),
        )
    }

    @Test
    fun `preview marks a movie as ready when title and year are known`() {
        val library = Files.createTempDirectory("library-")
        val video = library.resolve("The.Matrix.1999.1080p.BluRay.mkv")
        Files.createFile(video)
        try {
            val plan = RenamePlanner.planAll(library, listOf(video)).single()
            assertEquals(PlanStatus.READY, plan.status)
            assertEquals("The Matrix (1999) 1080p BluRay.mkv", plan.proposedName)
        } finally {
            Files.deleteIfExists(video)
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `preview rejects a file without a year as unclear`() {
        val library = Files.createTempDirectory("library-")
        val video = library.resolve("random-clip.mkv")
        Files.createFile(video)
        try {
            val plan = RenamePlanner.planAll(library, listOf(video)).single()
            assertEquals(PlanStatus.UNCLEAR, plan.status)
            assertTrue(plan.reasons.any { it.contains("года") })
        } finally {
            Files.deleteIfExists(video)
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `catalog prefers the title with the matching year`() {
        val local = MediaParser.parse(Path.of("The.Matrix.1999.1080p.mkv"))
        val hit = TitleCatalog.pickBest(
            local,
            listOf(
                CatalogHit("iTunes", "The Matrix Reloaded", 2003, null),
                CatalogHit("iTunes", "The Matrix", 1999, null),
                CatalogHit("Wikipedia (en)", "Matrix (mathematics)", 2020, null),
            ),
        )
        assertEquals("The Matrix", hit?.title)
        assertEquals(1999, hit?.year)
    }

    @Test
    fun `debug results path is only debug slash results`() {
        assertTrue(FileGuard.isDebugResultsPath(Path.of("/tmp/MovieRenamer/debug/results")))
        assertFalse(FileGuard.isDebugResultsPath(Path.of("/tmp/MovieRenamer/debug/samples")))
        assertFalse(FileGuard.isDebugResultsPath(Path.of("/tmp/movies")))
    }

    @Test
    fun `prepareResultsDirectory refuses to clean a random folder`() {
        val random = Files.createTempDirectory("not-debug-results")
        try {
            assertFailsWith<IllegalStateException> {
                FileGuard.prepareResultsDirectory(random)
            }
        } finally {
            Files.deleteIfExists(random)
        }
    }
}
