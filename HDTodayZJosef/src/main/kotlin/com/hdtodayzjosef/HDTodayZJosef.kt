package com.hdtodayzjosef

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to mainUrl
    )

    private fun Element.toSearchResultSafe(): SearchResponse? {
        val anchor = selectFirst("a.film-poster-ahref, .film-name a, h3.film-name a, a[href*=\"/movie/\"], a[href*=\"/tv/\"]")
            ?: return null

        val title = (
            selectFirst("h3.film-name a, .film-detail .film-name a, .film-name a")
                ?.text()
                ?.trim()
                ?: anchor.attr("title").trim()
            ).ifBlank { return null }

        val href = fixUrl(anchor.attr("href"))
        if (!href.contains("/movie/") && !href.contains("/tv/")) return null

        val posterEl = selectFirst("img.film-poster-img, img")
        val poster = posterEl?.attr("data-src")?.ifBlank {
            posterEl.attr("data-lazy-src")
        }?.ifBlank {
            posterEl.attr("src")
        }?.let { fixUrl(it) }

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
        val doc = app.get(url, referer = mainUrl).document

        val results = doc.select(
            ".film_list-wrap .flw-item, " +
            ".film_list .flw-item, " +
            ".block_area-content .flw-item, " +
            ".film_list-grid .flw-item"
        ).mapNotNull { it.toSearchResultSafe() }

        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val urls = listOf(
            "$mainUrl/search/$q",
            "$mainUrl/filter?keyword=$q"
        )

        val all = mutableListOf<SearchResponse>()
        urls.forEach { url ->
            runCatching {
                val doc = app.get(url, referer = mainUrl).document
                all += doc.select(
                    ".film_list-wrap .flw-item, " +
                    ".film_list .flw-item, " +
                    ".block_area-content .flw-item, " +
                    ".film_list-grid .flw-item"
                ).mapNotNull { it.toSearchResultSafe() }
            }
        }

        return all.distinctBy { it.url }
    }

    private fun parseYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text)
            ?.value
            ?.toIntOrNull()
    }

    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun getRowValue(doc: Document, label: String): String? {
        return doc.select(".elements .row-line")
            .firstOrNull { it.text().contains(label, ignoreCase = true) }
            ?.text()
            ?.substringAfter(label, "")
            ?.trim()
            ?.removePrefix(":")
            ?.trim()
    }

    private fun getTags(doc: Document, label: String): List<String> {
        return doc.select(".elements .row-line")
            .firstOrNull { it.text().contains(label, ignoreCase = true) }
            ?.select("a")
            ?.mapNotNull { it.text()?.trim()?.takeIf(String::isNotBlank) }
            ?: emptyList()
    }

    private fun extractMediaId(url: String): Int? {
        return Regex("""-(\d+)$""")
            .find(url.substringBefore("?").substringBeforeLast("."))
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractWatchIdFromUrl(url: String): String? {
        return Regex("""\.(\d+)(?:\?.*)?$""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
    }

    private suspend fun getWatchPageUrl(detailUrl: String, isTv: Boolean): String? {
        val doc = app.get(detailUrl, referer = mainUrl).document

        val direct = doc.select("a[href*=\"/watch-movie/\"], a[href*=\"/watch-tv/\"]")
            .mapNotNull { it.attr("href") }
            .firstOrNull { it.contains("/watch-") }

        if (!direct.isNullOrBlank()) return fixUrl(direct)

        val mediaId = extractMediaId(detailUrl) ?: return null
        return if (isTv) {
            "$mainUrl/watch-tv/${detailUrl.substringAfterLast("/tv/")}.$mediaId"
        } else {
            "$mainUrl/watch-movie/${detailUrl.substringAfterLast("/movie/")}.$mediaId"
        }
    }

    private suspend fun loadEpisodesFromAjax(mediaId: Int, refererUrl: String): List<Episode> {
        val html = app.get(
            "$mainUrl/ajax/episode/list/$mediaId",
            referer = refererUrl,
            headers = ajaxHeaders
        ).text

        val doc = Jsoup.parse(html)
        val items = doc.select("a[href], .ep-item, .ssl-item, li a, .nav-item a")

        val episodes = items.mapNotNull { el ->
            val dataId = el.attr("data-id").trim()
            val href = el.attr("href").trim()
            val epNum = el.attr("data-number").trim().toIntOrNull()
                ?: Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(el.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val seasonNum = el.attr("data-season").trim().toIntOrNull()
                ?: Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(el.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val watchData = when {
                dataId.isNotBlank() -> "epid:$dataId"
                href.isNotBlank() && href.contains(".") -> "watch:${fixUrl(href)}"
                else -> null
            } ?: return@mapNotNull null

            newEpisode(watchData) {
                this.name = el.text().trim().ifBlank {
                    buildString {
                        append("Episode")
                        if (epNum != null) append(" $epNum")
                    }
                }
                this.episode = epNum
                this.season = seasonNum
            }
        }

        return episodes.distinctBy { it.data }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = doc.selectFirst(
            ".dp-i-c-poster img, .film-poster img, img.film-poster-img, .detail_page-infor img"
        )?.let {
            it.attr("data-src").ifBlank {
                it.attr("data-lazy-src")
            }.ifBlank {
                it.attr("src")
            }
        }?.takeIf { it.isNotBlank() }?.let(::fixUrl)

        val description = doc.selectFirst(".description, .film-description, .dp-i-c-description")
            ?.text()
            ?.trim()

        val year = parseYear(getRowValue(doc, "Released"))
        val duration = parseDurationMinutes(getRowValue(doc, "Duration"))
        val genres = getTags(doc, "Genre")
        val actors = getTags(doc, "Casts")

        val isTv = url.contains("/tv/")
        val watchPage = getWatchPageUrl(url, isTv)
        val mediaId = extractMediaId(url)

        return if (isTv) {
            val episodes = if (mediaId != null && watchPage != null) {
                loadEpisodesFromAjax(mediaId, watchPage).ifEmpty {
                    listOfNotNull(
                        watchPage?.let {
                            newEpisode("watch:$it") {
                                name = "Episode 1"
                                episode = 1
                            }
                        }
                    )
                }
            } else {
                listOfNotNull(
                    watchPage?.let {
                        newEpisode("watch:$it") {
                            name = "Episode 1"
                            episode = 1
                        }
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.actors = actors.map { Actor(it) }
            }
        } else {
            val watchDoc = watchPage?.let { app.get(it, referer = url).document }
            val activeServerId = watchDoc
                ?.selectFirst(".detail_page-watch")?.attr("data-watch_id")
                ?.ifBlank { null }
                ?: watchDoc?.selectFirst(".detail_page-servers .link-item.active")?.attr("data-id")?.ifBlank { null }
                ?: watchDoc?.selectFirst(".detail_page-servers .link-item")?.attr("data-id")?.ifBlank { null }

            val movieData = when {
                !activeServerId.isNullOrBlank() -> "epid:$activeServerId"
                !watchPage.isNullOrBlank() -> "watch:$watchPage"
                else -> "watch:$url"
            }

            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.duration = duration
                this.tags = genres
                this.actors = actors.map { Actor(it) }
            }
        }
    }

    private fun extractIframeFromAjaxResponse(response: String): String? {
        val patterns = listOf(
            Regex("""["']link["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']file["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']src["']\s*:\s*["']([^"']+)["']"""),
            Regex("""https?:\\/\\/[^"'\\]+""")
        )

        patterns.forEach { regex ->
            val match = regex.find(response)?.groupValues?.getOrNull(1) ?: regex.find(response)?.value
            if (!match.isNullOrBlank()) {
                return match.replace("\\/", "/")
            }
        }
        return null
    }

    private fun extractDirectVideoLinks(html: String): List<String> {
        val found = linkedSetOf<String>()

        val patterns = listOf(
            Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.(m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']src["']\s*:\s*["'](https?://[^"']+\.(m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^"'\\\s]+\.(m3u8|mp4)(\?[^"'\\\s]*)?)""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { regex ->
            regex.findAll(html).forEach { m ->
                m.groupValues.getOrNull(1)?.let { link ->
                    if (link.isNotBlank()) found += link.replace("\\/", "/")
                }
            }
        }

        return found.toList()
    }

    private fun extractSubtitles(html: String): List<SubtitleFile> {
        val subtitles = linkedSetOf<SubtitleFile>()

        val trackRegex = Regex(
            """["']file["']\s*:\s*["'](https?://[^"']+\.(vtt|srt)[^"']*)["'][^}]*["']label["']\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        trackRegex.findAll(html).forEach { m ->
            val url = m.groupValues.getOrNull(1)?.replace("\\/", "/") ?: return@forEach
            val label = m.groupValues.getOrNull(3)?.ifBlank { "Unknown" } ?: "Unknown"
            subtitles += SubtitleFile(label, url)
        }

        return subtitles.toList()
    }

    private suspend fun resolveWatchToEpisodeId(watchUrl: String): String? {
        extractWatchIdFromUrl(watchUrl)?.let { return it }

        val doc = app.get(watchUrl, referer = mainUrl).document
        return doc.selectFirst(".detail_page-watch")?.attr("data-watch_id")?.ifBlank { null }
            ?: doc.selectFirst(".detail_page-servers .link-item.active")?.attr("data-id")?.ifBlank { null }
            ?: doc.selectFirst(".detail_page-servers .link-item")?.attr("data-id")?.ifBlank { null }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = when {
            data.startsWith("epid:") -> data.removePrefix("epid:").trim()
            data.startsWith("watch:") -> resolveWatchToEpisodeId(data.removePrefix("watch:").trim())
            data.contains("/watch-") -> resolveWatchToEpisodeId(data)
            else -> extractWatchIdFromUrl(data)
        } ?: return false

        val ajaxUrl = "$mainUrl/ajax/episode/sources/$episodeId"
        val ajaxResponse = app.get(
            ajaxUrl,
            referer = data.removePrefix("watch:"),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to mainUrl
            )
        ).text

        val iframeLink = extractIframeFromAjaxResponse(ajaxResponse)?.let(::fixUrl) ?: return false

        val embedHtml = runCatching {
            app.get(
                iframeLink,
                referer = data.removePrefix("watch:"),
                headers = mapOf("Referer" to mainUrl)
            ).text
        }.getOrNull()

        if (!embedHtml.isNullOrBlank()) {
            extractSubtitles(embedHtml).forEach(subtitleCallback)

            val directLinks = extractDirectVideoLinks(embedHtml)
            if (directLinks.isNotEmpty()) {
                directLinks.distinct().forEachIndexed { index, videoUrl ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name ${index + 1}",
                            url = videoUrl
                        ) {
                            referer = iframeLink
                            quality = Qualities.Unknown.value
                            isM3u8 = videoUrl.contains(".m3u8", true)
                        }
                    )
                }
                return true
            }
        }

        loadExtractor(iframeLink, data.removePrefix("watch:"), subtitleCallback, callback)
        return true
    }
}
