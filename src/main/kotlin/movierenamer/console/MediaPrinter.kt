package movierenamer.console

import movierenamer.model.MediaInfo
import movierenamer.model.MediaType
import java.nio.file.Path

object MediaPrinter {
    fun print(index: Int, file: Path, media: MediaInfo) {
        println("==================================================")
        println("${index + 1}. ${file.fileName}")
        println("Полный путь: $file")
        println("Тип: ${media.mediaType.displayName}")
        println("Название: ${media.title}")
        println("Год: ${media.year ?: "не найден"}")

        if (media.mediaType == MediaType.TV_EPISODE) {
            println("Сезон: ${media.season ?: "не найден"}")
            println("Эпизод: ${media.episode ?: "не найден"}")
            println("Название эпизода: ${media.episodeTitle ?: "не найдено"}")
        }

        println("Разрешение: ${media.resolution ?: "не найдено"}")
        println("Источник: ${media.source ?: "не найден"}")
        println("Версия: ${media.editions.ifEmpty { listOf("не найдена") }.joinToString()}")
        println("Языки: ${media.languages.ifEmpty { listOf("не найдены") }.joinToString()}")
    }
}
