package movierenamer

import java.nio.file.Path

// 0000 Настройки старта. DEBUG всегда ходит только в debug/samples → debug/results.

private val moviesDirectory: Path = Path.of("movies")

private val workMode: WorkMode = WorkMode.DEBUG
// WorkMode.PREVIEW — библиотека movies: только читаем
// WorkMode.RENAME  — библиотека movies: переименовать на месте
// WorkMode.COPY    — библиотека movies: копия с новым именем
// WorkMode.DEBUG   — только debug/samples и debug/results, movies не трогаем

private val lookupOnline: Boolean = true

fun main() {
    MovieRenamer.run(
        LaunchSettings(
            moviesDirectory = moviesDirectory,
            mode = workMode,
            lookupOnline = lookupOnline,
        ),
    )
}
