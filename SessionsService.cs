using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace WaitingListWidget;

// Reads training sessions from two vatsim.uk sources:
//  - the member's personal calendar feed (bookings, mentoring sessions, exams), whose link
//    is shown on the dashboard under "Calendar subscription"
//  - "My Training History" in the Training section, which says whether a mentor report is filed
static class SessionsService
{
    const string DashboardUrl = "https://www.vatsim.uk/mship/manage/dashboard";
    public const string HistoryUrl = "https://www.vatsim.uk/training/my-training/mentoring-history";
    public const string ExamsUrl = "https://www.vatsim.uk/training/my-training/exams";

    // Reads the "Practical Exam History" and "Theory Exam History" tables on My Exams.
    const string ExamsScript = """
        (() => {
          const exams = [];
          let found = false;
          for (const container of document.querySelectorAll('.fi-ta')) {
            const heading = (container.querySelector('.fi-ta-header-heading')?.textContent || '').trim();
            const type = /practical exam history/i.test(heading) ? 'Practical' : /theory exam history/i.test(heading) ? 'Theory' : null;
            if (!type) continue;
            found = true;
            const table = container.querySelector('table.fi-ta-table') || container.querySelector('table');
            if (!table) continue;
            const headers = [...table.querySelectorAll('thead th')].map(th => th.textContent.trim().toLowerCase());
            const col = name => headers.findIndex(h => h.startsWith(name));
            const examCol = Math.max(0, headers.findIndex(h => h === 'exam'));
            for (const row of table.querySelectorAll('tbody tr')) {
              const cells = row.querySelectorAll('td');
              if (cells.length < 3) continue;
              const text = i => (i >= 0 && cells[i] ? cells[i].textContent.replace(/\s+/g, ' ').trim() : '');
              const exam = text(examCol), result = text(col('result'));
              if (!exam && !result) continue;
              const link = row.querySelector('a[href*="/exams/report/"]');
              exams.push({ type, exam, position: text(col('position')) || null, result,
                score: text(col('score')) || null, date: text(col('exam date')), reportUrl: link ? link.href : null });
            }
          }
          return { found: found || /Exam History/i.test(document.body.innerText), exams };
        })()
        """;

    sealed record ExamRow(string Type, string Exam, string? Position, string Result, string? Score, string Date, string? ReportUrl);
    sealed record ExamsPage(bool Found, List<ExamRow> Exams);

    const string FeedUrlScript = """
        (() => {
          const input = document.querySelector('#calendar-feed-url');
          if (input && input.value) return input.value;
          const match = document.documentElement.innerHTML.match(/https:\/\/[^\s"'<>]+\/calendar\/feed\/[A-Za-z0-9]{48}\.ics/);
          return match ? match[0] : null;
        })()
        """;

