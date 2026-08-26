package movierenamer

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParserFixesTest {

    @Test
    fun `название из одного года не тащит теги за собой`() {
        val media = MediaParser.parse(Path.of("movies", "1917.1080p.BluRay.mkv"))
        assertEquals("1917", media.title)
        assertNull(media.year)
        assertEquals("1080p", media.resolution)
    }

    @Test
    fun `год названия и год релиза различаются`() {
        val media = MediaParser.parse(Path.of("movies", "1917.2019.1080p.mkv"))
        assertEquals("1917", media.title)
        assertEquals(2019, media.year)
    }

    @Test
    fun `версия фильма и источник не теряются в новом имени`() {
        val name = NameFormatter.fileName(
            movie(
                title = "Interstate 60",
                year = 2002,
                originalTitle = "Interstate 60",
                russianTitle = "Трасса 60",
                resolution = "1080p",
                source = "WEBRip",
                editions = listOf("Extended"),
            ),
            "mkv",
        )
        assertTrue("Extended" in name, "потеряли версию фильма: $name")
        assertTrue("WEBRip" in name, "потеряли источник: $name")
        assertTrue("1080p" in name, "потеряли разрешение: $name")
    }

    @Test
    fun `новое имя читается парсером обратно`() {
        val original = movie(
            title = "Interstate 60",
            year = 2002,
            originalTitle = "Interstate 60",
            russianTitle = "Трасса 60",
            resolution = "1080p",
            source = "WEBRip",
            editions = listOf("Extended"),
        )
        val name = NameFormatter.fileName(original, "mkv")
        val reparsed = MediaParser.parse(Path.of("movies", name))
        assertEquals(2002, reparsed.year)
        assertEquals("1080p", reparsed.resolution)
        assertEquals("WEBRip", reparsed.source)
        assertEquals(listOf("Extended"), reparsed.editions)
        assertEquals(
            name,
            NameFormatter.fileName(
                reparsed.copy(
                    originalTitle = original.originalTitle,
                    russianTitle = original.russianTitle,
                ),
                "mkv",
            ),
        )
    }

    @Test
    fun `транслит знает мягкий знак и и краткое`() {
        assertEquals("незнайка на луне", TitleCatalog.latinToCyrillic("Neznayka na Lune"))
        assertEquals("ворошиловский стрелок", TitleCatalog.latinToCyrillic("Voroshilovskiy Strelok"))
        assertEquals("брат", TitleCatalog.latinToCyrillic("brat"))
    }

    @Test
    fun `старые удачные случаи не сломались`() {
        assertEquals("о чём говорят мужчины", TitleCatalog.latinToCyrillic("O chjom govorjat muzhchiny"))
        assertEquals("мужчины", TitleCatalog.latinToCyrillic("muzhchiny"))
        assertEquals("о чём ещё говорят мужчины", TitleCatalog.latinToCyrillic("O chjom ewjo govorjat muzhchiny"))
    }

    @Test
    fun `Den Radio совпадает с День радио`() {
        assertMatches("Den Radio", "День радио")
    }

    @Test
    fun `Vasilyevich совпадает с Васильевич`() {
        assertMatches("Ivan Vasilyevich menyaet professiyu", "Иван Васильевич меняет профессию")
    }

    @Test
    fun `окончание -ые не превращается в мягкий знак`() {
        assertMatches("Mertvye dushi", "Мёртвые души")
        assertMatches("Utomlyonnye solntsem", "Утомлённые солнцем")
    }

    @Test
    fun `э и е в начале слова это одна буква`() {
        assertMatches("Ekipazh", "Экипаж")
        assertTrue(
            TitleCatalog.titleKeys("Ekipazh").intersect(TitleCatalog.titleKeys("Экипаж 2")).isEmpty(),
        )
    }

    @Test
    fun `остальные типовые русские названия`() {
        assertMatches("Ironiya sudby", "Ирония судьбы")
        assertMatches("Serdtsa chetyryokh", "Сердца четырёх")
        assertMatches("Beloe solntse pustyni", "Белое солнце пустыни")
        assertMatches("Vyuga", "Вьюга")
    }

    @Test
    fun `транслит не превращается в перевод`() {
        val fromFile = TitleCatalog.titleKeys("dune")
        val fromCatalog = TitleCatalog.titleKeys("Дюна")
        assertTrue(fromFile.intersect(fromCatalog).isEmpty())
    }

    @Test
    fun `без года несколько одинаковых названий это отказ`() {
        val hits = listOf(
            hit(title = "Дюна", year = 2021, id = 1),
            hit(title = "Дюна", year = 1984, id = 2),
        )
        assertNull(TitleCatalog.pickBest(movie(title = "Дюна", year = null), hits))
    }

    @Test
    fun `без года одно совпадение принимается`() {
        val only = hit(title = "Чудаки", year = 2002, id = 1)
        val hits = listOf(only, hit(title = "Чудаки 2", year = 2006, id = 2))
        assertSame(only, TitleCatalog.pickBest(movie(title = "Чудаки", year = null), hits))
    }

    @Test
    fun `год разводит одинаковые названия`() {
        val dune2021 = hit(title = "Дюна", year = 2021, id = 1)
        val hits = listOf(dune2021, hit(title = "Дюна", year = 1984, id = 2))
        assertSame(dune2021, TitleCatalog.pickBest(movie(title = "Дюна", year = 2021), hits))
    }

    @Test
    fun `частичное совпадение не прощает разницу в год`() {
        val local = movie(title = "О чём евё говорят мужчины", year = 2011)
        val wrong = hit(title = "О чём говорят мужчины", year = 2010, id = 1)
        assertNull(TitleCatalog.pickBest(local, listOf(wrong)))
    }

    @Test
    fun `частичное совпадение с точным годом проходит`() {
        val local = movie(title = "О чём евё говорят мужчины", year = 2011)
        val right = hit(title = "О чём ещё говорят мужчины", year = 2011, id = 1)
        assertSame(right, TitleCatalog.pickBest(local, listOf(right)))
    }

    @Test
    fun `лесенка режет название с обеих сторон`() {
        val queries = TitleCatalog.shortenedQueries("O chjom ewjo govorjat muzhchiny")
        assertTrue("говорят мужчины" in queries, "нет короткого хвоста: $queries")
    }

    @Test
    fun `короткое название не режется`() {
        assertTrue(TitleCatalog.shortenedQueries("Брат").isEmpty())
    }

    @Test
    fun `1080 без p это разрешение а не часть названия`() {
        val media = MediaParser.parse(Path.of("movies", "12 стульев.1080.mkv"))
        assertEquals("12 стульев", media.title)
        assertNull(media.year)
        assertEquals("1080p", media.resolution)
    }

    @Test
    fun `HDTVRip и хвост трекера не лезут в название`() {
        val media = MediaParser.parse(
            Path.of(
                "Ace_Ventura_Pet_Detective_HDTVRip_1080p_DVD9_DXVA_DIMAPIKS[torrents.ru].mkv",
            ),
        )
        assertEquals("Ace Ventura Pet Detective", media.title)
        assertEquals("1080p", media.resolution)
        assertEquals("HDTVRip", media.source)
    }

    @Test
    fun `HDDVDRip не часть названия`() {
        val media = MediaParser.parse(
            Path.of("Ocean's.Eleven.HDDVDRip.1080p.x264.HANSMER.mkv"),
        )
        assertEquals("Ocean's Eleven", media.title)
        assertNull(media.year)
        assertEquals("1080p", media.resolution)
        assertEquals("HDDVDRip", media.source)
    }

    @Test
    fun `сцена-группа в конце не часть названия`() {
        val media = MediaParser.parse(Path.of("Клик с пультом hns-cl.mkv"))
        assertEquals("Клик с пультом", media.title)
    }

    @Test
    fun `X-Men в конце имени не снимается как группа`() {
        val media = MediaParser.parse(Path.of("X-Men.mkv"))
        assertEquals("X-Men", media.title)
    }

    @Test
    fun `The Father режется по источнику`() {
        val media = MediaParser.parse(Path.of("The.Father.BDRip.1080p.HD.m4v"))
        assertEquals("The Father", media.title)
        assertEquals("BDRip", media.source)
        assertEquals("1080p", media.resolution)
    }

    @Test
    fun `Клик с пультом без года совпадает с полным русским названием`() {
        val click = hit(title = "Клик: С пультом по жизни", year = 2006, id = 1)
        assertSame(click, TitleCatalog.pickBest(movie(title = "Клик с пультом", year = null), listOf(click)))
    }

    @Test
    fun `The Father без года не берёт The Father of the Bride`() {
        val bride = hit(title = "The Father of the Bride", year = 1991, id = 2)
        assertNull(TitleCatalog.pickBest(movie(title = "The Father", year = null), listOf(bride)))
    }

    @Test
    fun `The Father без года и два фильма это отказ`() {
        assertNull(
            TitleCatalog.pickBest(
                movie(title = "The Father", year = null),
                listOf(
                    hit(title = "The Father", year = 2020, id = 1),
                    hit(title = "The Father", year = 1979, id = 2),
                ),
            ),
        )
    }

    @Test
    fun `две версии 12 стульев без года это отказ с подсказкой`() {
        val local = movie(title = "12 стульев", year = null)
        assertNull(
            TitleCatalog.pickBest(
                local,
                listOf(
                    hit(title = "12 стульев", year = 1971, id = 1),
                    hit(title = "12 стульев", year = 1976, id = 2),
                ),
            ),
        )
        val note = TitleCatalog.noteFor(local)
        assertTrue(note != null && "1971" in note && "1976" in note, "нет подсказки с годами: $note")
    }

    @Test
    fun `X Cut это версия а не часть названия`() {
        val media = MediaParser.parse(Path.of("Clerks.X.Cut.1080p.x264.Perevodman.mkv"))
        assertEquals("Clerks", media.title)
        assertEquals(listOf("X Cut"), media.editions)
        assertEquals("1080p", media.resolution)
    }

    @Test
    fun `The Father без года принимает единственный год и игнорирует пустой`() {
        val hopkins = hit(title = "The Father", year = 2020, id = 1)
        assertSame(
            hopkins,
            TitleCatalog.pickBest(
                movie(title = "The Father", year = null),
                listOf(
                    hit(title = "The Father", year = null, id = 2),
                    hopkins,
                ),
            ),
        )
    }

    @Test
    fun `Prodolzenie это продолжение`() {
        assertEquals(
            "о чем говорят мужчины продолжение",
            TitleCatalog.latinToCyrillic("O chem govorjat muzhchiny Prodolzenie"),
        )
        val sequel = hit(title = "О чём ещё говорят мужчины", year = 2011, id = 2)
        val original = hit(title = "О чём говорят мужчины", year = 2010, id = 1)
        val local = movie(title = "O chem govorjat muzhchiny Prodolzenie", year = null)
        assertSame(sequel, TitleCatalog.pickBest(local, listOf(original, sequel)))
        assertNull(TitleCatalog.pickBest(local, listOf(original)))
    }

    private fun assertMatches(fromFile: String, fromCatalog: String) {
        val left = TitleCatalog.titleKeys(fromFile)
        val right = TitleCatalog.titleKeys(fromCatalog)
        assertTrue(
            left.intersect(right).isNotEmpty(),
            "«$fromFile» не сошлось с «$fromCatalog»: $left против $right",
        )
    }

    private fun movie(
        title: String,
        year: Int?,
        originalTitle: String? = null,
        russianTitle: String? = null,
        resolution: String? = null,
        source: String? = null,
        editions: List<String> = emptyList(),
        languages: List<String> = emptyList(),
    ) = MediaInfo(
        mediaType = MediaType.MOVIE,
        title = title,
        year = year,
        season = null,
        episode = null,
        episodeTitle = null,
        resolution = resolution,
        source = source,
        editions = editions,
        languages = languages,
        originalTitle = originalTitle,
        russianTitle = russianTitle,
    )

    private fun hit(title: String, year: Int?, id: Int) = CatalogHit(
        site = "TMDB",
        title = title,
        year = year,
        pageUrl = null,
        russianTitle = title,
        catalogId = id,
    )
}
