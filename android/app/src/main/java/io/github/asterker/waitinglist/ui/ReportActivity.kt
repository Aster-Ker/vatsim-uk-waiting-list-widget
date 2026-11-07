package io.github.asterker.waitinglist.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.asterker.waitinglist.data.Criterion
import io.github.asterker.waitinglist.data.ExamReport
import io.github.asterker.waitinglist.data.MentorReport
import io.github.asterker.waitinglist.data.ReportClient
import io.github.asterker.waitinglist.data.Store
import io.github.asterker.waitinglist.data.scoreLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Shows a mentor or exam report in the app's own layout, read from vatsim.uk with the app's sign-in.
class ReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        setContent {
            PanelTheme {
                ReportScreen(url, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, ReportActivity::class.java).putExtra(EXTRA_URL, url)

        // Mentor and exam reports open natively; any other vatsim.uk page opens in the in-app browser.
        fun open(context: Context, url: String) {
            context.startActivity(
                if (ReportClient.isReportUrl(url)) intent(context, url) else WebPageActivity.intent(context, url)
            )
        }
    }
}

@Composable
private fun ReportScreen(url: String, onClose: () -> Unit) {
    val c = LocalPanel.current
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<ReportClient.Result?>(null) }

    LaunchedEffect(attempt) {
        result = null
        result = withContext(Dispatchers.IO) {
            runCatching { ReportClient.fetch(context, url) }
                .getOrElse { ReportClient.Result.Error(it.message ?: "Couldn't load the report") }
        }
    }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) attempt++
    }
    val openOnWebsite = { context.startActivity(WebPageActivity.intent(context, url)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PanelButton("‹ Back", onClose, style = ButtonStyle.Ghost)
        Spacer(Modifier.height(4.dp))
        Text(
            if (ReportClient.isExamReportUrl(url)) "Exam report" else "Mentor report",
            color = c.foreground,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text("VATSIM UK training", color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(16.dp))

        when (val current = result) {
            null -> PanelCard {
                Text("Loading the report…", color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            ReportClient.Result.LoginRequired -> PanelAlert(
                c.warning, "Sign in needed", "Your vatsim.uk session has expired.", "Sign in",
            ) { signIn.launch(Intent(context, LoginActivity::class.java)) }
            is ReportClient.Result.Error -> {
                PanelAlert(c.destructive, "Couldn't load the report", current.message, "Retry") { attempt++ }
                Spacer(Modifier.height(12.dp))
                PanelButton("Open on vatsim.uk", openOnWebsite, modifier = Modifier.fillMaxWidth())
            }
            is ReportClient.Result.Ok -> {
                when (val report = current.report) {
                    is MentorReport -> MentorReportView(report)
                    is ExamReport -> ExamReportView(report)
                }
                Spacer(Modifier.height(16.dp))
                PanelButton("Open on vatsim.uk", openOnWebsite, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ExamReportView(report: ExamReport) {
    val c = LocalPanel.current

    PanelCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(report.position ?: "Practical exam", color = c.foreground, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    report.exam?.let { Text("$it practical exam", color = c.foreground, fontSize = 14.sp) }
                }
                report.result?.let { ExamResultBadge(it) }
            }
            report.date?.let { Text(it, color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
            if (report.examiners.isNotEmpty() || report.qualification != null) Spacer(Modifier.height(8.dp))
            report.examiners.forEach { (role, name) -> Text("$role examiner: $name", color = c.muted, fontSize = 13.sp) }
            report.qualification?.let { Text("Your rating at the time: $it", color = c.muted, fontSize = 13.sp) }
        }
    }

    report.comments?.let { comments ->
        Spacer(Modifier.height(20.dp))
        SectionLabel("Examiner's comments")
        PanelCard {
            Text(comments, color = c.foreground, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(16.dp))
        }
    }

    if (report.grades.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        SectionLabel("Grades")
        PanelCard {
            report.grades.forEachIndexed { i, grade ->
                if (i > 0) Divider()
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(grade.criterion, color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        GradeBadge(grade.grade)
                    }
                    grade.notes?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = c.muted, fontSize = 13.5.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

// Passed green, Partial Pass amber, Failed red, Incomplete amber, as on vatsim.uk.
@Composable
private fun ExamResultBadge(result: String) {
    val c = LocalPanel.current
    val role = when {
        result.startsWith("Fail", ignoreCase = true) -> c.destructive
        result.startsWith("Pass", ignoreCase = true) -> c.success
        else -> c.warning
    }
    RoleBadge(result, role, dot = true)
}

// vatsim.uk's grade colours: Fully Competent green, Mostly amber, Partly orange, Fail red, Not Assessed grey.
@Composable
private fun GradeBadge(grade: String?) {
    val c = LocalPanel.current
    val colour = when (grade?.lowercase()) {
        "fully competent" -> Color(0xFF10B981)
        "mostly competent" -> Color(0xFFF59E0B)
        "partly competent" -> Color(0xFFF97316)
        "fail" -> Color(0xFFEF4444)
        else -> Color(0xFF9CA3AF)
    }
    Badge(grade ?: "Not assessed", if (c.isDark) colour else colour.darken(), colour.copy(alpha = if (c.isDark) 0.18f else 0.14f))
}

@Composable
private fun MentorReportView(report: MentorReport) {
    val c = LocalPanel.current

    PanelCard {
        Column(Modifier.padding(16.dp)) {
            Text(report.position ?: "Session", color = c.foreground, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            report.dateTime?.let { Text(it, color = c.foreground, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
            report.mentor?.let { Text("Mentor: $it", color = c.muted, fontSize = 13.sp) }
            report.student?.let { Text("Student: $it", color = c.muted, fontSize = 13.sp) }
        }
    }

    report.notices.forEach { notice ->
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.warning.tint)
                .padding(14.dp),
        ) {
            Text(notice.heading, color = c.warning.foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            notice.description?.let { Text(it, color = c.muted, fontSize = 13.sp) }
        }
    }

    if (report.categories.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        ScoreLegend()
    }
    report.categories.forEach { category ->
        Spacer(Modifier.height(20.dp))
        SectionLabel(category.name)
        PanelCard {
            if (category.criteria.isEmpty()) {
                Text("Nothing scored in this section.", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            category.criteria.forEachIndexed { i, criterion ->
                if (i > 0) Divider()
                CriterionRow(criterion)
            }
        }
    }

    report.comments?.let { comments ->
        Spacer(Modifier.height(20.dp))
        SectionLabel("Additional comments")
        PanelCard {
            Text(comments, color = c.foreground, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun CriterionRow(criterion: Criterion) {
    val c = LocalPanel.current
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(criterion.name, color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            criterion.delta?.let { delta ->
                Text(
                    if (delta > 0) "+$delta" else "$delta",
                    color = if (delta > 0) c.success.foreground else c.destructive.foreground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
            }
            ScoreBadge(criterion.score)
        }
        Spacer(Modifier.height(8.dp))
        ScoreBar(criterion.level, previous = criterion.delta?.let { d -> criterion.level?.let { it - d } })
        val history = listOfNotNull(
            criterion.previous?.let { "Previous: $it" },
            criterion.best?.let { "Best: $it" },
        )
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(history.joinToString(" · "), color = c.muted, fontSize = 12.sp)
        }
        criterion.notes?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = c.muted, fontSize = 13.5.sp, lineHeight = 19.sp)
        }
    }
}

// Colours follow vatsim.uk's scores: Covered orange, Developing amber, Good lime, Test Standard emerald.
private fun scoreColour(level: Int?): Color = when (level) {
    2 -> Color(0xFFF97316)
    3 -> Color(0xFFF59E0B)
    4 -> Color(0xFF84CC16)
    5 -> Color(0xFF10B981)
    else -> Color(0xFF9CA3AF)
}

@Composable
private fun ScoreBadge(score: String?) {
    val c = LocalPanel.current
    val colour = scoreColour(scoreLevel(score))
    Badge(score ?: "Not scored", if (c.isDark) colour else colour.darken(), colour.copy(alpha = if (c.isDark) 0.18f else 0.14f))
}

private fun Color.darken(): Color = Color(red * 0.7f, green * 0.7f, blue * 0.7f, alpha)

// Four segments, Covered to Test Standard. Segments gained since the previous session are
// drawn in the score colour, ones held from before in a softer shade, and ones lost in red.
@Composable
private fun ScoreBar(level: Int?, previous: Int?) {
    val c = LocalPanel.current
    val colour = scoreColour(level)
    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (segment in 2..5) {
            val fill = when {
                level == null || level < 2 -> c.secondary
                segment <= level && previous != null && previous >= 2 && segment <= previous -> colour.copy(alpha = 0.45f)
                segment <= level -> colour
                previous != null && segment <= previous -> c.destructive.solid.copy(alpha = 0.55f)
                else -> c.secondary
            }
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(fill))
        }
    }
}

@Composable
private fun ScoreLegend() {
    val c = LocalPanel.current
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Covered" to 2, "Developing" to 3, "Good" to 4, "Test standard" to 5).forEach { (label, level) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(scoreColour(level)))
                Spacer(Modifier.width(4.dp))
                Text(label, color = c.muted, fontSize = 11.sp)
            }
        }
    }
}
