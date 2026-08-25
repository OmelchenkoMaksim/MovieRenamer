package movierenamer

import java.nio.file.Path

// 0000 Настройки старта. Меняешь здесь — запускаешь. Аргументы CLI не нужны.

private val moviesDirectory: Path = Path.of("movies")

private val workMode: WorkMode = WorkMode.PREVIEW
// WorkMode.PREVIEW — только читаем: что переименуется, а что нет
// WorkMode.RENAME  — переименовать на месте, если имя поняли
// WorkMode.COPY    — оригинал оставить, копия с новым именем, если поняли
// WorkMode.DEBUG   — как PREVIEW, плюс сырой разбор каждого файла

private val lookupOnline: Boolean = true
// true  — уточняем название в iTunes, TVMaze и Wikipedia
// false — только имя файла, без интернета

fun main() {
    MovieRenamer.run(
        LaunchSettings(
            moviesDirectory = moviesDirectory,
            mode = workMode,
            lookupOnline = lookupOnline,
        ),
    )
}
