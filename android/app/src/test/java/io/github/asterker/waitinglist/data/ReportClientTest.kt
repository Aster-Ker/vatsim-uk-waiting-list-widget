package io.github.asterker.waitinglist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The HTML below follows the markup Filament 5 produces for vatsim.uk's ViewMentoringReport page.
class ReportClientTest {

    private fun entry(label: String, value: String, hiddenLabel: Boolean = false, helper: String? = null, content: String? = null) = """
        <div class="fi-in-entry">
          ${if (hiddenLabel) """<div class="fi-in-entry-label fi-sr-only" role="term">$label</div>""" else ""}
          <div class="fi-in-entry-label-col">
            ${if (!hiddenLabel) """<div class="fi-in-entry-label-ctn"><div class="fi-in-entry-label" role="term">$label</div></div>""" else ""}
          </div>
          <div class="fi-in-entry-content-col">
            <div class="fi-in-entry-content-ctn" role="definition">
              <div class="fi-in-entry-content">
                ${content ?: """<div class="fi-in-text"><div class="fi-in-text-item"><div>$value</div></div></div>"""}
              </div>
            </div>
            ${helper?.let { """<div class="fi-sc"><div class="fi-sc-text">$it</div></div>""" } ?: ""}
          </div>
        </div>
    """

    private fun section(title: String, body: String) = """
        <section class="fi-section fi-section-has-header">
          <header class="fi-section-header"><div class="fi-section-header-content">
            <h3 class="fi-section-header-heading">$title</h3>
          </div></header>
          <div class="fi-section-content-ctn"><div class="fi-section-content">$body</div></div>
        </section>
    """

    private fun progress(id: Int, score: String, delta: String?) = entry(
        "Field progress $id", "", hiddenLabel = true,
        content = """
            <div class="flex flex-col w-full max-w-xs gap-2 font-sans select-none">
              <div class="flex items-end justify-between px-1">
                <span class="fi-badge fi-color-lime"><span class="fi-badge-label">$score</span></span>
                ${delta?.let { """<span class="text-base font-medium tracking-wide text-emerald-600">$it</span>""" } ?: ""}
              </div>
              <div class="relative flex w-full h-6"><div title="Covered"></div><div title="Developing"></div></div>
            </div>
        """,
    )

    private fun row(id: Int, name: String, notes: String?, right: String) = """
        <div class="fi-grid fi-sc mb-2 last:mb-0">
          <div class="fi-grid-col"><div class="fi-grid gap-0 fi-sc">
            ${entry("Field name $id", name, hiddenLabel = true)}
            ${notes?.let { entry("Notes", "", hiddenLabel = true, content = """<div class="fi-in-text"><div class="fi-in-text-item"><div class="fi-prose">$it</div></div></div>""") } ?: ""}
          </div></div>
          $right
        </div>
    """

    private val html = """
        <html><body>
        ${section("Session Summary", """
            ${entry("Student", "Alex Pilot", helper = "1234567")}
            ${entry("Mentor", "Sam Mentor", helper = "7654321")}
            ${entry("Position & Time", "EGLL_GND", helper = "03. 11. 2026 | 18:00 - 19:30")}
            <div class="fi-callout"><div class="fi-callout-main"><div class="fi-callout-text">
              <h4 class="fi-callout-heading">Adjacent ATC Online</h4>
              <p class="fi-callout-description">EGLL_TWR, LON_S_CTR</p>
            </div></div></div>
        """)}
        ${section("Additional Comments", entry("Additional comments", "", hiddenLabel = true,
            content = """<div class="fi-prose">Great session overall.<br>Work on phraseology.<p>See you next week.</p></div>"""))}
        ${section("Session Report", """
            ${section("<span class='text-2xl font-extrabold'>Ground Movement</span>", """
                ${row(11, "Taxi instructions", "Clear and concise.<br>Watch hold short points.", progress(11, "Good", "+1"))}
                ${row(12, "Pushback", null, progress(12, "Developing", null))}
            """)}
            ${section("<span class='text-2xl font-extrabold'>Circuits</span>", """
                ${row(21, "Joining", "Good joins.", entry("Best", "Good") + entry("Previous", "Covered") + entry("Current", "Good"))}
            """)}
        """)}
        ${section("Other Sessions", entry("View more", "View more"))}
        </body></html>
    """

