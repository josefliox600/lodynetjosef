package com.lodynetjosef

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class LodyNetJosef : MainAPI() {
    override var mainUrl = "https://lodynet.watch"
    override var name = "LodyNet Josef"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%87%d9%86%d8%af%d9%8a%d8%a9/" to "أفلام هندية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%87%d9%86%d8%af%d9%8a%d9%87/" to "مسلسلات هندية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a/" to "مسلسلات تركية",
        "$mainUrl/korean-series-b/" to "مسلسلات كورية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b5%d9%8a%d9%86%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/" to "مسلسلات صينية",
        "$mainUrl/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%a7%d9%8a%d9%84%d9%86%d8%af%d9%8a%d8%a9/" to "مسلسلات تايلندية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9-a/" to "أفلام أجنبية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9-a/" to "أفلام آسيوية",
        "$mainUrl/category/%d8%a7%d9%86%d9%8a%d9%85%d9%8a/" to "أنيمي"
    )

    private fun detectType(title: String, href: String): TvType {
        return when {
            href.contains("episode", true) || title.contains("الحلقة") -> TvType.TvSeries
            href.contains("anime", true) || title.contains("انيمي") || title.contains("أنيمي") -> TvType.Anime
            title.contains("مسلسل") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").trim().ifBlank { return null }

        val title = selectFirst(".NewlyTitle, .SuggestionsTitle, h2, h3")
            ?.text()?.trim()
            ?.ifBlank { null }
            ?: anchor.attr("title").trim().ifBlank { null }
            ?: return null

        val poster = selectFirst(".NewlyCover, .SuggestionsCover, [data-src], img")
            ?.let { el -> el.attr("data-src").ifBlank { el.attr("src") } }
            ?.trim()
            ?.ifBlank { null }

        val type = detectType(title, href)

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val doc = app.get(pageUrl).document

        val items = doc.select(".ItemNewly, .SuggestionsItems, article, .post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document

        return doc.select(".ItemNewly, .SuggestionsItems, article, .post, .Post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url)
        val doc = resp.document
        val pageText = resp.text

        val title = doc.selectFirst("#PrimaryTitle")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: return null

        val poster = doc.selectFirst("#CoverSingle")?.attr("data-src")?.trim()?.ifBlank { null }
            ?: Regex("""<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""")
                .find(pageText)?.groupValues?.get(1)

        val description = doc.selectFirst("#ContentDetails")?.text()?.trim()
            ?: doc.selectFirst("""meta[name="description"]""")?.attr("content")?.trim()

        val year = doc.selectFirst("#DateDetails")
            ?.attr("content")
            ?.takeIf { it.length >= 4 }
            ?.substring(0, 4)
            ?.toIntOrNull()

        val tags = doc.select("a.GenresDetails").map { it.text().trim() }.filter { it.isNotBlank() }

        val recommendations = doc.select(".ItemNewly")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        val episodeButtons = doc.select("#ListEpisodes a, .EpisodesList a, .EpisodeItem a")
        if (episodeButtons.isNotEmpty()) {
            val episodes = episodeButtons.mapNotNull { ep ->
                val epUrl = ep.attr("href").trim().ifBlank { return@mapNotNull null }
                val epTitle = ep.text().trim().ifBlank { "حلقة" }
                val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    name = epTitle
                    episode = epNum
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val responsePage = app.get(data)
        val doc = responsePage.document
        val pageText = responsePage.text

        var found = false

        val firstEmbed = Regex("""["']embedUrl["']\s*:\s*["']([^"']+)["']""")
            .find(pageText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()

        if (!firstEmbed.isNullOrBlank() && firstEmbed.startsWith("http")) {
            try {
                loadExtractor(firstEmbed, data, subtitleCallback, callback)
                found = true
            } catch (_: Exception) {
            }
        }

        val postId = Regex("""PostData\s*=\s*\{[\s\S]*?ID:\s*(\d+)""")
            .find(pageText)
            ?.groupValues?.get(1)
            ?: Regex("""SwitchServer\(this,\s*\d+,\s*(\d+)\)""")
                .find(pageText)
                ?.groupValues?.get(1)

        if (postId == null) return found

        val buttons = doc.select("#AllServerWatch button")
        buttons.forEach { button ->
            val onclick = button.attr("onclick")
            val match = Regex("""SwitchServer\(this,\s*(\d+),\s*(\d+)\)""").find(onclick)
                ?: return@forEach

            val serverId = match.groupValues[1]

            try {
                val embedResponse = app.post(
                    "$mainUrl/wp-content/themes/Lodynet2020/Api/RequestServerEmbed.php",
                    headers = mapOf(
                        "Referer" to data,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf(
                        "PostID" to postId,
                        "ServerID" to serverId
                    )
                ).text.trim()

                if (embedResponse.isNotBlank() && embedResponse.startsWith("http")) {
                    try {
                        loadExtractor(embedResponse, data, subtitleCallback, callback)
                        found = true
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}
