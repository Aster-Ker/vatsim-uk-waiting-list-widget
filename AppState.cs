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

    public string Key => $"{Department}|{Name}";
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
