package movierenamer.parser

import movierenamer.model.MediaType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaParserTest {

    @Test
    fun `parses a movie release name`() {
        val media = MediaParser.parse(
            Path.of("The.Matrix.1999.1080p.BluRay.x264.RUS.ENG.mkv"),
        )

        assertEquals(MediaType.MOVIE, media.mediaType)
        assertEquals("The Matrix", media.title)
        assertEquals(1999, media.year)
        assertEquals("1080p", media.resolution)
        assertEquals("BluRay", media.source)
        assertEquals(listOf("RU", "EN"), media.languages)
        assertNull(media.season)
        assertNull(media.episode)
    }

    @Test
    fun `parses a tv episode and takes the series title from the parent folder`() {
        val media = MediaParser.parse(
            Path.of("Breaking Bad", "Breaking.Bad.S02E03.Bit.by.a.Dead.Bee.720p.WEB-DL.mkv"),
        )

        assertEquals(MediaType.TV_EPISODE, media.mediaType)
        assertEquals("Breaking Bad", media.title)
        assertEquals(2, media.season)
        assertEquals(3, media.episode)
        assertEquals("Bit by a Dead Bee", media.episodeTitle)
        assertEquals("720p", media.resolution)
        assertEquals("WEB-DL", media.source)
    }

    @Test
    fun `strips a leading resolution tag and detects edition`() {
        val media = MediaParser.parse(
            Path.of("[1080p] Dune 2021 Directors Cut Open Matte Remastered.mkv"),
        )

        assertEquals("Dune", media.title)
        assertEquals(2021, media.year)
        assertEquals("1080p", media.resolution)
        assertEquals(listOf("Director's Cut", "Open Matte", "Remastered"), media.editions)
    }
}
