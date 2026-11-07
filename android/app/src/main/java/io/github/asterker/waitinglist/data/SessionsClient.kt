package io.github.asterker.waitinglist.data

import android.content.Context
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Reads training sessions from two vatsim.uk sources:
//  - the member's personal calendar feed (bookings, mentoring sessions, exams), whose link
//    is shown on the dashboard under "Calendar subscription"
//  - "My Training History" in the Training section, which says whether a mentor report is filed
object SessionsClient {
    private const val DASHBOARD_URL = "https://www.vatsim.uk/mship/manage/dashboard"
    const val HISTORY_URL = "https://www.vatsim.uk/training/my-training/mentoring-history"
    const val EXAMS_URL = "https://www.vatsim.uk/training/my-training/exams"
    private val FEED_URL = Regex("""https://[^\s"'<>]+/calendar/feed/[A-Za-z0-9]{48}\.ics""")

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data object LoginRequired : Result<Nothing>
        data class Error(val message: String) : Result<Nothing>
    }

    // ---------- Calendar feed ----------

    fun findFeedUrl(context: Context): Result<String> {
        val page = WaitingListClient.fetchPage(context, DASHBOARD_URL) { url ->
            WaitingListClient.isVatsimUk(url) && url.path.startsWith("/mship/")
        }
        return when (page) {
            is WaitingListClient.Page.Html -> {
                val document = Jsoup.parse(page.html, page.url)
                val url = document.selectFirst("#calendar-feed-url")?.attr("value")?.takeIf { FEED_URL.matches(it) }
                    ?: FEED_URL.find(page.html)?.value
                if (url != null) Result.Ok(url)
                else Result.Error("Couldn't find your calendar link on the vatsim.uk dashboard")
            }
            WaitingListClient.Page.LoginRequired -> Result.LoginRequired
            is WaitingListClient.Page.Failed -> Result.Error(page.message)
        }
    }

    // Null means the link no longer works (it was reset on the dashboard) and should be looked up again.
    fun fetchBookings(feedUrl: String): Result<List<SessionBooking>?> {
        try {
            val connection = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("Accept", "text/calendar")
            }
            try {
                return when (val code = connection.responseCode) {
                    200 -> Result.Ok(parseIcs(connection.inputStream.bufferedReader().use { it.readText() }))
                    404 -> Result.Ok(null)
                    else -> Result.Error("vatsim.uk calendar returned HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            return Result.Error("Couldn't reach the vatsim.uk calendar (${e.message ?: e.javaClass.simpleName})")
        }
    }

    fun parseIcs(ics: String): List<SessionBooking> {
        // Undo line folding: a line starting with a space or tab continues the previous one.
        val lines = ics.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split("\n")
        val bookings = mutableListOf<SessionBooking>()
        var fields: MutableMap<String, String>? = null

        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> fields = mutableMapOf()
                line == "END:VEVENT" -> {
                    fields?.let { toBooking(it) }?.let { bookings += it }
                    fields = null
                }
                fields != null -> {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon).substringBefore(';').uppercase()
                        fields[name] = line.substring(colon + 1)
                    }
                }
            }
        }
        return bookings.sortedBy { it.start }
    }

    private fun toBooking(f: Map<String, String>): SessionBooking? {
        val uid = f["UID"] ?: return null
        val start = parseUtc(f["DTSTART"]) ?: return null
        val end = parseUtc(f["DTEND"]) ?: start
        val summary = unescape(f["SUMMARY"].orEmpty())
        val details = unescape(f["DESCRIPTION"].orEmpty()).lines()
            .mapNotNull { line -> line.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()

        // Summaries look like "EGLL_GND - Mentoring session" or just "ATC booking".
        val kind = summary.substringAfter(" - ", summary)
        val position = f["LOCATION"]?.let(::unescape)?.takeIf { it.isNotBlank() }
            ?: details["Position"]
            ?: summary.substringBefore(" - ", "").takeIf { it.isNotBlank() }

        return SessionBooking(
            uid = uid,
            kind = kind,
            position = position,
            start = start,
            end = end,
            mentor = details["Mentor"],
            examiner = details["Examiner"],
            student = details["Student"],
            url = f["URL"],
        )
    }

    private fun parseUtc(value: String?): Long? {
        if (value == null) return null
        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return runCatching { format.parse(value)?.time }.getOrNull()
    }

    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    // ---------- Exams ----------

    // Null means the member can't see the Training section (no training access).
    fun fetchExams(context: Context): Result<List<ExamRecord>?> {
        val page = WaitingListClient.fetchPage(context, EXAMS_URL) { url ->
            WaitingListClient.isVatsimUk(url) && url.path.startsWith("/training")
        }
        return when (page) {
            is WaitingListClient.Page.Html -> parseExams(page.html, page.url)
            WaitingListClient.Page.LoginRequired -> Result.LoginRequired
            is WaitingListClient.Page.Failed ->
                if (page.code == 403 || page.code == 404) Result.Ok(null) else Result.Error(page.message)
        }
    }

    // "My Exams" has a "Practical Exam History" table (exam, position, result, date, report link)
    // and a "Theory Exam History" table (exam, score, result, date).
    fun parseExams(html: String, baseUri: String): Result<List<ExamRecord>?> {
        val document = Jsoup.parse(html, baseUri)
        var found = false
        val exams = mutableListOf<ExamRecord>()

        for (container in document.select(".fi-ta")) {
            val heading = container.selectFirst(".fi-ta-header-heading")?.text()?.trim().orEmpty()
            val type = when {
                heading.contains("Practical Exam History", ignoreCase = true) -> "Practical"
                heading.contains("Theory Exam History", ignoreCase = true) -> "Theory"
                else -> continue
            }
            found = true
            val table = container.selectFirst("table.fi-ta-table") ?: container.selectFirst("table") ?: continue
            val headers = table.select("thead th").map { it.text().trim().lowercase() }
            fun column(name: String) = headers.indexOfFirst { it.startsWith(name) }
            // "Exam" exactly, not "Exam date".
            val examColumn = headers.indexOfFirst { it == "exam" }.takeIf { it >= 0 } ?: 0
            val positionColumn = column("position")
            val resultColumn = column("result")
            val scoreColumn = column("score")
            val dateColumn = column("exam date")

            for (row in table.select("tbody tr")) {
                val cells = row.select("td")
                if (cells.size < 3) continue
                fun text(i: Int) = if (i < 0) "" else cells.getOrNull(i)?.text()?.trim().orEmpty()
                val exam = text(examColumn)
                val result = text(resultColumn)
                if (exam.isEmpty() && result.isEmpty()) continue
                val position = text(positionColumn).ifEmpty { null }
                val date = text(dateColumn)
                val report = row.selectFirst("a[href*=/exams/report/]")?.absUrl("href")?.ifEmpty { null }
                exams += ExamRecord(
                    id = report?.let { "P" + it.substringAfterLast('/') } ?: "${type.first()}|$exam|${position.orEmpty()}|$date",
                    type = type,
                    exam = exam,
                    position = position,
                    result = result,
                    score = text(scoreColumn).ifEmpty { null },
                    date = date,
                    reportUrl = report,
                )
            }
        }

        return when {
            found -> Result.Ok(exams)
            html.contains("Exam History", ignoreCase = true) -> Result.Ok(emptyList())
            else -> Result.Error("Couldn't read My Exams. The page layout may have changed.")
        }
    }

    // ---------- Training history ----------

    // Null means the member can't see the Training section (no training access).
    fun fetchHistory(context: Context): Result<List<TrainingRecord>?> {
        val page = WaitingListClient.fetchPage(context, HISTORY_URL) { url ->
            WaitingListClient.isVatsimUk(url) && url.path.startsWith("/training")
        }
        return when (page) {
            is WaitingListClient.Page.Html -> parseHistory(page.html, page.url)
            WaitingListClient.Page.LoginRequired -> Result.LoginRequired
            is WaitingListClient.Page.Failed ->
                if (page.code == 403 || page.code == 404) Result.Ok(null) else Result.Error(page.message)
        }
    }

    private val STATUSES = listOf("No Show", "Cancelled", "Completed", "Pending")

    fun parseHistory(html: String, baseUri: String): Result<List<TrainingRecord>?> {
        val document = Jsoup.parse(html, baseUri)
        val table = document.selectFirst("table.fi-ta-table") ?: document.selectFirst("table")
        if (table == null) {
            return if (html.contains("No mentoring sessions found", ignoreCase = true)) Result.Ok(emptyList())
            else Result.Error("Couldn't read My Training History. The page layout may have changed.")
        }

        val headers = table.select("thead th").map { it.text().trim().lowercase() }
        fun column(name: String) = headers.indexOfFirst { it.startsWith(name) }
        val mentorColumn = column("mentor")
        val positionColumn = column("position")
        val dateColumn = column("date")
        val statusColumn = column("status")

        val records = table.select("tbody tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 3) return@mapNotNull null
            fun text(i: Int) = cells.getOrNull(i)?.text()?.trim().orEmpty()

            val rowText = row.text()
            val status = text(statusColumn).let { cell -> STATUSES.firstOrNull { cell.contains(it, ignoreCase = true) } }
                ?: STATUSES.firstOrNull { rowText.contains(it, ignoreCase = true) }
                ?: return@mapNotNull null
            val report = row.selectFirst("a[href*=/mentoring/report/]")?.absUrl("href")?.takeIf { it.isNotEmpty() }
            val position = text(positionColumn)
            val date = text(dateColumn)
            // The mentor cell holds the name with the CID underneath; keep just the name.
            val mentor = text(mentorColumn).replace(Regex("""\s*\d{4,}\s*$"""), "")

            TrainingRecord(
                id = report?.substringAfterLast('/') ?: "$position|$date",
                position = position,
                date = date,
                mentor = mentor,
                status = status,
                reportUrl = report,
            )
        }
        return Result.Ok(records)
    }
}
