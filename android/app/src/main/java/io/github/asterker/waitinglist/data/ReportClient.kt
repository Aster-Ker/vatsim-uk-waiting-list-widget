package io.github.asterker.waitinglist.data

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

// Reports from vatsim.uk's Training section, read into plain data so the app can show them natively.
sealed interface Report

data class MentorReport(
    val position: String?,
    val dateTime: String?,
    val mentor: String?,
    val student: String?,
    val notices: List<ReportNotice>,
    val categories: List<ReportCategory>,
    val comments: String?,
) : Report {
    val isEmpty: Boolean get() = position == null && mentor == null && categories.isEmpty() && comments == null
}

data class ExamReport(
    val exam: String?,
    val position: String?,
    val date: String?,
    val qualification: String?,
    // Primary, secondary and trainee examiners that are set, as (role, name).
    val examiners: List<Pair<String, String>>,
    val result: String?,
    val comments: String?,
    val grades: List<ExamGrade>,
) : Report {
    val isEmpty: Boolean get() = exam == null && position == null && result == null && grades.isEmpty()
}

// One criterion: Fully Competent, Mostly Competent, Partly Competent, Not Assessed or Fail.
data class ExamGrade(val criterion: String, val grade: String?, val notes: String?)

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
        data class Ok(val report: Report) : Result
        data object LoginRequired : Result
        data class Error(val message: String) : Result
    }

    fun isMentorReportUrl(url: String): Boolean = url.contains("/mentoring/report/")
    fun isExamReportUrl(url: String): Boolean = url.contains("/exams/report/")
    fun isReportUrl(url: String): Boolean = isMentorReportUrl(url) || isExamReportUrl(url)

    fun fetch(context: Context, url: String): Result {
        val marker = if (isExamReportUrl(url)) "/exams/report/" else "/mentoring/report/"
        val page = WaitingListClient.fetchPage(context, url) { target ->
            WaitingListClient.isVatsimUk(target) && target.path.contains(marker)
        }
        return when (page) {
            is WaitingListClient.Page.Html -> {
                val (report, empty) = if (isExamReportUrl(url)) {
                    parseExam(page.html, page.url).let { it to it.isEmpty }
                } else {
                    parse(page.html, page.url).let { it to it.isEmpty }
                }
                if (empty) Result.Error("Couldn't read this report. The page layout may have changed.")
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

    // The exam report has "Student" and "Exam" sections, an "Exam Result" section with the result
    // and additional comments, then (for ATC exams) a "Grades" list of criteria with grade and notes.
    fun parseExam(html: String, baseUri: String): ExamReport {
        val document = Jsoup.parse(html, baseUri)
        val sections = document.select(".fi-section")
        fun section(title: String) = sections.firstOrNull { heading(it).equals(title, ignoreCase = true) }
        fun entries(section: Element?) = section?.select(".fi-in-entry").orEmpty().associateBy { label(it) }

        val exam = entries(section("Exam"))
        val student = entries(section("Student"))
        val resultSection = section("Exam Result")
        val result = entries(resultSection)

        val examiners = listOf("Primary Examiner" to "Primary", "Secondary Examiner" to "Secondary", "Trainee Examiner" to "Trainee")
            .mapNotNull { (label, role) -> exam[label]?.let(::value)?.ifEmpty { null }?.let { role to it } }

        val grades = document.select(".fi-in-repeatable-item").mapNotNull { item ->
            val itemEntries = item.select(".fi-in-entry")
            val criterion = itemEntries.firstOrNull { label(it) !in setOf("Result", "Notes") }?.let(::value)?.ifEmpty { null }
                ?: return@mapNotNull null
            ExamGrade(
                criterion = criterion,
                grade = itemEntries.firstOrNull { label(it) == "Result" }?.let(::value)?.ifEmpty { null },
                notes = itemEntries.firstOrNull { label(it) == "Notes" }?.selectFirst(".fi-in-entry-content")?.let(::richText)?.ifEmpty { null },
            )
        }

        return ExamReport(
            exam = exam["Exam"]?.let(::value)?.ifEmpty { null },
            position = exam["Position"]?.let(::value)?.ifEmpty { null },
            date = exam["Date"]?.let(::value)?.ifEmpty { null }?.let(::friendlyDate),
            qualification = student["Qualification"]?.let(::value)?.ifEmpty { null },
            examiners = examiners,
            result = result["Result"]?.let(::value)?.ifEmpty { null },
            comments = result["Additional Comments"]?.selectFirst(".fi-in-entry-content")?.let(::richText)?.ifEmpty { null },
            grades = grades,
        )
    }

    // The exam date comes through as stored, e.g. "2026-10-14 19:00:00"; show it as "Wed 14 Oct 2026, 19:00".
    private fun friendlyDate(raw: String): String {
        val parsed = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.UK).apply { isLenient = false }.parse(raw)
        }.getOrNull() ?: return raw
        return java.text.SimpleDateFormat("EEE d MMM yyyy, HH:mm", java.util.Locale.UK).format(parsed)
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
