package movierenamer.model

data class MediaInfo(
    val mediaType: MediaType,
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val resolution: String?,
    val source: String?,
    val editions: List<String>,
    val languages: List<String>,
)