    // Columns are found by header text; status and report link have fallbacks in case they move.
    const string HistoryScript = """
        (() => {
          const table = document.querySelector('table.fi-ta-table') || document.querySelector('table');
          if (!table) {
            return { found: /No mentoring sessions found/i.test(document.body.innerText), rows: [] };
          }
          const headers = [...table.querySelectorAll('thead th')].map(th => th.textContent.trim().toLowerCase());
          const col = name => headers.findIndex(h => h.startsWith(name));
          const statuses = ['No Show', 'Cancelled', 'Completed', 'Pending'];
          const rows = [];
          for (const row of table.querySelectorAll('tbody tr')) {
            const cells = row.querySelectorAll('td');
            if (cells.length < 3) continue;
            const text = i => (i >= 0 && cells[i] ? cells[i].textContent.replace(/\s+/g, ' ').trim() : '');
            const statusCell = text(col('status'));
            const status = statuses.find(s => statusCell.toLowerCase().includes(s.toLowerCase()))
              || statuses.find(s => row.textContent.toLowerCase().includes(s.toLowerCase()));
            if (!status) continue;
            const link = row.querySelector('a[href*="/mentoring/report/"]');
            rows.push({
              position: text(col('position')),
              date: text(col('date')),
              mentor: text(col('mentor')).replace(/\s*\d{4,}\s*$/, ''),
              status,
              reportUrl: link ? link.href : null,
            });
          }
          return { found: true, rows };
        })()
        """;

    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };

    public sealed record Notice(string Title, string Body, string? Url = null);

    public sealed record Outcome(SessionsState State, List<Notice> Notices, bool LoginRequired = false);

    sealed record HistoryRow(string Position, string Date, string Mentor, string Status, string? ReportUrl);
    sealed record HistoryPage(bool Found, List<HistoryRow> Rows);

    public static async Task<Outcome> SyncAsync(BrowserForm browser, SessionsState previous)
    {
        var now = DateTime.UtcNow;
        string? message = null;

        // Bookings, from the calendar feed. Look the link up again if it was reset on the dashboard.
        string? feedUrl = previous.FeedUrl;
        List<SessionBooking>? bookings = null;
        for (int attempt = 0; attempt < 2; attempt++)
        {
            if (feedUrl == null)
            {
                var page = await browser.LoadAsync(new BrowserForm.PageJob(DashboardUrl,
                    uri => BrowserForm.IsVatsimUk(uri) && uri.AbsolutePath.StartsWith("/mship/", StringComparison.OrdinalIgnoreCase),
                    FeedUrlScript));
                if (page.Status == FetchStatus.LoginRequired)
                    return new Outcome(previous, new(), LoginRequired: true);
                feedUrl = page.Status == FetchStatus.Ok ? JsonSerializer.Deserialize<string?>(page.Json ?? "null") : null;
                if (feedUrl == null)
                    message = page.Message ?? "Couldn't find your calendar link on the vatsim.uk dashboard";
            }
            if (feedUrl == null)
                break;

            try
            {
                using var response = await Http.GetAsync(feedUrl);
                if (response.StatusCode == HttpStatusCode.NotFound)
                    feedUrl = null;
                else if (response.IsSuccessStatusCode)
                    bookings = ParseIcs(await response.Content.ReadAsStringAsync());
                else
                    message = $"vatsim.uk calendar returned HTTP {(int)response.StatusCode}";
            }
            catch (Exception ex)
            {
                message = "Couldn't reach the vatsim.uk calendar: " + ex.Message;
            }
            if (bookings != null || feedUrl != null)
                break;
        }

        // Training history, for mentor reports.
        List<TrainingRecord>? history = null;
        bool historyAvailable = previous.HistoryAvailable;
        var historyPage = await browser.LoadAsync(new BrowserForm.PageJob(HistoryUrl,
            uri => BrowserForm.IsVatsimUk(uri) && uri.AbsolutePath.StartsWith("/training", StringComparison.OrdinalIgnoreCase),
            HistoryScript));
        if (historyPage.Status == FetchStatus.LoginRequired)
            return new Outcome(previous, new(), LoginRequired: true);
        if (historyPage.HttpStatus is 403 or 404)
        {
            historyAvailable = false;
            history = new();
        }
        else if (historyPage.Status == FetchStatus.Ok)
        {
            var parsed = JsonSerializer.Deserialize<HistoryPage>(historyPage.Json ?? "null", new JsonSerializerOptions(JsonSerializerDefaults.Web));
            if (parsed is { Found: true })
            {
                historyAvailable = true;
                history = parsed.Rows.Select(r => new TrainingRecord
                {
                    Id = r.ReportUrl?.Split('/').Last() ?? $"{r.Position}|{r.Date}",
                    Position = r.Position,
                    Date = r.Date,
                    Mentor = r.Mentor,
                    Status = r.Status,
                    ReportUrl = r.ReportUrl,
                }).ToList();
            }
            else
            {
                message ??= "Couldn't read My Training History. The page layout may have changed.";
            }
        }
        else
        {
            message ??= historyPage.Message;
        }

        // Exam results, from My Exams.
        List<ExamRecord>? exams = null;
        var examsPage = await browser.LoadAsync(new BrowserForm.PageJob(ExamsUrl,
            uri => BrowserForm.IsVatsimUk(uri) && uri.AbsolutePath.StartsWith("/training", StringComparison.OrdinalIgnoreCase),
            ExamsScript));
        if (examsPage.Status == FetchStatus.LoginRequired)
            return new Outcome(previous, new(), LoginRequired: true);
        if (examsPage.HttpStatus is 403 or 404)
        {
            exams = new();
        }
        else if (examsPage.Status == FetchStatus.Ok)
        {
            var parsed = JsonSerializer.Deserialize<ExamsPage>(examsPage.Json ?? "null", new JsonSerializerOptions(JsonSerializerDefaults.Web));
            if (parsed is { Found: true })
            {
                exams = parsed.Exams.Select(e => new ExamRecord
                {
                    Id = e.ReportUrl != null ? "P" + e.ReportUrl.Split('/').Last() : $"{e.Type[0]}|{e.Exam}|{e.Position}|{e.Date}",
                    Type = e.Type,
                    Exam = e.Exam,
                    Position = e.Position,
                    Result = e.Result,
                    Score = e.Score,
                    Date = e.Date,
                    ReportUrl = e.ReportUrl,
                }).ToList();
            }
            else
            {
                message ??= "Couldn't read My Exams. The page layout may have changed.";
            }
        }
        else
        {
            message ??= examsPage.Message;
        }

        bool firstSync = previous.LastChecked == null;
        var notices = new List<Notice>();
        if (bookings != null && !firstSync)
            notices.AddRange(BookingChanges(previous.Bookings, bookings, now));
        if (history != null && !firstSync && previous.HistoryAvailable)
            notices.AddRange(ReportChanges(previous.History, history));
        if (exams != null && previous.ExamsChecked != null)
            notices.AddRange(ExamChanges(previous.Exams, exams));

        var state = new SessionsState
        {
            FeedUrl = feedUrl,
            Bookings = bookings ?? previous.Bookings,
            History = history ?? previous.History,
            HistoryAvailable = historyAvailable,
            Exams = exams ?? previous.Exams,
            ExamsChecked = exams != null ? previous.ExamsChecked ?? now : previous.ExamsChecked,
            LastChecked = bookings != null || history != null ? now : previous.LastChecked,
            Message = message,
            Alerts = previous.Alerts,
            RemindMinutes = previous.RemindMinutes,
            // Forget reminders for sessions that are over.
            Reminded = previous.Reminded.Where(uid => (bookings ?? previous.Bookings).Any(b => b.Uid == uid && b.End > now)).ToHashSet(),
        };
        return new Outcome(state, notices);
    }

    static IEnumerable<Notice> BookingChanges(List<SessionBooking> old, List<SessionBooking> fresh, DateTime now)
    {
        var before = old.ToDictionary(b => b.Uid);
        var after = fresh.Select(b => b.Uid).ToHashSet();

        foreach (var booking in fresh.Where(b => b.Start > now))
        {
            if (!before.TryGetValue(booking.Uid, out var was))
                yield return new Notice($"New {booking.Kind.ToLowerInvariant()} booked",
                    Join(booking.Position, booking.TimeRange, booking.WithWhom));
            else if (was.Start != booking.Start || was.End != booking.End)
                yield return new Notice($"{booking.Kind} moved",
                    Join(booking.Position, "now " + booking.TimeRange, $"was {was.Start.ToLocalTime():ddd HH:mm}"));
        }
        foreach (var removed in old.Where(b => b.Start > now && !after.Contains(b.Uid)))
            yield return new Notice($"{removed.Kind} removed", Join(removed.Position, removed.TimeRange) + " is no longer booked");
    }

    static IEnumerable<Notice> ReportChanges(List<TrainingRecord> old, List<TrainingRecord> fresh)
    {
        var hadReport = old.Where(r => r.ReportUrl != null).SelectMany(r => new[] { r.Id, $"{r.Position}|{r.Date}" }).ToHashSet();
        return fresh
            .Where(r => r.ReportUrl != null && !hadReport.Contains(r.Id) && !hadReport.Contains($"{r.Position}|{r.Date}"))
            .Select(r => new Notice("Mentor feedback ready",
                Join(Blank(r.Position), Blank(r.Date), Blank(r.Mentor) is string m ? "with " + m : null) + ". Click to read the report.",
                r.ReportUrl));
    }

    static IEnumerable<Notice> ExamChanges(List<ExamRecord> old, List<ExamRecord> fresh)
    {
        var known = old.Select(e => e.Id).ToHashSet();
        return fresh
            .Where(e => !known.Contains(e.Id) && !string.IsNullOrWhiteSpace(e.Result))
            .Select(e => new Notice($"Exam result: {e.Result}",
                Join($"{e.Type} exam", Blank(e.Exam), e.Position, e.Score, Blank(e.Date)) + (e.ReportUrl != null ? ". Click to read the report." : ""),
                e.ReportUrl));
    }

    static string? Blank(string s) => string.IsNullOrWhiteSpace(s) ? null : s;

    static string Join(params string?[] parts) => string.Join(" · ", parts.Where(p => !string.IsNullOrEmpty(p)));

    // ---------- iCalendar ----------

    public static List<SessionBooking> ParseIcs(string ics)
    {
        // Undo line folding: a line starting with a space or tab continues the previous one.
        var unfolded = Regex.Replace(ics.Replace("\r\n", "\n"), "\n[ \t]", "");
        var bookings = new List<SessionBooking>();
        Dictionary<string, string>? fields = null;

        foreach (var line in unfolded.Split('\n'))
        {
            if (line == "BEGIN:VEVENT")
                fields = new();
            else if (line == "END:VEVENT")
            {
                if (fields != null && ToBooking(fields) is SessionBooking booking)
                    bookings.Add(booking);
                fields = null;
            }
            else if (fields != null)
            {
                int colon = line.IndexOf(':');
                if (colon > 0)
                    fields[line[..colon].Split(';')[0].ToUpperInvariant()] = line[(colon + 1)..];
            }
        }
        return bookings.OrderBy(b => b.Start).ToList();
    }

    static SessionBooking? ToBooking(Dictionary<string, string> f)
    {
        if (!f.TryGetValue("UID", out var uid) || ParseUtc(f.GetValueOrDefault("DTSTART")) is not DateTime start)
            return null;
        var end = ParseUtc(f.GetValueOrDefault("DTEND")) ?? start;
        var summary = Unescape(f.GetValueOrDefault("SUMMARY") ?? "");
        var details = Unescape(f.GetValueOrDefault("DESCRIPTION") ?? "")
            .Split('\n')
            .Select(l => l.Split(": ", 2))
            .Where(p => p.Length == 2)
            .GroupBy(p => p[0])
            .ToDictionary(g => g.Key, g => g.First()[1]);

        // Summaries look like "EGLL_GND - Mentoring session" or just "ATC booking".
        int dash = summary.IndexOf(" - ", StringComparison.Ordinal);
        var kind = dash >= 0 ? summary[(dash + 3)..] : summary;
        var location = Unescape(f.GetValueOrDefault("LOCATION") ?? "");
        var position = !string.IsNullOrWhiteSpace(location) ? location
            : details.GetValueOrDefault("Position") ?? (dash > 0 ? summary[..dash] : null);

        return new SessionBooking
        {
            Uid = uid,
            Kind = kind,
            Position = position,
            Start = start,
            End = end,
            Mentor = details.GetValueOrDefault("Mentor"),
            Examiner = details.GetValueOrDefault("Examiner"),
            Student = details.GetValueOrDefault("Student"),
            Url = f.GetValueOrDefault("URL"),
        };
    }

    static DateTime? ParseUtc(string? value) =>
        DateTime.TryParseExact(value, "yyyyMMdd'T'HHmmss'Z'", CultureInfo.InvariantCulture,
            DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out var parsed) ? parsed : null;

    static string Unescape(string text)
    {
        var sb = new StringBuilder();
        for (int i = 0; i < text.Length; i++)
        {
            if (text[i] == '\\' && i + 1 < text.Length)
            {
                char next = text[++i];
                sb.Append(next is 'n' or 'N' ? '\n' : next);
            }
            else
            {
                sb.Append(text[i]);
            }
        }
        return sb.ToString();
    }
}
