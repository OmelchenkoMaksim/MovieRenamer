package movierenamer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

// 0000 Настройки старта. DEBUG всегда ходит только в debug/samples → debug/results.

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
// WorkMode.RENAME  — библиотека movies: переименовать на месте
// WorkMode.COPY    — библиотека movies: копия с новым именем
// WorkMode.DEBUG   — только debug/samples и debug/results, movies не трогаем

private val lookupOnline: Boolean = true

// Оба токена лежат в одном файле рядом с Main.kt.
private val catalogTokensFile: Path = Path.of(
    "start",
    "src",
    "main",
    "kotlin",
    "movierenamer",
    "catalog-tokens.properties",
)

fun main() {
    installCatalogTokens()
    MovieRenamer.run(
        LaunchSettings(
            moviesDirectory = moviesDirectory,
            mode = workMode,
            lookupOnline = lookupOnline,
        ),
    )
}

private fun installCatalogTokens() {
    if (!Files.isRegularFile(catalogTokensFile)) return
    val properties = Properties()
    Files.newBufferedReader(catalogTokensFile, StandardCharsets.UTF_8).use(properties::load)
    installToken(properties, "TMDB_API_TOKEN", "tmdb.api.token")
    installToken(properties, "POISKKINO_API_TOKEN", "poiskkino.api.token")
}

private fun installToken(properties: Properties, propertyName: String, systemProperty: String) {
    properties.getProperty(propertyName)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "плейсхолдер" }
        ?.let { System.setProperty(systemProperty, it) }
}
