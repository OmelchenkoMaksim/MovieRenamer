package movierenamer

import java.nio.file.Path

object Config {
    val moviesDirectory: Path = Path.of("movies")

    val videoExtensions: Set<String> = setOf(
        "mkv",
        "mp4",
        "avi",
        "m4v",
        "mov",
        "webm",
        "ts",
        "m2ts",
    )
}
