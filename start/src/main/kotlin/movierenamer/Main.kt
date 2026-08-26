package movierenamer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

// 0000 Настройки старта. DEBUG: debug/samples → debug/results.
// DEBUG_REVERT: debug/results → debug/reverted (если results пуста — только лог).

// Укажите ПАПКУ с видео, не путь к отдельному фильму.
// Windows: для C:\Users\Ivan\Desktop\Dune.mkv → Path.of("C:/Users/Ivan/Desktop")
//   Для отдельной медиатеки удобнее: Path.of("C:/Users/Ivan/Desktop/Movies").
//   Прямые слеши работают на Windows и не требуют писать двойное "\\"
// macOS: для /Users/ivan/Desktop/Dune.mkv → Path.of("/Users/ivan/Desktop")
//   Для отдельной медиатеки: Path.of("/Users/ivan/Desktop/Movies").
// Относительный Path.of("movies") означает папку movies в корне проекта.
private val moviesDirectory: Path = Path.of("movies")

private val workMode: WorkMode = WorkMode.DEBUG

// WorkMode.PREVIEW — библиотека movies: только читаем
//
// WorkMode.RENAME  — библиотека movies: переименовать на месте
// WorkMode.COPY    — библиотека movies: копия с новым именем//                         свой кеш: debug/debug-revert-cache.json; если results пуста — только лог,
//                         DEBUG сам не запускаем
// WorkMode.REVERT  — вернуть имена из кэша последнего переименования библиотеки
//
// WorkMode.DEBUG   — только debug/samples и debug/results, movies не трогаем;
//                    онлайн-поиск и токены TMDB/ПоискКино работают так же, как в остальных режимах
// WorkMode.DEBUG_REVERT — читаем debug/results, пишем исходные имена в debug/reverted;

private val lookupOnline: Boolean = true

fun main() {
    Talk.install()
    installCatalogTokens()
    MovieRenamer.run(
        LaunchSettings(
            moviesDirectory = moviesDirectory,
            mode = workMode,
            lookupOnline = lookupOnline || workMode == WorkMode.DEBUG,
        ),
    )
}

private fun installCatalogTokens() {
    val file = findCatalogTokensFile()
    if (file == null) {
        Talk.info("Файл токенов не найден: catalog-tokens.properties")
        return
    }
    val properties = Properties()
    Files.newBufferedReader(file, StandardCharsets.UTF_8).use(properties::load)
    installToken(properties, "TMDB_API_TOKEN", "tmdb.api.token")
    installToken(properties, "POISKKINO_API_TOKEN", "poiskkino.api.token")
    val loaded = buildList {
        if (TitleCatalog.isTmdbConfigured()) add("TMDB")
        if (TitleCatalog.isPoiskKinoConfigured()) add("ПоискКино")
    }
    if (loaded.isEmpty()) {
        Talk.info("Токены не заданы в $file — TMDB и ПоискКино выключены")
    } else {
        Talk.info("Токены загружены из $file: ${loaded.joinToString(", ")}")
    }
}

private fun findCatalogTokensFile(): Path? {
    val relative =
        Path.of("start", "src", "main", "kotlin", "movierenamer", "catalog-tokens.properties")
    val nextToMain = Path.of("src", "main", "kotlin", "movierenamer", "catalog-tokens.properties")
    val shortName = Path.of("catalog-tokens.properties")
    val roots = buildList {
        add(Path.of("").toAbsolutePath().normalize())
        System.getProperty("user.dir")?.let { add(Path.of(it).toAbsolutePath().normalize()) }
    }.distinct()
    val candidates = mutableListOf<Path>()
    for (root in roots) {
        var current: Path? = root
        var depth = 0
        while (current != null && depth < 6) {
            candidates.add(current.resolve(relative))
            candidates.add(current.resolve(nextToMain))
            candidates.add(current.resolve(shortName))
            current = current.parent
            depth++
        }
    }
    return candidates.map { it.normalize() }.distinct().firstOrNull { Files.isRegularFile(it) }
}

private fun installToken(properties: Properties, propertyName: String, systemProperty: String) {
    properties.getProperty(propertyName)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "плейсхолдер" }
        ?.let { System.setProperty(systemProperty, it) }
}
