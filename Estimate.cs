namespace WaitingListWidget;

// Estimates how long until you reach #1 from how fast your own position has moved.
// VATSIM UK doesn't publish list speeds, so this only knows what the widget has seen.
abstract record Estimate
{
    // Days until #1 at the current pace, and the pace in places per month.
    public sealed record Ready(double Days, double PlacesPerMonth) : Estimate;
    public sealed record Next : Estimate;
    // Not enough movement seen yet; tracking began at Since.
    public sealed record Gathering(DateTime Since) : Estimate;
    public sealed record Stalled : Estimate;

    const int WindowDays = 180;
    const double MinDays = 7;

    public static Estimate? Of(WaitingListEntry entry, DateTime nowUtc)
    {
        if (entry.Position is not int position)
            return null;
        if (position <= 1)
            return new Next();
        var samples = entry.History.OrderBy(s => s.At).ToList();
        if (samples.Count == 0)
            return null;

        // Pace over the last six months, counting the quiet weeks since the last move.
        var windowStart = nowUtc.AddDays(-WindowDays);
        var baseline = samples.LastOrDefault(s => s.At <= windowStart) ?? samples[0];
        var from = baseline.At > windowStart ? baseline.At : windowStart;
        double days = (nowUtc - from).TotalDays;
        int moved = baseline.Position - position;

        if (days < MinDays)
            return new Gathering(samples[0].At);
        if (moved <= 0)
            return moved < 0 || days >= 60 ? new Stalled() : new Gathering(samples[0].At);
        double perDay = moved / days;
        return new Ready((position - 1) / perDay, perDay * 30.44);
    }

    // Records the position whenever it changes, keeping a year of history.
    public static void Record(WaitingListEntry entry, DateTime nowUtc)
    {
        if (entry.Position is not int position || entry.History.LastOrDefault()?.Position == position)
            return;
        entry.History.Add(new PositionSample { At = nowUtc, Position = position });
        entry.History = entry.History.Where(s => (nowUtc - s.At).TotalDays <= 366).TakeLast(200).ToList();
    }

    public static string FormatDays(double days) => days switch
    {
        < 1.5 => "about a day",
        < 14 => $"about {Math.Round(days)} days",
        < 60 => $"about {Math.Round(days / 7)} weeks",
        < 730 => $"about {Math.Max(2, Math.Round(days / 30.44))} months",
        _ => "over 2 years",
    };
}
