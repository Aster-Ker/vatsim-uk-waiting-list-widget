namespace WaitingListWidget;

// Estimates how long until you reach #1 from how fast your own position has moved.
// VATSIM UK doesn't publish list speeds, so this only knows what the widget has seen.
abstract record Estimate
{
    // Days until #1 at the current pace, and the pace in places per month.
    // FromJoin means the pace was measured from the position you entered for the day you joined.
    public sealed record Ready(double Days, double PlacesPerMonth, bool FromJoin = false) : Estimate;
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
        // Your join position, if entered, counts as a sample on the day you joined.
        PositionSample? join = entry.JoinPosition is int joinPosition && JoinedAt(entry.Joined) is DateTime joinedAt
            ? new PositionSample { At = joinedAt, Position = joinPosition }
            : null;
        var samples = entry.History.Append(join).OfType<PositionSample>().OrderBy(s => s.At).ToList();
        if (samples.Count == 0)
            return null;

        // Pace over the last six months, counting the quiet weeks since the last move. The join
        // position is the one known point before then, so from it the pace covers the whole wait.
        var windowStart = nowUtc.AddDays(-WindowDays);
        var baseline = samples.LastOrDefault(s => s.At <= windowStart) ?? samples[0];
        var from = ReferenceEquals(baseline, join) || baseline.At > windowStart ? baseline.At : windowStart;
        double days = (nowUtc - from).TotalDays;
        int moved = baseline.Position - position;

        if (days < MinDays)
            return new Gathering(samples[0].At);
        if (moved <= 0)
            return moved < 0 || days >= 60 ? new Stalled() : new Gathering(samples[0].At);
        double perDay = moved / days;
        return new Ready((position - 1) / perDay, perDay * 30.44, ReferenceEquals(baseline, join));
    }

    // The join date as vatsim.uk shows it, e.g. "03 Feb 2026".
    public static DateTime? JoinedAt(string joined) =>
        DateTime.TryParseExact(joined.Trim(), "dd MMM yyyy", System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.AdjustToUniversal | System.Globalization.DateTimeStyles.AssumeUniversal, out var at) ? at : null;

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
