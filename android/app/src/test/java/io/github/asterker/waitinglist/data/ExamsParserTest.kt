package io.github.asterker.waitinglist.data

import org.junit.Assert.assertEquals
import org.junit.Test

// Follows the markup Filament 5 tables produce on vatsim.uk's "My Exams" page.
class ExamsParserTest {

    private fun table(heading: String, headers: List<String>, rows: List<List<String>>) = """
        <div class="fi-ta">
          <div class="fi-ta-ctn">
            <div class="fi-ta-header-ctn"><div class="fi-ta-header"><div>
              <h3 class="fi-ta-header-heading">$heading</h3>
            </div></div></div>
            <div class="fi-ta-content-ctn">
              <table class="fi-ta-table">
                <thead><tr>${headers.joinToString("") { """<th class="fi-ta-header-cell"><span class="fi-ta-header-cell-label">$it</span></th>""" }}<th class="fi-ta-actions-header-cell"></th></tr></thead>
                <tbody>${rows.joinToString("") { row -> "<tr class=\"fi-ta-row\">" + row.joinToString("") { """<td class="fi-ta-cell"><div class="fi-ta-text"><div class="fi-ta-text-item">$it</div></div></td>""" } + "</tr>" }}</tbody>
              </table>
            </div>
          </div>
        </div>
    """

    private val html = """
        <html><body>
        ${table("Pending Exams", listOf("Exam", "Position", "Exam Date", "Exam Time"), listOf(listOf("S3", "EGLL_APP", "20. 11. 2026", "18:00")))}
        <h2>Exam History</h2>
        ${table("Practical Exam History", listOf("Exam", "Position", "Result", "Exam date"), listOf(
            listOf("S2", "EGKK_TWR", """<span class="fi-badge fi-color-success"><span class="fi-badge-label">Passed</span></span>""", "14. 10. 2026 19:00",
                """<a href="/training/exams/report/812" class="fi-link">View Report</a>"""),
            listOf("S1", "EGKK_GND", "Failed - Resubmit", "02. 08. 2026 18:00", """<a href="/training/exams/report/640">View Report</a>"""),
        ))}
        ${table("Theory Exam History", listOf("Exam", "Score", "Result", "Exam date"), listOf(
            listOf("S2", "38 / 40 (95%)", "Passed", "Sep 1, 2026 20:11:04", """<button>View Report</button>"""),
        ))}
        </body></html>
    """

    private fun parse(): List<ExamRecord> {
        val result = SessionsClient.parseExams(html, "https://www.vatsim.uk/training/my-training/exams")
        @Suppress("UNCHECKED_CAST")
        return (result as SessionsClient.Result.Ok<List<ExamRecord>?>).value!!
    }

    @Test
    fun readsPracticalExams() {
        val (s2, s1) = parse().filter { it.type == "Practical" }
        assertEquals("S2", s2.exam)
        assertEquals("EGKK_TWR", s2.position)
        assertEquals("Passed", s2.result)
        assertEquals("14. 10. 2026 19:00", s2.date)
        assertEquals("https://www.vatsim.uk/training/exams/report/812", s2.reportUrl)
        assertEquals("P812", s2.id)
        assertEquals("Failed - Resubmit", s1.result)
    }

    @Test
    fun ignoresTheoryAndPendingExams() {
        assertEquals(listOf("EGKK_TWR", "EGKK_GND"), parse().map { it.position })
    }

    @Test
    fun noTablesMeansError() {
        val result = SessionsClient.parseExams("<html><body>Login</body></html>", "https://www.vatsim.uk/")
        assertEquals(true, result is SessionsClient.Result.Error)
    }
}
