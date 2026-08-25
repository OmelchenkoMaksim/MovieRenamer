package movierenamer.scanner

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoScannerTest {

    @Test
    fun `finds video files with cyrillic names`() {
        val directory = Files.createTempDirectory("фильмы-")
        val video = directory.resolve("Матрица.1999.mkv")
        Files.createFile(video)
        try {
            val found = VideoScanner.findVideoFiles(directory)
            assertEquals("Матрица.1999.mkv", found.single().fileName.toString())
        } finally {
            Files.deleteIfExists(video)
            Files.deleteIfExists(directory)
        }
    }
}
