package movierenamer

import java.nio.file.Path

// 0000 Настройки старта. Меняешь здесь — запускаешь.

private val moviesDirectory: Path = Path.of("movies")

private val workMode: WorkMode = WorkMode.DEBUG
// WorkMode.PREVIEW — библиотека: только читаем, что можно / что нет
// WorkMode.RENAME  — библиотека: переименовать на месте, если имя поняли
// WorkMode.COPY    — библиотека: оригинал оставить, копия с новым именем
// WorkMode.DEBUG   — debug/samples только читаем, результат пишем в debug/results

private val lookupOnline: Boolean = true
// true  — уточняем название в iTunes, TVMaze и Wikipedia
// false — только имя файла, без интернета

private val debugSamples: Path = Path.of("debug", "samples")
private val debugResults: Path = Path.of("debug", "results")

fun main() {
    val debug = workMode == WorkMode.DEBUG
    MovieRenamer.run(
        LaunchSettings(
            moviesDirectory = if (debug) debugSamples else moviesDirectory,
            mode = workMode,
            lookupOnline = lookupOnline,
            resultsDirectory = if (debug) debugResults else null,
        ),
    )
}
