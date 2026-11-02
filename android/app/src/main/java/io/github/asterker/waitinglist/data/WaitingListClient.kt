package io.github.asterker.waitinglist.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Reads the signed-in waiting list page. VATSIM UK has no API for this, so we send the
// cookies the sign-in WebView stored and parse the same HTML tables a browser shows.
object WaitingListClient {
    const val WAITING_LISTS_URL = "https://www.vatsim.uk/mship/waiting-lists"

    sealed interface Page {
        data class Html(val html: String, val url: String) : Page
        data object LoginRequired : Page
        data class Failed(val code: Int?, val message: String) : Page
    }

    fun fetch(context: Context): FetchResult = when (val page = fetchPage(context, WAITING_LISTS_URL, this::isWaitingListsPage)) {
        is Page.Html -> parse(page.html, page.url)
        Page.LoginRequired -> FetchResult.LoginRequired
        is Page.Failed -> FetchResult.Error(page.message)
    }

    // GETs a signed-in vatsim.uk page, following redirects by hand so cookies stay in step
    // with the sign-in WebView. Ending anywhere other than a page isTarget accepts means
    // the session has expired.
    fun fetchPage(context: Context, startUrl: String, isTarget: (URL) -> Boolean): Page {
        val cookies = CookieManager.getInstance()
        val userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrDefault("Mozilla/5.0 (Linux; Android) UKWaitingLists")

        var url = URL(startUrl)
        try {
            for (hop in 0 until 8) {
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    cookies.getCookie(url.toString())?.let { setRequestProperty("Cookie", it) }
                }
                try {
                    val code = connection.responseCode
                    connection.headerFields
                        .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                        .values.flatten()
                        .forEach { cookies.setCookie(url.toString(), it) }

                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: return Page.Failed(code, "vatsim.uk sent a redirect with no address")
                        val next = URL(url, location)
                        // Being sent to sign in (on vatsim.uk or VATSIM SSO) means the session has expired.
                        if (!isVatsimUk(next) || next.path.startsWith("/login")) return Page.LoginRequired
                        url = next
                        continue
                    }
                    if (code != 200) return Page.Failed(code, "vatsim.uk returned HTTP $code")
                    if (!isTarget(url)) return Page.LoginRequired

                    val html = connection.inputStream.bufferedReader().use { it.readText() }
                    return Page.Html(html, url.toString())
                } finally {
                    connection.disconnect()
                }
            }
            return Page.Failed(null, "Too many redirects from vatsim.uk")
        } catch (e: IOException) {
            return Page.Failed(null, "Couldn't reach vatsim.uk (${e.message ?: e.javaClass.simpleName})")
        } finally {
            cookies.flush()
        }
    }

    fun isVatsimUk(url: URL): Boolean =
        url.host.equals("www.vatsim.uk", ignoreCase = true) || url.host.equals("vatsim.uk", ignoreCase = true)

    fun isWaitingListsPage(url: URL): Boolean =
        isVatsimUk(url) && url.path.trimEnd('/').equals("/mship/waiting-lists", ignoreCase = true)

    // Reads the "ATC Waiting Lists" and "Pilot Waiting Lists" tables. Columns are found by
    // header text because the pilot table has no roster or theory columns.
    fun parse(html: String, baseUri: String): FetchResult {
        val document = Jsoup.parse(html, baseUri)
        var found = false
        val lists = mutableListOf<WaitingListEntry>()

        for (panel in document.select(".panel")) {
            val heading = panel.selectFirst(".panel-heading") ?: continue
            val table = panel.selectFirst("table") ?: continue
            val title = heading.text()
            if (!title.contains("waiting lists", ignoreCase = true)) continue
            found = true

            val department = if (title.contains("pilot", ignoreCase = true)) "Pilot" else "ATC"
            val headers = table.select("thead th").map { it.text().trim().lowercase() }
            fun column(prefix: String) = headers.indexOfFirst { it.startsWith(prefix) }
            val nameColumn = column("waiting list")
            val positionColumn = column("position")
            val joinedColumn = column("joined")
            val rosterColumn = column("on roster")
            val theoryColumn = column("theory")

            for (row in table.select("tbody tr")) {
                val cells = row.select("td")
                if (cells.size < 3) continue
                fun text(i: Int) = cells.getOrNull(i)?.text()?.trim().orEmpty()
                fun tick(i: Int): Boolean? = cells.getOrNull(i)?.selectFirst("img")?.attr("alt")?.let { it == "Tick" }

                lists += WaitingListEntry(
                    department = department,
                    name = text(nameColumn),
                    position = text(positionColumn).toIntOrNull(),
                    joined = text(joinedColumn),
                    onRoster = tick(rosterColumn),
                    theoryPassed = tick(theoryColumn),
                )
            }
        }

        return if (found) FetchResult.Ok(lists)
        else FetchResult.Error("Couldn't find the waiting list tables. The page layout may have changed.")
    }
}
