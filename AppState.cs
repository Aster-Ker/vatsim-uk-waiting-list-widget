using System.Text.Json;

namespace WaitingListWidget;

sealed class WaitingListEntry
{
    public string Department { get; set; } = "";
    public string Name { get; set; } = "";
    public int? Position { get; set; }
    public string Joined { get; set; } = "";
    public bool? OnRoster { get; set; }
    public bool? TheoryPassed { get; set; }

    // Places gained (+) or lost (-) at the most recent position change.
    public int? Change { get; set; }

    // When that change was seen, so recently moved lists can be highlighted.
    public DateTime? ChangedAt { get; set; }

    // Your position each time it changed, oldest first, for the time estimate.
    public List<PositionSample> History { get; set; } = new();

    public string Key => $"{Department}|{Name}";
}

sealed class PositionSample
{
    public DateTime At { get; set; }
    public int Position { get; set; }
}

// A booking from the member's vatsim.uk calendar feed: ATC booking, mentoring session or exam.
sealed class SessionBooking
{
    public string Uid { get; set; } = "";
    public string Kind { get; set; } = "";
    public string? Position { get; set; }
    public DateTime Start { get; set; }
    public DateTime End { get; set; }
    public string? Mentor { get; set; }
    public string? Examiner { get; set; }
    public string? Student { get; set; }
    public string? Url { get; set; }

    public string Title => Position == null ? Kind : $"{Position} · {Kind}";

    // The feed names the student only when you're the mentor or examiner.
    public string? WithWhom => Student != null ? $"Student: {Student}"
        : Mentor != null ? $"Mentor: {Mentor}"
        : Examiner != null ? $"Examiner: {Examiner}"
        : null;

    public string TimeRange => $"{Start.ToLocalTime():ddd d MMM}, {Start.ToLocalTime():HH:mm}–{End.ToLocalTime():HH:mm}";
}

// A row from "My Training History": a past mentoring session and whether its report is filed.
sealed class TrainingRecord
{
    public string Id { get; set; } = "";
    public string Position { get; set; } = "";
    public string Date { get; set; } = "";
    public string Mentor { get; set; } = "";
    public string Status { get; set; } = "";
    public string? ReportUrl { get; set; }
}

// A row from "My Exams": a practical or theory exam and its result.
sealed class ExamRecord
{
    public string Id { get; set; } = "";
    public string Type { get; set; } = "";
    public string Exam { get; set; } = "";
    public string? Position { get; set; }
    public string Result { get; set; } = "";
    public string? Score { get; set; }
    public string Date { get; set; } = "";
    public string? ReportUrl { get; set; }
}

sealed class SessionsState
{
    public string? FeedUrl { get; set; }
    public List<SessionBooking> Bookings { get; set; } = new();
    public List<TrainingRecord> History { get; set; } = new();
    public bool HistoryAvailable { get; set; } = true;
    public List<ExamRecord> Exams { get; set; } = new();
    // When exam results were first read, so results already there don't trigger alerts.
    public DateTime? ExamsChecked { get; set; }
    public DateTime? LastChecked { get; set; }
    public string? Message { get; set; }
    public bool Alerts { get; set; } = true;
    public int RemindMinutes { get; set; } = 30;
    // Sessions already reminded about, so a reminder pops up once.
    public HashSet<string> Reminded { get; set; } = new();

    public List<SessionBooking> Upcoming(DateTime nowUtc) =>
        Bookings.Where(b => b.End > nowUtc).OrderBy(b => b.Start).ToList();
}

sealed class AppState
{
    public static string DataDir { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VatsimUkWaitingListWidget");

    static string FilePath => Path.Combine(DataDir, "state.json");

    public int? X { get; set; }
    public int? Y { get; set; }
    public bool TopMost { get; set; } = true;
    public bool Hidden { get; set; }
    public int IntervalMinutes { get; set; } = 60;
    public ThemeMode Theme { get; set; } = ThemeMode.System;
    public bool NotifyChanges { get; set; } = true;
    public DateTime? LastChecked { get; set; }
    public List<WaitingListEntry> Lists { get; set; } = new();
    public SessionsState Sessions { get; set; } = new();

    public static AppState Load()
    {
        try
        {
            return JsonSerializer.Deserialize<AppState>(File.ReadAllText(FilePath)) ?? new AppState();
        }
        catch
        {
            return new AppState();
        }
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(DataDir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // Losing the saved state only costs the change arrows and window position.
        }
    }
}
