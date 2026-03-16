package com.hdtodayzjosef

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HDTodayZJosef : MainAPI() {
    override var mainUrl = "https://hdtodayz.to"
    override var name = "HDTodayZJosef"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val mapper = jacksonObjectMapper()

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/movie" to "Movies",
        "$mainUrl/tv-show" to "TV Shows"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h3.film-name a, .film-detail .film-name a, .film-name a")
            ?.text()
            ?.trim()
            ?: return null

        val href = fixUrl(
            this.selectFirst("a[href*=\"/movie/\"], a[href*=\"/tv/\"]")
                ?.attr("href")
                ?: return null
        )

        val poster = this.selectFirst("img.film-poster-img, img")
            ?.attr("data-src")
            ?.ifBlank {
                this.selectFirst("img.film-poster-img, img")?.attr("src")
            }

        val isTv = href.contains("/tv/")
        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document

        val items = doc.select(
            ".film_list-wrap .flw-item, .film_list .flw-item, .block_area-content .flw-item"
        ).mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search/$query").document

        return doc.select(
            ".film_list-wrap .flw-item, .film_list .flw-item, .block_area-content .flw-item"
        ).mapNotNull { it.toSearchResponse() }
    }

    private fun parseYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
    }

    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".dp-i-c-poster img")?.attr("src")
        val description = doc.selectFirst(".description")?.text()?.trim()

        val rows = doc.select(".elements .row-line")

        val releasedText = rows.find { it.text().contains("Released:", true) }?.text()
        val durationText = rows.find { it.text().contains("Duration:", true) }?.text()
        val genreRow = rows.find { it.text().contains("Genre:", true) }
        val year = parseYear(releasedText)
        val duration = parseDurationMinutes(durationText)

        val tags = genreRow?.select("a")?.map { it.text().trim() } ?: emptyList()

        val serverIds = doc.select(".detail_page-servers .link-item")
            .mapNotNull { it.attr("data-id").takeIf { id -> id.isNotBlank() } }
            .distinct()

        if (serverIds.isEmpty()) return null

        val serversJson = mapper.writeValueAsString(serverIds)

        val isTv = url.contains("/tv/") || url.contains("/watch-tv/")

        return if (isTv) {
            val episodes = mutableListOf<Episode>()

            episodes.add(
                newEpisode(serversJson) {
                    name = "Episode 1"
                    episode = 1
                }
            )

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, serversJson) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.duration = duration
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serverIds = try {
            mapper.readValue<List<String>>(data)
        } catch (_: Exception) {
            emptyList()
        }

        if (serverIds.isEmpty()) return false

        var found = false

        serverIds.forEach { serverId ->
            try {
                val response = app.get(
                    "$mainUrl/ajax/episode/sources/$serverId",
                    referer = mainUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text

                val iframeLink = Regex("""["']link["']\s*:\s*["']([^"']+)["']""")
                    .find(response)
                    ?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.trim()

                if (!iframeLink.isNullOrBlank()) {
                    loadExtractor(iframeLink, mainUrl, subtitleCallback, callback)
                    found = true
                } else {
                    val iframeFromHtml = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                        .find(response)
                        ?.groupValues?.get(1)
                        ?.replace("\\/", "/")
                        ?.trim()

                    if (!iframeFromHtml.isNullOrBlank()) {
                        loadExtractor(fixUrl(iframeFromHtml), mainUrl, subtitleCallback, callback)
                        found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}
