package movierenamer.scanner

import movierenamer.Config
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

object VideoScanner {
    fun findVideoFiles(directory: Path): List<Path> {
        return Files.walk(directory).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() }
                .filter { it.extension.lowercase() in Config.videoExtensions }
                .sortedBy { it.toString().lowercase() }
                .toList()
        }
    }
}
