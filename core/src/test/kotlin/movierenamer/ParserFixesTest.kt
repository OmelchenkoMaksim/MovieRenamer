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