    @Test
    fun readsSummary() {
        val report = ReportClient.parse(html, "https://www.vatsim.uk/training/mentoring/report/1")
        assertEquals("EGLL_GND", report.position)
        assertEquals("03. 11. 2026 | 18:00 - 19:30", report.dateTime)
        assertEquals("Sam Mentor", report.mentor)
        assertEquals("Alex Pilot", report.student)
        assertEquals("Adjacent ATC Online", report.notices.single().heading)
        assertEquals("EGLL_TWR, LON_S_CTR", report.notices.single().description)
    }

    @Test
    fun readsCategoriesAndScores() {
        val report = ReportClient.parse(html, "https://www.vatsim.uk/training/mentoring/report/1")
        assertEquals(listOf("Ground Movement", "Circuits"), report.categories.map { it.name })

        val (taxi, pushback) = report.categories[0].criteria
        assertEquals("Taxi instructions", taxi.name)
        assertEquals("Good", taxi.score)
        assertEquals(4, taxi.level)
        assertEquals(1, taxi.delta)
        assertEquals("Clear and concise.\nWatch hold short points.", taxi.notes)
        assertEquals("Developing", pushback.score)
        assertNull(pushback.delta)
        assertNull(pushback.notes)

        val joining = report.categories[1].criteria.single()
        assertEquals("Good", joining.score)
        assertEquals("Covered", joining.previous)
        assertEquals("Good", joining.best)
        assertEquals(2, joining.delta)
    }

    @Test
    fun readsComments() {
        val report = ReportClient.parse(html, "https://www.vatsim.uk/training/mentoring/report/1")
        assertEquals("Great session overall.\nWork on phraseology.\nSee you next week.", report.comments)
    }

    private val examHtml = """
        <html><body>
        ${section("", section("Student", entry("Name", "Alex Pilot") + entry("CID", "1234567") + entry("Qualification", "S1")) +
            section("Exam", entry("Exam", "S2") + entry("Position", "EGKK_TWR") + entry("Date", "2026-10-14 19:00:00") +
                entry("Primary Examiner", "Jo Examiner") + entry("Secondary Examiner", "") + entry("Trainee Examiner", "")))}
        ${section("Exam Result", entry("Result", "", content = """<span class="fi-badge fi-color-success"><span class="fi-badge-label">Passed</span></span>""") +
            entry("Additional Comments", "", content = """<div class="fi-in-text"><div class="fi-in-text-item">Solid exam.<br>Well done!</div></div>"""))}
        <h1 class="text-xl font-bold">Grades</h1>
        <div class="fi-in-repeatable"><ul>
          <li class="fi-in-repeatable-item">
            ${entry("Exam criteria.criteria", "Runway management")}
            ${entry("Result", "", content = """<div class="flex items-center"><span class="leading-none">Fully Competent</span></div>""")}
            ${entry("Notes", "", content = """<div class="fi-in-text"><div class="fi-in-text-item">Good spacing.</div></div>""")}
          </li>
          <li class="fi-in-repeatable-item">
            ${entry("Exam criteria.criteria", "Phraseology")}
            ${entry("Result", "", content = """<div class="flex items-center"><span class="leading-none">Mostly Competent</span></div>""")}
            ${entry("Notes", "", content = "")}
          </li>
        </ul></div>
        </body></html>
    """

    @Test
    fun readsExamReport() {
        val report = ReportClient.parseExam(examHtml, "https://www.vatsim.uk/training/exams/report/812")
        assertEquals("S2", report.exam)
        assertEquals("EGKK_TWR", report.position)
        assertEquals("Wed 14 Oct 2026, 19:00", report.date)
        assertEquals("S1", report.qualification)
        assertEquals(listOf("Primary" to "Jo Examiner"), report.examiners)
        assertEquals("Passed", report.result)
        assertEquals("Solid exam.\nWell done!", report.comments)
        assertEquals(listOf("Runway management", "Phraseology"), report.grades.map { it.criterion })
        assertEquals(listOf("Fully Competent", "Mostly Competent"), report.grades.map { it.grade })
        assertEquals("Good spacing.", report.grades[0].notes)
        assertNull(report.grades[1].notes)
    }

    @Test
    fun emptyPageIsEmpty() {
        assertTrue(ReportClient.parse("<html><body><p>Login</p></body></html>", "https://www.vatsim.uk/").isEmpty)
    }
}
