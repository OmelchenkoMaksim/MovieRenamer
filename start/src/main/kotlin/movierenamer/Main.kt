package movierenamer

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

// Локальный токен лежит рядом с Main.kt и никогда не попадает в Git.
// Скопируйте tmdb-token.example.properties в tmdb-token.local.properties
// и замените моковое значение на TMDB API Read Access Token.
private val tmdbTokenFile: Path = Path.of(
    "start",
    "src",
    "main",
    "kotlin",
    "movierenamer",
    "tmdb-token.local.properties",
)

fun main() {
    installLocalTmdbToken()
    MovieRenamer.run(
        LaunchSettings(
            moviesDirectory = moviesDirectory,
            mode = workMode,
            lookupOnline = lookupOnline,
        ),
    )
}

private fun installLocalTmdbToken() {
    if (!Files.isRegularFile(tmdbTokenFile)) return
    val properties = Properties()
    Files.newInputStream(tmdbTokenFile).use(properties::load)
    properties.getProperty("TMDB_API_TOKEN")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "PASTE_TMDB_READ_ACCESS_TOKEN_HERE" }
        ?.let { System.setProperty("tmdb.api.token", it) }
}
