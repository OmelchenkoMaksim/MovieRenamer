package movierenamer

import java.io.IOException
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
            "The Matrix (1999) 1080p.mkv",
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
            assertEquals("The Matrix (1999) 1080p.mkv", plan.proposedName)
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
    fun `catalog matches an english filename against an english TMDB title`() {
        val local = MediaParser.parse(Path.of("Shaolin.Soccer.2001.Ch_ver.1080p.BDRip.mkv"))
        assertEquals("Shaolin Soccer", local.title)
        assertEquals(2001, local.year)

        val russianOnly = CatalogHit(
            "TMDB",
            "Шаолиньский футбол",
            2001,
            null,
            originalTitle = "少林足球",
            russianTitle = "Шаолиньский футбол",
        )
        assertNull(TitleCatalog.pickBest(local, listOf(russianOnly)))

        val englishSearch = CatalogHit(
            "TMDB",
            "Shaolin Soccer",
            2001,
            null,
            originalTitle = "少林足球",
        )
        assertEquals("Shaolin Soccer", TitleCatalog.pickBest(local, listOf(englishSearch))?.title)
    }

    @Test
    fun `catalog matches a romanized russian title like brat to Брат`() {
        assertEquals("брат", TitleCatalog.latinToCyrillic("brat"))
        assertEquals(listOf("brat", "брат"), TitleCatalog.searchQueries("brat"))

        val local = MediaParser.parse(Path.of("brat.1997.dvdrip.mp4"))
        val hit = TitleCatalog.pickBest(
            local,
            listOf(
                CatalogHit("ПоискКино", "Брат 2", 2000, null, originalTitle = "Brat 2", russianTitle = "Брат 2"),
                CatalogHit("ПоискКино", "Брат", 1997, null, originalTitle = "Brat", russianTitle = "Брат"),
            ),
        )

        assertEquals("Брат", hit?.title)
        assertEquals(1997, hit?.year)
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
        assertTrue(FileGuard.isDebugResultsPath(Config.debugResults))
        assertFalse(FileGuard.isDebugResultsPath(Path.of("/tmp/MovieRenamer/debug/results")))
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

    @Test
    fun `file guard refuses a source behind a parent symlink`() {
        val library = Files.createTempDirectory("library-")
        val outside = Files.createTempDirectory("outside-")
        val outsideVideo = outside.resolve("old.mkv")
        val link = library.resolve("link")
        Files.writeString(outsideVideo, "media")
        try {
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: IOException) {
                return
            }

            val linkedSource = link.resolve("old.mkv")
            val linkedTarget = link.resolve("new.mkv")
            assertFalse(FileGuard.isInside(library, linkedSource))
            assertFailsWith<IllegalStateException> {
                FileGuard.copy(library, linkedSource, linkedTarget)
            }
            assertFalse(Files.exists(outside.resolve("new.mkv")))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(outsideVideo)
            Files.deleteIfExists(outside)
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `keeps a year that belongs to the title and uses the later release year`() {
        val odyssey = MediaParser.parse(Path.of("2001.A.Space.Odyssey.1968.1080p.mkv"))
        assertEquals("2001 A Space Odyssey", odyssey.title)
        assertEquals(1968, odyssey.year)
        assertEquals("1080p", odyssey.resolution)

        val blade = MediaParser.parse(Path.of("Blade.Runner.2049.2017.UHD.mkv"))
        assertEquals("Blade Runner 2049", blade.title)
        assertEquals(2017, blade.year)
        assertEquals("UHD", blade.resolution)
    }

    @Test
    fun `parses the earliest supported cinema years`() {
        val media = MediaParser.parse(Path.of("Workers.Leaving.the.Lumiere.Factory.1895.mkv"))

        assertEquals("Workers Leaving the Lumiere Factory", media.title)
        assertEquals(1895, media.year)
    }

    @Test
    fun `does not treat language words inside a title as release tags`() {
        val patient = MediaParser.parse(Path.of("The.English.Patient.1996.1080p.mkv"))
        assertEquals("The English Patient", patient.title)
        assertEquals(1996, patient.year)
        assertTrue(patient.languages.isEmpty())

        val doll = MediaParser.parse(
            Path.of("Russian Doll", "Russian.Doll.2019.S01E01.1080p.WEB-DL.mkv"),
        )
        assertEquals("Russian Doll", doll.title)
        assertTrue(doll.languages.isEmpty())
    }

    @Test
    fun `takes a series title above a season folder`() {
        val media = MediaParser.parse(
            Path.of("Breaking Bad", "Season 2", "Breaking.Bad.S02E03.720p.WEB-DL.mkv"),
        )

        assertEquals("Breaking Bad", media.title)
        assertEquals(2, media.season)
        assertEquals(3, media.episode)
    }

    @Test
    fun `unwraps a year with a russian suffix and leftover parentheses`() {
        val media = MediaParser.parse(Path.of("Унесенные призраками(2001г 1080p).mkv"))

        assertEquals("Унесенные призраками", media.title)
        assertEquals(2001, media.year)
        assertEquals("1080p", media.resolution)
    }

    @Test
    fun `parses a bilingual russian english release name`() {
        val media = MediaParser.parse(
            Path.of("Управление гневом.Anger Management.2003.BDRip.1080p.h264.Rus.Eng.mkv"),
        )

        assertEquals("Управление гневом Anger Management", media.title)
        assertEquals(2003, media.year)
        assertEquals("1080p", media.resolution)
        assertEquals("BDRip", media.source)
        assertEquals(listOf("RU", "EN"), media.languages)
    }

    @Test
    fun `treats 4KRip as a source so it does not stick to the title`() {
        val media = MediaParser.parse(Path.of("Особенности национальной охоты 4KRip 1080p.mkv"))

        assertEquals("Особенности национальной охоты", media.title)
        assertNull(media.year)
        assertEquals("1080p", media.resolution)
        assertEquals("4KRip", media.source)
    }

    @Test
    fun `still detects a standalone 4K resolution`() {
        val media = MediaParser.parse(Path.of("Dune.2021.4K.mkv"))
        assertEquals("Dune", media.title)
        assertEquals(2021, media.year)
        assertEquals("4K", media.resolution)
    }

    @Test
    fun `catalog matches yo and ye as the same letter`() {
        val local = MediaParser.parse(Path.of("Унесенные призраками(2001г 1080p).mkv"))
        val hit = TitleCatalog.pickBest(
            local,
            listOf(
                CatalogHit(
                    "TMDB",
                    "Унесённые призраками",
                    2001,
                    null,
                    originalTitle = "千と千尋の神隠し",
                    russianTitle = "Унесённые призраками",
                ),
            ),
        )

        assertEquals("Унесённые призраками", hit?.title)
        assertEquals(2001, hit?.year)
    }

    @Test
    fun `catalog matches either side of a bilingual filename`() {
        val local = MediaParser.parse(
            Path.of("Управление гневом.Anger Management.2003.BDRip.1080p.mkv"),
        )
        val hit = TitleCatalog.pickBest(
            local,
            listOf(
                CatalogHit(
                    "TMDB",
                    "Управление гневом",
                    2003,
                    null,
                    originalTitle = "Anger Management",
                    russianTitle = "Управление гневом",
                ),
            ),
        )

        assertEquals("Управление гневом", hit?.title)
        assertEquals("Anger Management", hit?.originalTitle)
        assertEquals(2003, hit?.year)
    }

    @Test
    fun `catalog matches a bracketed russian title next to the english name`() {
        val local = MediaParser.parse(
            Path.of("Kung Fu Hustle [Разборки в стиле кунг-фу] (2004) BDRip.mkv"),
        )
        val hit = TitleCatalog.pickBest(
            local,
            listOf(
                CatalogHit(
                    "TMDB",
                    "Разборки в стиле кунг-фу",
                    2004,
                    null,
                    originalTitle = "功夫",
                    russianTitle = "Разборки в стиле кунг-фу",
                ),
            ),
        )

        assertEquals("Разборки в стиле кунг-фу", hit?.title)
        assertTrue(TitleCatalog.searchQueries(local.title).any { it.equals("Kung Fu Hustle", ignoreCase = true) })
        assertTrue(TitleCatalog.searchQueries(local.title).any { it.contains("Разборки") })
    }

    @Test
    fun `catalog can supply a missing year for an exact russian title`() {
        val local = MediaParser.parse(Path.of("Особенности национальной охоты 4KRip 1080p.mkv"))
        val hit = TitleCatalog.pickBest(
            local,
            listOf(
                CatalogHit(
                    "ПоискКино",
                    "Особенности национальной охоты",
                    1995,
                    null,
                    originalTitle = "Особенности национальной охоты",
                    russianTitle = "Особенности национальной охоты",
                ),
            ),
        )

        assertEquals("Особенности национальной охоты", hit?.title)
        assertEquals(1995, hit?.year)
    }

    @Test
    fun `uses an english TMDB translation when the original title is not latin`() {
        val hit = TitleCatalog.parseTmdbMovieDetails(
            """
            {
              "id": 129,
              "title": "Унесённые призраками",
              "original_title": "千と千尋の神隠し",
              "original_language": "ja",
              "release_date": "2001-07-20",
              "vote_average": 8.5,
              "genres": [{"id": 16, "name": "мультфильм"}],
              "credits": {
                "cast": [{"name": "Руми Хиираги", "order": 0}],
                "crew": [{"name": "Хаяо Миядзаки", "job": "Director"}]
              },
              "translations": {
                "translations": [
                  {
                    "iso_639_1": "en",
                    "data": {"title": "Spirited Away"}
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("Унесённые призраками", hit?.russianTitle)
        assertEquals("Spirited Away", hit?.originalTitle)
        assertEquals(2001, hit?.year)
        assertEquals(listOf("Хаяо Миядзаки"), hit?.directors)
    }

    @Test
    fun `unwraps a year in parentheses and treats an already-clean name as already ok`() {
        val media = MediaParser.parse(Path.of("The Matrix (1999) 1080p.mkv"))
        assertEquals("The Matrix", media.title)
        assertEquals(1999, media.year)
        assertEquals(
            "The Matrix (1999) 1080p.mkv",
            NameFormatter.fileName(media, "mkv"),
        )

        val library = Files.createTempDirectory("library-")
        val video = library.resolve("The Matrix (1999) 1080p.mkv")
        Files.createFile(video)
        try {
            val plan = RenamePlanner.planAll(library, listOf(video)).single()
            assertEquals(PlanStatus.ALREADY_OK, plan.status)
        } finally {
            Files.deleteIfExists(video)
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `ignores a generic parent folder for a tv episode`() {
        val media = MediaParser.parse(
            Path.of("samples", "IGRA.PRESTOLOV.S01E01.1080P.WEB-DL.mkv"),
        )
        assertEquals("IGRA PRESTOLOV", media.title)
        assertEquals(1, media.season)
        assertEquals(1, media.episode)
        assertEquals("1080p", media.resolution)
        assertEquals("WEB-DL", media.source)
    }

    @Test
    fun `strips a tracker site prefix from the title`() {
        val media = MediaParser.parse(
            Path.of("www.Kinozal.Org.Interstellar.2014.1080p.WEB-DL.mkv"),
        )
        assertEquals("Interstellar", media.title)
        assertEquals(2014, media.year)
        assertEquals("1080p", media.resolution)
        assertEquals("WEB-DL", media.source)
    }

    @Test
    fun `detects WEB-DLRip as a source`() {
        val media = MediaParser.parse(Path.of("Матрица.1999.WEB-DLRip.avi"))
        assertEquals("Матрица", media.title)
        assertEquals(1999, media.year)
        assertEquals("WEB-DLRip", media.source)
    }

    @Test
    fun `strips wikipedia film disambiguation from a catalog title`() {
        assertEquals("Dune", TitleCatalog.cleanTitle("Dune (2021 film)"))
        assertEquals("The Matrix", TitleCatalog.cleanTitle("The Matrix (1999 film)"))
        assertEquals("Матрица", TitleCatalog.cleanTitle("Матрица (фильм)"))
        assertEquals("Дюна", TitleCatalog.cleanTitle("Дюна (фильм, 2021)"))
        assertEquals("Игра престолов", TitleCatalog.cleanTitle("Игра престолов (телесериал)"))
    }

    @Test
    fun `catalog requires an exact title before supplying a missing year`() {
        val local = MediaParser.parse(Path.of("Dune.1080p.mkv"))
        val hit = TitleCatalog.pickBest(
            local,
            listOf(
                CatalogHit("Wikipedia", "Dune Part Two", 2024, null),
                CatalogHit("Wikipedia", "Dune", 2021, null),
            ),
        )

        assertEquals("Dune", hit?.title)
        assertEquals(2021, hit?.year)
    }

    @Test
    fun `catalog rejects substring matches and a wrong sequel`() {
        val it = MediaParser.parse(Path.of("It.2017.mkv"))
        assertNull(
            TitleCatalog.pickBest(
                it,
                listOf(CatalogHit("Wikipedia", "Titanic", 2017, null)),
            ),
        )

        val trees = MediaParser.parse(Path.of("Ёлки.2010.mkv"))
        assertNull(
            TitleCatalog.pickBest(
                trees,
                listOf(CatalogHit("Wikipedia", "Ёлки 10", null, null)),
            ),
        )
    }

    @Test
    fun `formats rich TMDB movie metadata without release tags`() {
        val media = MediaParser.parse(Path.of("Dune.2021.2160p.WEB-DL.RUS.ENG.mkv")).copy(
            originalTitle = "Dune",
            russianTitle = "Дюна",
            originalLanguage = "en",
            genres = listOf("Фантастика", "Приключения", "Драма"),
            directors = listOf("Дени Вильнёв"),
            actors = listOf("Тимоти Шаламе", "Ребекка Фергюсон", "Оскар Айзек"),
            rating = 8.2,
            ratingSource = "TMDB",
        )

        assertEquals(
            "Dune — Дюна (2021) [Фантастика, Приключения, Драма] [Дени Вильнёв] " +
                "[Тимоти Шаламе, Ребекка Фергюсон, Оскар Айзек] (8.2 TMDB) 2160p.mkv",
            NameFormatter.fileName(media, "mkv"),
        )
    }

    @Test
    fun `uses one title for a russian movie and keeps episode names short`() {
        val movie = MediaParser.parse(Path.of("Брат.1997.1080p.BluRay.RUS.mkv")).copy(
            originalTitle = "Брат",
            russianTitle = "Брат",
            originalLanguage = "ru",
            genres = listOf("Драма", "Криминал"),
            directors = listOf("Алексей Балабанов"),
            actors = listOf("Сергей Бодров мл.", "Виктор Сухоруков", "Светлана Письмиченко"),
            rating = 7.9,
            ratingSource = "TMDB",
        )
        assertEquals(
            "Брат (1997) [Драма, Криминал] [Алексей Балабанов] " +
                "[Сергей Бодров мл., Виктор Сухоруков, Светлана Письмиченко] (7.9 TMDB) 1080p.mkv",
            NameFormatter.fileName(movie, "mkv"),
        )

        val episode = MediaParser.parse(
            Path.of("Breaking Bad", "Breaking.Bad.S02E03.Bit.by.a.Dead.Bee.720p.WEB-DL.mkv"),
        )
        assertEquals(
            "Breaking Bad S02E03 Bit by a Dead Bee 720p.mkv",
            NameFormatter.fileName(episode, "mkv"),
        )
    }

    @Test
    fun `parses TMDB details with russian title genres cast and rating`() {
        val hit = TitleCatalog.parseTmdbMovieDetails(
            """
            {
              "id": 438631,
              "title": "Дюна",
              "original_title": "Dune",
              "original_language": "en",
              "release_date": "2021-09-15",
              "vote_average": 7.8,
              "genres": [
                {"id": 878, "name": "Фантастика"},
                {"id": 12, "name": "Приключения"},
                {"id": 18, "name": "Драма"},
                {"id": 28, "name": "Боевик"}
              ],
              "credits": {
                "cast": [
                  {"name": "Оскар Айзек", "order": 2},
                  {"name": "Тимоти Шаламе", "order": 0},
                  {"name": "Ребекка Фергюсон", "order": 1},
                  {"name": "Джейсон Момоа", "order": 3}
                ],
                "crew": [
                  {"name": "Дени Вильнёв", "job": "Director"},
                  {"name": "Джо Спайкс", "job": "Producer"}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("Дюна", hit?.russianTitle)
        assertEquals("Dune", hit?.originalTitle)
        assertEquals(2021, hit?.year)
        assertEquals(listOf("Фантастика", "Приключения", "Драма"), hit?.genres)
        assertEquals(listOf("Дени Вильнёв"), hit?.directors)
        assertEquals(listOf("Тимоти Шаламе", "Ребекка Фергюсон", "Оскар Айзек"), hit?.actors)
        assertEquals(7.8, hit?.rating)
        assertEquals("TMDB", hit?.ratingSource)
    }

    @Test
    fun `limits rich file names for windows`() {
        val media = MediaParser.parse(Path.of("Movie.2020.1080p.mkv")).copy(
            originalTitle = "A".repeat(180),
            russianTitle = "Б".repeat(180),
            originalLanguage = "en",
            genres = listOf("Фантастика", "Приключения", "Драма"),
            actors = listOf("Первый Актёр", "Второй Актёр", "Третий Актёр"),
            rating = 8.5,
            ratingSource = "TMDB",
        )

        val name = NameFormatter.fileName(media, "mkv")
        assertTrue(name.length <= 240)
        assertTrue(name.endsWith("1080p.mkv"))
    }

    @Test
    fun `parses PoiskKino details with russian title genres cast and kp rating`() {
        val hit = TitleCatalog.parsePoiskKinoMovieDetails(
            """
            {
              "id": 301,
              "name": "Матрица",
              "alternativeName": "The Matrix",
              "enName": "The Matrix",
              "type": "movie",
              "year": 1999,
              "isSeries": false,
              "rating": { "kp": 8.5, "imdb": 8.7, "tmdb": 8.1 },
              "genres": [
                {"name": "фантастика"},
                {"name": "боевик"},
                {"name": "триллер"},
                {"name": "драма"}
              ],
              "persons": [
                {"name": "Киану Ривз", "enProfession": "actor", "profession": "актеры"},
                {"name": "Лоренс Фишбёрн", "enProfession": "actor", "profession": "актеры"},
                {"name": "Кэрри-Энн Мосс", "enProfession": "actor", "profession": "актеры"},
                {"name": "Лана Вачовски", "enProfession": "director", "profession": "режиссеры"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("Матрица", hit?.russianTitle)
        assertEquals("The Matrix", hit?.originalTitle)
        assertEquals(1999, hit?.year)
        assertEquals(listOf("Фантастика", "Боевик", "Триллер"), hit?.genres)
        assertEquals(listOf("Лана Вачовски"), hit?.directors)
        assertEquals(listOf("Киану Ривз", "Лоренс Фишбёрн", "Кэрри-Энн Мосс"), hit?.actors)
        assertEquals(8.5, hit?.rating)
        assertEquals("КП", hit?.ratingSource)
        assertEquals("ПоискКино", hit?.site)
    }

    @Test
    fun `skips series in a PoiskKino search payload`() {
        val hits = TitleCatalog.parsePoiskKinoSearch(
            """
            {
              "docs": [
                {
                  "id": 1,
                  "name": "Игра престолов",
                  "alternativeName": "Game of Thrones",
                  "type": "tv-series",
                  "year": 2011,
                  "isSeries": true
                },
                {
                  "id": 603,
                  "name": "Матрица",
                  "alternativeName": "The Matrix",
                  "type": "movie",
                  "year": 1999,
                  "isSeries": false,
                  "rating": { "kp": 8.5 }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, hits.size)
        assertEquals("Матрица", hits.single().russianTitle)
        assertEquals(1999, hits.single().year)
    }

    @Test
    fun `fills missing catalog fields from the second service`() {
        val tmdb = CatalogHit(
            site = "TMDB",
            title = "Dune",
            year = 2021,
            pageUrl = "https://www.themoviedb.org/movie/438631",
            originalTitle = "Dune",
            russianTitle = null,
            originalLanguage = "en",
            genres = emptyList(),
            actors = listOf("Timothée Chalamet", "Rebecca Ferguson", "Oscar Isaac"),
            rating = 7.8,
            ratingSource = "TMDB",
            catalogId = 438631,
        )
        val poiskKino = CatalogHit(
            site = "ПоискКино",
            title = "Дюна",
            year = 2021,
            pageUrl = "https://www.kinopoisk.ru/film/409600/",
            originalTitle = "Dune",
            russianTitle = "Дюна",
            genres = listOf("Фантастика", "Приключения", "Драма"),
            actors = listOf("Тимоти Шаламе", "Ребекка Фергюсон", "Оскар Айзек"),
            rating = 7.7,
            ratingSource = "КП",
            catalogId = 409600,
        )

        val merged = TitleCatalog.mergeCatalogHits(tmdb, poiskKino)

        assertEquals("TMDB + ПоискКино", merged?.site)
        assertEquals("Dune", merged?.originalTitle)
        assertEquals("Дюна", merged?.russianTitle)
        assertEquals(listOf("Фантастика", "Приключения", "Драма"), merged?.genres)
        assertEquals(listOf("Timothée Chalamet", "Rebecca Ferguson", "Oscar Isaac"), merged?.actors)
        assertEquals(7.8, merged?.rating)
        assertEquals("TMDB", merged?.ratingSource)
    }

    @Test
    fun `uses PoiskKino rating when TMDB has none`() {
        val tmdb = CatalogHit(
            site = "TMDB",
            title = "Dune",
            year = 2021,
            pageUrl = null,
            originalTitle = "Dune",
            russianTitle = "Дюна",
        )
        val poiskKino = CatalogHit(
            site = "ПоискКино",
            title = "Дюна",
            year = 2021,
            pageUrl = null,
            rating = 6.4,
            ratingSource = "КП",
        )
        val merged = TitleCatalog.mergeCatalogHits(tmdb, poiskKino)
        assertEquals(6.4, merged?.rating)
        assertEquals("КП", merged?.ratingSource)

        val media = MediaParser.parse(Path.of("Dune.2021.1080p.mkv")).copy(
            originalTitle = "Dune",
            russianTitle = "Дюна",
            originalLanguage = "en",
            rating = 6.4,
            ratingSource = "TMDB",
        )
        assertEquals("Dune — Дюна (2021) (6.4 TMDB) 1080p.mkv", NameFormatter.fileName(media, "mkv"))
    }

    @Test
    fun `keeps a complete TMDB hit and ignores fallback catalogs`() {
        val local = MediaParser.parse(Path.of("Dune.2021.mkv"))
        val tmdb = CatalogHit(
            site = "TMDB",
            title = "Дюна",
            year = 2021,
            pageUrl = "https://www.themoviedb.org/movie/438631",
            originalTitle = "Dune",
            russianTitle = "Дюна",
            originalLanguage = "en",
            genres = listOf("Фантастика"),
            directors = listOf("Дени Вильнёв"),
            actors = listOf("Тимоти Шаламе"),
            rating = 7.8,
            ratingSource = "TMDB",
        )
        val chosen = TitleCatalog.chooseMovieHit(
            local,
            tmdb = tmdb,
            poiskKino = CatalogHit("ПоискКино", "Дюна", 2021, null, rating = 7.7, ratingSource = "КП"),
            fallbackHits = listOf(CatalogHit("iTunes", "Dune", 2021, null)),
        )

        assertEquals("TMDB", chosen?.site)
        assertEquals(7.8, chosen?.rating)
        assertEquals("TMDB", chosen?.ratingSource)
        assertFalse(TitleCatalog.movieHitNeedsMoreData(tmdb))
    }

    @Test
    fun `asks PoiskKino only for fields TMDB does not have`() {
        val local = MediaParser.parse(Path.of("Dune.2021.mkv"))
        val tmdb = CatalogHit(
            site = "TMDB",
            title = "Dune",
            year = 2021,
            pageUrl = null,
            originalTitle = "Dune",
            russianTitle = null,
            actors = listOf("Timothée Chalamet"),
            rating = 7.8,
            ratingSource = "TMDB",
        )
        val poiskKino = CatalogHit(
            site = "ПоискКино",
            title = "Дюна",
            year = 2021,
            pageUrl = null,
            russianTitle = "Дюна",
            genres = listOf("Фантастика", "Приключения"),
            rating = 7.7,
            ratingSource = "КП",
        )
        val chosen = TitleCatalog.chooseMovieHit(
            local,
            tmdb,
            poiskKino,
            fallbackHits = listOf(CatalogHit("Wikipedia (en)", "Dune", 2021, null)),
        )

        assertTrue(TitleCatalog.movieHitNeedsMoreData(tmdb))
        assertEquals("TMDB + ПоискКино", chosen?.site)
        assertEquals("Дюна", chosen?.russianTitle)
        assertEquals(listOf("Фантастика", "Приключения"), chosen?.genres)
        assertEquals(listOf("Timothée Chalamet"), chosen?.actors)
        assertEquals(7.8, chosen?.rating)
        assertEquals("TMDB", chosen?.ratingSource)
    }

    @Test
    fun `falls back to iTunes and Wikipedia when TMDB and PoiskKino return nothing`() {
        val local = MediaParser.parse(Path.of("The.Matrix.1999.mkv"))
        val chosen = TitleCatalog.chooseMovieHit(
            local,
            tmdb = null,
            poiskKino = null,
            fallbackHits = listOf(
                CatalogHit("iTunes", "The Matrix Reloaded", 2003, null),
                CatalogHit("iTunes", "The Matrix", 1999, null),
                CatalogHit("Wikipedia (en)", "The Matrix", 1999, null),
            ),
        )

        assertTrue(TitleCatalog.movieHitNeedsMoreData(null))
        assertEquals("The Matrix", chosen?.title)
        assertEquals(1999, chosen?.year)
        assertTrue(chosen?.site in setOf("iTunes", "Wikipedia (en)"))
    }

    @Test
    fun `uses PoiskKino when TMDB is down and skips fallback catalogs`() {
        val local = MediaParser.parse(Path.of("Матрица.1999.mkv"))
        val poiskKino = CatalogHit(
            site = "ПоискКино",
            title = "Матрица",
            year = 1999,
            pageUrl = "https://www.kinopoisk.ru/film/301/",
            originalTitle = "The Matrix",
            russianTitle = "Матрица",
            genres = listOf("Фантастика"),
            directors = listOf("Лана Вачовски"),
            actors = listOf("Киану Ривз"),
            rating = 8.5,
            ratingSource = "КП",
        )
        val chosen = TitleCatalog.chooseMovieHit(
            local,
            tmdb = null,
            poiskKino = poiskKino,
            fallbackHits = listOf(CatalogHit("iTunes", "The Matrix", 1999, null)),
        )

        assertEquals("ПоискКино", chosen?.site)
        assertEquals(8.5, chosen?.rating)
        assertEquals("КП", chosen?.ratingSource)
    }

    @Test
    fun `revert cache stores new name to original name pairs`() {
        val cache = Files.createTempFile("revert-cache-", ".json")
        val library = Files.createTempDirectory("library-")
        try {
            NameHistory.save(
                library,
                mapOf("The Matrix (1999) 1080p.mkv" to "The.Matrix.1999.mkv"),
                cache,
            )
            val loaded = NameHistory.load(cache)
            assertEquals(library.toAbsolutePath().normalize(), loaded.directory?.toAbsolutePath()?.normalize())
            assertEquals("The.Matrix.1999.mkv", loaded.pairs["The Matrix (1999) 1080p.mkv"])
        } finally {
            Files.deleteIfExists(cache)
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `revert planner restores the original name when the cache has a pair`() {
        val library = Files.createTempDirectory("library-")
        val renamed = library.resolve("The Matrix (1999) 1080p.mkv")
        Files.createFile(renamed)
        try {
            val plan = RevertPlanner.planAll(
                library,
                listOf(renamed),
                mapOf("The Matrix (1999) 1080p.mkv" to "The.Matrix.1999.mkv"),
            ).single()

            assertEquals(PlanStatus.READY, plan.status)
            assertEquals("The.Matrix.1999.mkv", plan.proposedName)

            FileGuard.rename(library, renamed, plan.target!!)
            assertFalse(Files.exists(renamed))
            assertTrue(Files.exists(library.resolve("The.Matrix.1999.mkv")))
        } finally {
            Files.deleteIfExists(renamed)
            Files.deleteIfExists(library.resolve("The.Matrix.1999.mkv"))
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `revert planner leaves a file without a cache pair unclear`() {
        val library = Files.createTempDirectory("library-")
        val video = library.resolve("other.mkv")
        Files.createFile(video)
        try {
            val plan = RevertPlanner.planAll(
                library,
                listOf(video),
                mapOf("The Matrix (1999) 1080p.mkv" to "The.Matrix.1999.mkv"),
            ).single()
            assertEquals(PlanStatus.UNCLEAR, plan.status)
            assertTrue(plan.reasons.any { it.contains("кэше") })
        } finally {
            Files.deleteIfExists(video)
            Files.deleteIfExists(library)
        }
    }

    @Test
    fun `chained rename keeps the very first original name`() {
        val previous = mapOf("The Matrix (1999) 1080p.mkv" to "The.Matrix.1999.mkv")
        val original = previous["The Matrix (1999) 1080p.mkv"] ?: "The Matrix (1999) 1080p.mkv"
        assertEquals("The.Matrix.1999.mkv", original)
    }
}
