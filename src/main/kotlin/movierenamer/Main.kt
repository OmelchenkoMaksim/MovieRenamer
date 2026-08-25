package movierenamer

import movierenamer.console.Console
import movierenamer.console.MediaPrinter
import movierenamer.parser.MediaParser
import movierenamer.scanner.VideoScanner
import java.nio.file.Path
import kotlin.io.path.isDirectory

fun main(args: Array<String>) {
    Console.useUtf8()

    val moviesDirectory = resolveMoviesDirectory(args) ?: return

    Console.info("Запуск Movie Renamer")
    Console.info("Рабочая директория: $moviesDirectory")

    if (!moviesDirectory.isDirectory()) {
        Console.error("Директория не найдена: $moviesDirectory")
        return
    }

    val videoFiles = try {
        VideoScanner.findVideoFiles(moviesDirectory)
    } catch (exception: Exception) {
        Console.error("Ошибка сканирования: ${exception.message}")
        return
    }

    if (videoFiles.isEmpty()) {
        Console.info("В директории нет поддерживаемых видеофайлов")
        return
    }

    Console.info("Найдено файлов: ${videoFiles.size}")
    println()

    videoFiles.forEachIndexed { index, file ->
        MediaPrinter.print(
            index = index,
            file = file,
            media = MediaParser.parse(file),
        )
    }

    println()
    Console.info("Анализ завершён")
    Console.info("Файлы не изменялись")
}

private fun resolveMoviesDirectory(args: Array<String>): Path? {
    if (args.size > 1) {
        Console.error("Использование: MovieRenamer [директория]")
        return null
    }

    val rawPath = args.firstOrNull() ?: Config.moviesDirectory.toString()
    return Path.of(rawPath).toAbsolutePath().normalize()
}
