package com.lodynetjosef

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

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

    private fun absUrl(u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> mainUrl + u
        else -> "$mainUrl/$u"
    }

    // مثل runCatching لكن لا يبلع CancellationException
    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // النوع يُحدد من القسم الذي جاء منه العنصر (أدق من تخمين العنوان)
    private fun typeFromSection(sectionName: String): TvType? = when {
        sectionName.contains("أنيمي") -> TvType.Anime
        sectionName.contains("كورية") ||
            sectionName.contains("صينية") ||
            sectionName.contains("تايلندية") -> TvType.AsianDrama
        sectionName.contains("مسلسلات") -> TvType.TvSeries
        sectionName.contains("أفلام") -> TvType.Movie
        else -> null
    }

    private fun detectType(title: String, href: String): TvType {
        return when {
            href.contains("episode", true) || title.contains("الحلقة") -> TvType.TvSeries
            href.contains("anime", true) || title.contains("انيمي") || title.contains("أنيمي") -> TvType.Anime
            title.contains("مسلسل") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun Element.toSearchResult(forcedType: TvType? = null): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = absUrl(anchor.attr("href").trim().ifBlank { return null })

        val title = selectFirst(".NewlyTitle, .SuggestionsTitle, h2, h3")
            ?.text()?.trim()
            ?.ifBlank { null }
            ?: anchor.attr("title").trim().ifBlank { null }
            ?: return null

        val poster = selectFirst(".NewlyCover, .SuggestionsCover, [data-src], img")
            ?.let { el -> el.attr("data-src").ifBlank { el.attr("src") } }
            ?.trim()
            ?.ifBlank { null }
            ?.let { absUrl(it) }

        val type = forcedType ?: detectType(title, href)

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val doc = app.get(pageUrl).document

        val items = doc.select(".ItemNewly, .SuggestionsItems, article, .post")
            .mapNotNull { it.toSearchResult(typeFromSection(request.name)) }
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

        val title = doc.selectFirst("#PrimaryTitle")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: return null

        val poster = (doc.selectFirst("#CoverSingle")?.attr("data-src")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        )?.let { absUrl(it) }

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
                val epUrl = absUrl(ep.attr("href").trim().ifBlank { return@mapNotNull null })
                val epTitle = ep.text().trim().ifBlank { "حلقة" }
                val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(
                    epUrl,
                    initializer = {
                        this.name = epTitle
                        this.episode = epNum
                    }
                )
            }

            val seriesType = detectType(title, url).let { if (it == TvType.Movie) TvType.TvSeries else it }
            return newTvSeriesLoadResponse(title, url, seriesType, episodes) {
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

        // نعتبر النجاح فقط إذا وصل رابط فعلي للمشغّل
        val found = AtomicBoolean(false)
        val trackingCallback: (ExtractorLink) -> Unit = {
            found.set(true)
            callback(it)
        }

        // لتجنب تكرار نفس السيرفر
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        suspend fun tryEmbed(url: String) {
            if (!url.startsWith("http") || !seen.add(url)) return
            safe { loadExtractor(url, data, subtitleCallback, trackingCallback) }
        }

        Regex("""["']embedUrl["']\s*:\s*["']([^"']+)["']""")
            .find(pageText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
            ?.let { tryEmbed(it) }

        val postId = Regex("""PostData\s*=\s*\{[\s\S]*?ID:\s*(\d+)""")
            .find(pageText)
            ?.groupValues?.get(1)
            ?: Regex("""SwitchServer\(this,\s*\d+,\s*(\d+)\)""")
                .find(pageText)
                ?.groupValues?.get(1)

        if (postId == null) return found.get()

        // نحاول قراءة مسار الـ API من الصفحة نفسها، وإلا نستعمل المسار المعروف
        val embedApi = Regex("""["']([^"']*RequestServerEmbed\.php)["']""")
            .find(pageText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.let { absUrl(it) }
            ?: "$mainUrl/wp-content/themes/Lodynet2020/Api/RequestServerEmbed.php"

        val serverIds = doc.select("#AllServerWatch button").mapNotNull { button ->
            Regex("""SwitchServer\(this,\s*(\d+),\s*(\d+)\)""")
                .find(button.attr("onclick"))
                ?.groupValues?.get(1)
        }.distinct()

        // طلب السيرفرات بالتوازي بدل الانتظار واحداً تلو الآخر
        serverIds.amap { serverId ->
            val embed = safe {
                app.post(
                    embedApi,
                    headers = mapOf(
                        "Referer" to data,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf(
                        "PostID" to postId,
                        "ServerID" to serverId
                    )
                ).text.trim()
            }
            if (!embed.isNullOrBlank()) tryEmbed(embed)
        }

        return found.get()
    }
}
