package com.hdtodayzjosef

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addEpisodes
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
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

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/movie" to "Movies",
        "$mainUrl/tv-show" to "TV Shows"
    )

    private data class AjaxSources(
        val type: String? = null,
        val link: String? = null,
        val sources: List<SourceItem>? = null,
        val tracks: List<TrackItem>? = null
    )

    private data class SourceItem(
        val file: String? = null,
        val label: String? = null,
        val type: String? = null
    )

    private data class TrackItem(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h3.film-name a, .film-detail .film-name a, .film-name a")
            ?.text()
            ?.trim()
            ?: return null

        val href = selectFirst("a[href*=\"/movie/\"], a[href*=\"/tv/\"]")
            ?.attr("href")
            ?.let(::fixUrl)
            ?: return null

        val posterEl = selectFirst("img.film-poster-img, img")
        val poster = posterEl?.attr("data-src").takeUnless { it.isNullOrBlank() }
            ?: posterEl?.attr("src")

        return if (href.contains("/tv/")) {
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
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url).document

        val items = doc.select(
            ".film_list-wrap .flw-item, .film_list .flw-item, .block_area-content .flw-item"
        ).mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/search/$q",
            "$mainUrl/search?keyword=$q"
        )

        for (u in urls) {
            runCatching {
                val doc = app.get(u).document
                val results = doc.select(
                    ".film_list-wrap .flw-item, .film_list .flw-item, .block_area-content .flw-item"
                ).mapNotNull { it.toSearchResponse() }

                if (results.isNotEmpty()) return results
            }
        }

        return emptyList()
    }

    private fun parseYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
    }

    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun getInfoRow(doc: org.jsoup.nodes.Document, label: String): Element? {
        return doc.select(".elements .row-line").firstOrNull {
            it.text().contains(label, ignoreCase = true)
        }
    }

    private suspend fun fetchEpisodeListHtml(mediaId: String): String? {
        val urls = listOf(
            "$mainUrl/ajax/episode/list/$mediaId",
            "$mainUrl/ajax/v2/tv/episodes/$mediaId"
        )

        for (u in urls) {
            val res = runCatching {
                app.get(
                    u,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
            }.getOrNull()

            if (!res.isNullOrBlank()) return res
        }

        return null
    }

    private fun extractMediaIdFromUrl(url: String): String? {
        return Regex("""-(\d+)$""").find(url)?.groupValues?.getOrNull(1)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .heading-name a")?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".dp-i-c-poster img, .film-poster-img")?.attr("src")
            ?.takeUnless { it.isBlank() }
        val description = doc.selectFirst(".description")?.text()?.trim()

        val releasedText = getInfoRow(doc, "Released:")?.text()
        val durationText = getInfoRow(doc, "Duration:")?.text()
        val genreRow = getInfoRow(doc, "Genre:")
        val year = parseYear(releasedText)
        val duration = parseDurationMinutes(durationText)

        val tags = genreRow?.select("a")?.map { it.text().trim() } ?: emptyList()

        val isTv = url.contains("/tv/")
        val mediaId = extractMediaIdFromUrl(url)

        if (isTv) {
            val episodes = mutableListOf<Episode>()

            if (!mediaId.isNullOrBlank()) {
                val epHtml = fetchEpisodeListHtml(mediaId)
                if (!epHtml.isNullOrBlank()) {
                    val epDoc = app.parseHtml(epHtml)

                    val seasonBlocks = epDoc.select("[data-season-id], .ss-item, .season-item")
                    if (seasonBlocks.isNotEmpty()) {
                        seasonBlocks.forEachIndexed { seasonIndex, seasonEl ->
                            val seasonNum = seasonEl.attr("data-season-number").toIntOrNull()
                                ?: seasonEl.attr("data-number").toIntOrNull()
                                ?: (seasonIndex + 1)

                            val episodeLinks = seasonEl.select(
                                "a[href*=\"/watch-tv/\"], a[data-id], .ep-item"
                            )

                            episodeLinks.forEachIndexed { epIndex, epEl ->
                                val watchHref = epEl.attr("href").takeIf { it.isNotBlank() }?.let(::fixUrl)
                                val episodeId = epEl.attr("data-id").takeIf { it.isNotBlank() }
                                val episodeNum = epEl.attr("data-number").toIntOrNull() ?: (epIndex + 1)
                                val epName = epEl.attr("title").takeIf { it.isNotBlank() }
                                    ?: epEl.text().trim().ifBlank { "Episode $episodeNum" }

                                val episodeData = when {
                                    !watchHref.isNullOrBlank() -> watchHref
                                    !episodeId.isNullOrBlank() -> "$mainUrl/watch-tv/unknown.$episodeId"
                                    else -> null
                                }

                                if (!episodeData.isNullOrBlank()) {
                                    episodes.add(
                                        Episode(
                                            data = episodeData,
                                            name = epName,
                                            season = seasonNum,
                                            episode = episodeNum
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        epDoc.select("a[href*=\"/watch-tv/\"], a[data-id], .ep-item").forEachIndexed { index, epEl ->
                            val watchHref = epEl.attr("href").takeIf { it.isNotBlank() }?.let(::fixUrl)
                            val episodeId = epEl.attr("data-id").takeIf { it.isNotBlank() }
                            val epNum = epEl.attr("data-number").toIntOrNull() ?: (index + 1)
                            val epName = epEl.attr("title").takeIf { it.isNotBlank() }
                                ?: epEl.text().trim().ifBlank { "Episode $epNum" }

                            val episodeData = when {
                                !watchHref.isNullOrBlank() -> watchHref
                                !episodeId.isNullOrBlank() -> "$mainUrl/watch-tv/unknown.$episodeId"
                                else -> null
                            }

                            if (!episodeData.isNullOrBlank()) {
                                episodes.add(
                                    Episode(
                                        data = episodeData,
                                        name = epName,
                                        season = 1,
                                        episode = epNum
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (episodes.isEmpty()) {
                val watchHref = doc.select("a[href*=\"/watch-tv/\"]")
                    .mapNotNull { it.attr("href") }
                    .firstOrNull { it.contains("/watch-tv/") }
                    ?.let(::fixUrl)

                if (!watchHref.isNullOrBlank()) {
                    episodes.add(
                        Episode(
                            data = watchHref,
                            name = "Episode 1",
                            season = 1,
                            episode = 1
                        )
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            val serverLinks = doc.select(".detail_page-servers .link-item")
            val watchLink = doc.select("a[href*=\"/watch-movie/\"]")
                .mapNotNull { it.attr("href") }
                .firstOrNull { it.contains("/watch-movie/") }
                ?.let(::fixUrl)

            val firstServerId = serverLinks.firstOrNull()?.attr("data-id")?.takeIf { it.isNotBlank() }

            val dataUrl = when {
                !watchLink.isNullOrBlank() && !firstServerId.isNullOrBlank() && !watchLink.contains(".$firstServerId") ->
                    "$watchLink.$firstServerId"

                !watchLink.isNullOrBlank() ->
                    watchLink

                !firstServerId.isNullOrBlank() ->
                    "$mainUrl/watch-movie/unknown.$firstServerId"

                else -> url
            }

            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.duration = duration
                this.tags = tags
            }
        }
    }

    private suspend fun addDirectSourceLinks(
        ajaxText: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var added = false

        val parsed = tryParseJson<AjaxSources>(ajaxText)
        parsed?.tracks?.orEmpty()?.forEach { track ->
            val sub = track.file
            if (!sub.isNullOrBlank()) {
                subtitleCallback(
                    SubtitleFile(
                        lang = track.label ?: "Unknown",
                        url = sub
                    )
                )
            }
        }

        parsed?.sources?.orEmpty()?.forEach { src ->
            val file = src.file
            if (!file.isNullOrBlank()) {
                val fixed = fixUrl(file)
                if (fixed.contains(".m3u8")) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = src.label ?: "HDTodayZ",
                            url = fixed,
                            referer = referer,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    added = true
                } else if (fixed.contains(".mp4")) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = src.label ?: "HDTodayZ",
                            url = fixed,
                            referer = referer,
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                    added = true
                }
            }
        }

        return added
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.substringAfterLast(".").substringBefore("?").trim()
        if (episodeId.isBlank()) return false

        val ajaxUrl = "$mainUrl/ajax/episode/sources/$episodeId"
        val ajaxText = app.get(
            ajaxUrl,
            referer = data,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )
        ).text

        if (ajaxText.isBlank()) return false

        var found = false

        if (addDirectSourceLinks(ajaxText, data, subtitleCallback, callback)) {
            found = true
        }

        val iframeLink = Regex("""["']link["']\s*:\s*["']([^"']+)["']""")
            .find(ajaxText)
            ?.groupValues?.getOrNull(1)
            ?.trim()
            ?.let(::fixUrl)

        if (!iframeLink.isNullOrBlank()) {
            loadExtractor(iframeLink, data, subtitleCallback) { link ->
                found = true
                callback(link)
            }

            if (!found) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Iframe",
                        url = iframeLink,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = iframeLink.contains(".m3u8")
                    )
                )
                found = true
            }
        }

        return found
    }
}
