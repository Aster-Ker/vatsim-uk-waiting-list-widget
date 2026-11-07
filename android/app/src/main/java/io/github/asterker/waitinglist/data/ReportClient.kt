package io.github.asterker.waitinglist.data

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

// A mentor report from vatsim.uk's Training section, read into plain data so the app can show it natively.
data class MentorReport(
    val position: String?,
    val dateTime: String?,
    val mentor: String?,
    val student: String?,
    val notices: List<ReportNotice>,
    val categories: List<ReportCategory>,
    val comments: String?,
) {
    val isEmpty: Boolean get() = position == null && mentor == null && categories.isEmpty() && comments == null
}

data class ReportNotice(val heading: String, val description: String?)

data class ReportCategory(val name: String, val criteria: List<Criterion>)

data class Criterion(
    val name: String,
    val score: String?,
    // Change since the previous session, e.g. +1 or -1.
    val delta: Int?,
    val previous: String?,
    val best: String?,
    val notes: String?,
) {
    // Covered = 2 up to Test Standard = 5, as on vatsim.uk; anything else isn't scored.
    val level: Int? get() = scoreLevel(score)
}

fun scoreLevel(label: String?): Int? = when (label?.trim()?.lowercase()) {
    "covered" -> 2
    "developing" -> 3
    "good" -> 4
    "test standard" -> 5
    else -> null
}

object ReportClient {
    sealed interface Result {
        data class Ok(val report: MentorReport) : Result
        data object LoginRequired : Result
        data class Error(val message: String) : Result
    }

    fun isReportUrl(url: String): Boolean = url.contains("/mentoring/report/")

    fun fetch(context: Context, url: String): Result {
        val page = WaitingListClient.fetchPage(context, url) { target ->
            WaitingListClient.isVatsimUk(target) && target.path.contains("/mentoring/report/")
        }
        return when (page) {
            is WaitingListClient.Page.Html -> {
                val report = parse(page.html, page.url)
                if (report.isEmpty) Result.Error("Couldn't read this report. The page layout may have changed.")
                else Result.Ok(report)
            }
            WaitingListClient.Page.LoginRequired -> Result.LoginRequired
            is WaitingListClient.Page.Failed ->
                Result.Error(if (page.code == 403) "You don't have access to this report." else page.message)
        }
    }

    // The page is built from Filament sections ("Session Summary", "Session Report" with one
    // section per category, "Additional Comments"). Entries are found by their labels.
    fun parse(html: String, baseUri: String): MentorReport {
        val document = Jsoup.parse(html, baseUri)
        val sections = document.select(".fi-section")
        fun section(title: String) = sections.firstOrNull { heading(it).equals(title, ignoreCase = true) }

        val summary = section("Session Summary")
        val summaryEntries = summary?.select(".fi-in-entry").orEmpty().associateBy { label(it) }
        val positionEntry = summaryEntries.entries.firstOrNull { it.key.startsWith("Position", ignoreCase = true) }?.value
        val notices = summary?.select(".fi-callout").orEmpty().mapNotNull { callout ->
            val title = callout.selectFirst(".fi-callout-heading")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val details = listOfNotNull(
                callout.selectFirst(".fi-callout-description")?.text()?.trim(),
                callout.selectFirst(".fi-callout-footer")?.text()?.trim(),
            ).filter { it.isNotEmpty() }
            ReportNotice(title, details.joinToString("\n").ifEmpty { null })
        }

        val reportSection = section("Session Report")
        val categories = reportSection?.select(".fi-section").orEmpty()
            .filter { it !== reportSection }
            .map { category -> ReportCategory(heading(category), criteria(category)) }
            .filter { it.name.isNotEmpty() }

        val comments = section("Additional Comments")?.selectFirst(".fi-in-entry-content")?.let(::richText)

        return MentorReport(
            position = positionEntry?.let(::value)?.ifEmpty { null },
            dateTime = positionEntry?.let(::helper)?.ifEmpty { null },
            mentor = summaryEntries["Mentor"]?.let(::value)?.ifEmpty { null },
            student = summaryEntries["Student"]?.let(::value)?.ifEmpty { null },
            notices = notices,
            categories = categories,
            comments = comments?.ifEmpty { null },
        )
    }

    // Each criterion is a grid row with the field name, its notes, and either a progress
    // bar (ATC) or Best / Previous / Current badges (pilot).
    private fun criteria(category: Element): List<Criterion> {
        val rows = category.getElementsByClass("last:mb-0").filter { row ->
            row.select(".fi-in-entry").isNotEmpty() && row.parents().none { it in category.getElementsByClass("last:mb-0") }
        }
        return rows.mapNotNull { row ->
            val entries = row.select(".fi-in-entry")
            fun byLabel(prefix: String) = entries.firstOrNull { label(it).startsWith(prefix, ignoreCase = true) }

            val name = (byLabel("Field name") ?: entries.firstOrNull())?.let(::value)?.ifEmpty { null } ?: return@mapNotNull null
            val notes = byLabel("Notes")?.selectFirst(".fi-in-entry-content")?.let(::richText)?.ifEmpty { null }

            val progress = byLabel("Field progress")
            if (progress != null) {
                val score = progress.selectFirst(".fi-badge")?.text()?.trim()
                val delta = progress.getAllElements()
                    .firstOrNull { it.ownText().trim().matches(Regex("""[+-]\d+""")) }
                    ?.ownText()?.trim()?.toIntOrNull()
                Criterion(name, score, delta, previous = null, best = null, notes = notes)
            } else {
                val current = byLabel("Current")?.let(::value)
                val previous = byLabel("Previous")?.let(::value)
                val delta = scoreLevel(current)?.let { now -> scoreLevel(previous)?.let { now - it } }?.takeIf { it != 0 }
                Criterion(name, current, delta, previous, byLabel("Best")?.let(::value), notes)
            }
        }
    }

    private fun heading(section: Element): String =
        section.selectFirst(".fi-section-header-heading")?.text()?.trim().orEmpty()

    private fun label(entry: Element): String =
        entry.selectFirst(".fi-in-entry-label")?.text()?.trim().orEmpty()

    private fun value(entry: Element): String =
        (entry.selectFirst(".fi-in-text-item") ?: entry.selectFirst(".fi-in-entry-content"))?.text()?.trim().orEmpty()

    // Helper text sits under the entry's content, e.g. the date and time under the position.
    private fun helper(entry: Element): String {
        val column = entry.selectFirst(".fi-in-entry-content-col")?.clone() ?: return ""
        column.select(".fi-in-entry-content-ctn").remove()
        return column.text().trim()
    }

    // Text with line breaks kept for <br>, paragraphs and list items.
    fun richText(element: Element): String {
        val out = StringBuilder()
        val blocks = setOf("p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "blockquote")
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> out.append(node.text())
                    node is Element && node.normalName() == "br" -> out.append('\n')
                    node is Element && node.normalName() == "li" -> out.append("\n• ")
                    // A block starts on its own line.
                    node is Element && node.normalName() in blocks && out.isNotEmpty() && out.last() != '\n' -> out.append('\n')
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blocks) out.append('\n')
            }
        }, element)
        return out.toString()
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
