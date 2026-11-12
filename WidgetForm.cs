using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;

namespace WaitingListWidget;

sealed class WidgetForm : Form
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string RunValue = "VatsimUkWaitingListWidget";
    static readonly int[] IntervalChoices = { 15, 30, 60, 180, 360 };
    static readonly TimeSpan HighlightFor = TimeSpan.FromHours(24);

    // Layout in logical (96 dpi) pixels.
    const int WidgetWidth = 340;
    const int Pad = 16;
    const int HeaderHeight = 58;
    const int RowHeight = 78;
    const int FooterHeight = 40;
    const int AlertHeight = 78;
    const int SectionLabelHeight = 28;
    const int SessionRowHeight = 50;
    const int MaxSessionRows = 3;
    static readonly int[] ReminderChoices = { 10, 15, 30, 60, 120 };

    const TextFormatFlags SingleLineText = TextFormatFlags.Left | TextFormatFlags.NoPadding | TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine;

    readonly AppState state = AppState.Load();
    readonly BrowserForm browser = new();
    readonly NotifyIcon tray = new();
    readonly ContextMenuStrip menu = new();
    readonly ToolTip toolTip = new();
    readonly System.Windows.Forms.Timer timer = new();
    // Ticks every 30 seconds for countdowns and session reminders.
    readonly System.Windows.Forms.Timer clock = new() { Interval = 30_000 };
    readonly List<(Rectangle Bounds, string Url)> sessionLinks = new();
    readonly Font titleFont, nameFont, smallFont, badgeFont, positionFont, deltaFont;

    Theme theme;
    bool nativeCorners;
    FetchStatus? status;
    string? errorMessage;
    bool busy;
    bool exiting;
    Point? pressPoint;
    bool dragging;
    Rectangle alertButton;
    Icon? trayIcon;

    public WidgetForm()
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        DoubleBuffered = true;
        TopMost = state.TopMost;
        Text = "VATSIM UK waiting lists";

        titleFont = Theme.Font(11.25f, semibold: true);
        nameFont = Theme.Font(10.5f);
        smallFont = Theme.Font(9f);
        badgeFont = Theme.Font(8.25f, semibold: true);
        positionFont = Theme.Font(15f, semibold: true);
        deltaFont = Theme.Font(8.5f, semibold: true);

        theme = Theme.Resolve(state.Theme);
        BackColor = theme.Card;

        ClientSize = new Size(S(WidgetWidth), S(HeaderHeight + RowHeight + FooterHeight));
        Location = InitialLocation();

        BuildMenu();
        ContextMenuStrip = menu;

        tray.Text = Text;
        tray.ContextMenuStrip = menu;
        tray.Visible = true;
        tray.DoubleClick += (_, _) => SetWidgetVisible(!Visible);
        tray.BalloonTipClicked += (_, _) => SetWidgetVisible(true);

        Notifier.Init();
        Notifier.Clicked += url =>
        {
            if (!IsHandleCreated)
                return;
            BeginInvoke(() =>
            {
                if (url != null)
                    OpenUrl(url);
                else
                    SetWidgetVisible(true);
            });
        };
        clock.Tick += (_, _) =>
        {
            CheckReminders();
            Invalidate();
        };

        SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;
        timer.Tick += async (_, _) => await RefreshAsync(false);
        ApplyTheme();
    }

    protected override async void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        if (state.Hidden)
            BeginInvoke(Hide);
        UpdateLayoutSize();

        try
        {
            await browser.InitAsync();
            browser.ApplyTheme(theme);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            ShowError("Microsoft Edge WebView2 Runtime isn't installed. Get it from https://go.microsoft.com/fwlink/p/?LinkId=2124703");
            return;
        }

        timer.Interval = state.IntervalMinutes * 60_000;
        timer.Start();
        clock.Start();
        // First run: open the sign-in window straight away rather than waiting for a failed check.
        await RefreshAsync(state.LastChecked == null);
    }

    // ---------- Checking ----------

    async Task RefreshAsync(bool showSignIn)
    {
        if (busy && !showSignIn)
            return;
        if (busy)
        {
            // A check is already running; it will pick up the result of signing in.
            await browser.FetchAsync(true);
            return;
        }

        busy = true;
        Invalidate();
        FetchResult result;
        try
        {
            result = await browser.FetchAsync(showSignIn);
        }
        catch (Exception ex)
        {
            result = FetchResult.Fail(ex.Message);
        }
        // Bookings and training history use the same sign-in, so skip them when it has expired.
        if (result.Status != FetchStatus.LoginRequired)
        {
            try
            {
                var sessions = await SessionsService.SyncAsync(browser, state.Sessions);
                if (!sessions.LoginRequired)
                {
                    state.Sessions = sessions.State;
                    if (state.Sessions.Alerts)
                    {
                        foreach (var notice in sessions.Notices)
                            Notifier.Show(tray, notice.Title, notice.Body, url: notice.Url);
                    }
                }
            }
            catch (Exception ex)
            {
                state.Sessions.Message = "Couldn't check sessions: " + ex.Message;
            }
        }

        busy = false;
        Apply(result);
        CheckReminders();
    }

    // Pops up an alarm reminder once per session, the chosen number of minutes before it starts.
    void CheckReminders()
    {
        var sessions = state.Sessions;
        if (!sessions.Alerts)
            return;
        var now = DateTime.UtcNow;
        foreach (var booking in sessions.Upcoming(now))
        {
            if (sessions.Reminded.Contains(booking.Uid) || now < booking.Start.AddMinutes(-sessions.RemindMinutes) || now >= booking.Start)
                continue;
            sessions.Reminded.Add(booking.Uid);
            Notifier.ShowReminder(tray, $"{booking.Title} in {Relative(booking.Start - now)}",
                string.Join(" · ", new[] { booking.TimeRange, booking.WithWhom }.Where(s => s != null)));
        }
        state.Save();
    }

    static string Relative(TimeSpan span)
    {
        int minutes = (int)Math.Ceiling(Math.Max(0, span.TotalMinutes));
        if (minutes < 60)
            return $"{minutes} min";
        if (minutes < 24 * 60)
            return minutes % 60 == 0 ? $"{minutes / 60} h" : $"{minutes / 60} h {minutes % 60} min";
        int days = minutes / (24 * 60);
        return days == 1 ? "1 day" : $"{days} days";
    }

    void Apply(FetchResult result)
    {
        var previousStatus = status;
        status = result.Status;
        errorMessage = result.Message;

        switch (result.Status)
        {
            case FetchStatus.Ok:
                var changes = Merge(result.Lists);
                state.LastChecked = DateTime.Now;
                state.Save();
                if (changes.Count > 0 && state.NotifyChanges)
                {
                    string title = changes.Count == 1 ? "Waiting list update" : $"{changes.Count} waiting list updates";
                    Notifier.Show(tray, title, string.Join(Environment.NewLine, changes));
                }
                break;

            case FetchStatus.LoginRequired when previousStatus != FetchStatus.LoginRequired:
                Notifier.Show(tray, "Sign in to VATSIM UK",
                    "Your vatsim.uk session has expired. Open the widget and choose Sign in.", warning: true);
                break;
        }

        toolTip.SetToolTip(this, result.Status == FetchStatus.Error ? errorMessage : null);
        UpdateLayoutSize();
        UpdateTrayIcon();
        Invalidate();
    }

    // Copies the fresh lists into state and describes what changed since the last check.
    List<string> Merge(List<WaitingListEntry> fresh)
    {
        var changes = new List<string>();
        bool firstCheck = state.LastChecked == null;
        var old = state.Lists.ToDictionary(l => l.Key);

        foreach (var entry in fresh)
        {
            if (!old.TryGetValue(entry.Key, out var before))
            {
                Estimate.Record(entry, DateTime.UtcNow);
                if (!firstCheck)
                    changes.Add($"Added to {entry.Name}" + (entry.Position is int p ? $" at #{p}" : ""));
                continue;
            }

            old.Remove(entry.Key);
            entry.Change = before.Change;
            entry.ChangedAt = before.ChangedAt;
            entry.History = before.History;
            entry.JoinPosition = before.JoinPosition;
            Estimate.Record(entry, DateTime.UtcNow);
            if (before.Position is int was && entry.Position is int now && was != now)
            {
                entry.Change = was - now;
                entry.ChangedAt = DateTime.Now;
                string direction = was > now ? $"up {was - now}" : $"down {now - was}";
                changes.Add($"{entry.Name}: #{was} → #{now} ({direction})");
            }
            else if (before.Position == null && entry.Position is int first)
            {
                entry.ChangedAt = DateTime.Now;
                changes.Add($"{entry.Name}: you now have a position, #{first}");
            }

            if (before.TheoryPassed == false && entry.TheoryPassed == true)
                changes.Add($"{entry.Name}: theory exam now shows as passed");
        }

        foreach (var removed in old.Values)
            changes.Add($"No longer on {removed.Name}");

        state.Lists = fresh;
        return changes;
    }

    // ---------- Theme ----------

    void ApplyTheme()
    {
        theme = Theme.Resolve(state.Theme);
        BackColor = theme.Card;
        if (IsHandleCreated)
            nativeCorners = Theme.TryUseNativeCorners(Handle, theme.Border);
        if (browser.IsHandleCreated)
            browser.ApplyTheme(theme);
        menu.Renderer = new ToolStripProfessionalRenderer(new MenuColors(theme)) { RoundedEdges = false };
        SetMenuColors(menu.Items);
        UpdateLayoutSize();
        UpdateTrayIcon();
        Invalidate();
    }

    void OnUserPreferenceChanged(object? sender, UserPreferenceChangedEventArgs e)
    {
        if (e.Category == UserPreferenceCategory.General && IsHandleCreated)
            BeginInvoke(ApplyTheme);
    }

    void SetMenuColors(ToolStripItemCollection items)
    {
        foreach (ToolStripItem item in items)
        {
            item.ForeColor = theme.Foreground;
            if (item is ToolStripMenuItem { DropDown: ToolStripDropDownMenu dropDown } menuItem)
            {
                dropDown.Renderer = menu.Renderer;
                SetMenuColors(menuItem.DropDownItems);
            }
        }
    }

    sealed class MenuColors(Theme t) : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => t.Card;
        public override Color ImageMarginGradientBegin => t.Card;
        public override Color ImageMarginGradientMiddle => t.Card;
        public override Color ImageMarginGradientEnd => t.Card;
        public override Color MenuBorder => t.Border;
        public override Color MenuItemBorder => t.Secondary;
        public override Color MenuItemSelected => t.Secondary;
        public override Color MenuItemSelectedGradientBegin => t.Secondary;
        public override Color MenuItemSelectedGradientEnd => t.Secondary;
        public override Color SeparatorDark => t.Border;
        public override Color SeparatorLight => t.Card;
        public override Color CheckBackground => t.Secondary;
        public override Color CheckSelectedBackground => t.Secondary;
        public override Color CheckPressedBackground => t.Secondary;
        public override Color ButtonSelectedBorder => t.Border;
    }

    // ---------- Drawing ----------

    bool ShowsAlert => !busy && status is FetchStatus.LoginRequired or FetchStatus.Error;

    void UpdateLayoutSize()
    {
        int rows = Math.Max(1, state.Lists.Count);
        int sessionRows = Math.Max(1, Math.Min(MaxSessionRows, state.Sessions.Upcoming(DateTime.UtcNow).Count));
        int height = S(HeaderHeight + 2 * SectionLabelHeight + sessionRows * SessionRowHeight + rows * RowHeight
            + (ShowsAlert ? AlertHeight : FooterHeight));
        if (ClientSize.Height != height)
            ClientSize = new Size(ClientSize.Width, height);
        Region = nativeCorners ? null : new Region(RoundedPath(new Rectangle(Point.Empty, ClientSize), S(12)));
        KeepOnScreen();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(theme.Card);
        int width = ClientSize.Width;
        int pad = S(Pad);

        // Header: title, subtitle and a status badge, like a PanelUI card header.
        TextRenderer.DrawText(g, "VATSIM UK", titleFont, new Point(pad, S(12)), theme.Foreground, TextFormatFlags.NoPadding);
        TextRenderer.DrawText(g, "Training and waiting lists", smallFont, new Point(pad, S(34)), theme.MutedForeground, TextFormatFlags.NoPadding);
        var (badgeText, badgeRole) = StatusBadge();
        DrawBadge(g, badgeText, badgeRole.Foreground, badgeRole.Tint, width - pad, S(HeaderHeight / 2), alignRight: true, dot: badgeRole.Solid);

        int y = S(HeaderHeight);
        using var divider = new Pen(theme.Border);
        g.DrawLine(divider, 0, y, width, y);

        y = DrawSessions(g, y, width, divider);
        DrawSectionLabel(g, "WAITING LISTS", new Rectangle(pad, y, width - 2 * pad, S(SectionLabelHeight)));
        y += S(SectionLabelHeight);

        if (state.Lists.Count == 0)
        {
            string text = status == FetchStatus.Ok ? "You aren't on any waiting lists." : busy || status == null ? "Checking vatsim.uk…" : "No data yet.";
            TextRenderer.DrawText(g, text, smallFont, new Rectangle(pad, y, width - 2 * pad, S(RowHeight)), theme.MutedForeground,
                TextFormatFlags.VerticalCenter | SingleLineText);
            y += S(RowHeight);
            g.DrawLine(divider, 0, y, width, y);
        }

        foreach (var list in state.Lists)
        {
            DrawRow(g, list, new Rectangle(0, y + 1, width, S(RowHeight) - 1));
            y += S(RowHeight);
            g.DrawLine(divider, 0, y, width, y);
        }

        alertButton = Rectangle.Empty;
        if (ShowsAlert)
            DrawAlert(g, new Rectangle(pad, y + S(12), width - 2 * pad, S(AlertHeight - 24)));
        else
            DrawFooter(g, new Rectangle(pad, y, width - 2 * pad, S(FooterHeight)));

        if (!nativeCorners)
        {
            using var border = new Pen(theme.Border);
            using var outline = RoundedPath(new Rectangle(0, 0, width - 1, ClientSize.Height - 1), S(12));
            g.DrawPath(border, outline);
        }
    }

    void DrawSectionLabel(Graphics g, string text, Rectangle r) =>
        TextRenderer.DrawText(g, text, badgeFont, new Rectangle(r.Left, r.Top + S(10), r.Width, r.Height - S(10)), theme.MutedForeground,
            TextFormatFlags.Top | SingleLineText);

    // The next few bookings with a starts-in / in-progress badge. Clicking one opens it on vatsim.uk.
    int DrawSessions(Graphics g, int y, int width, Pen divider)
    {
        int pad = S(Pad);
        sessionLinks.Clear();
        DrawSectionLabel(g, "NEXT SESSIONS", new Rectangle(pad, y, width - 2 * pad, S(SectionLabelHeight)));
        y += S(SectionLabelHeight);

        var now = DateTime.UtcNow;
        var upcoming = state.Sessions.Upcoming(now).Take(MaxSessionRows).ToList();
        if (upcoming.Count == 0)
        {
            string text = state.Sessions.FeedUrl == null && state.Sessions.Message != null ? state.Sessions.Message
                : state.Sessions.LastChecked == null ? "Checking your bookings…"
                : "No upcoming bookings.";
            TextRenderer.DrawText(g, text, smallFont, new Rectangle(pad, y, width - 2 * pad, S(SessionRowHeight)), theme.MutedForeground,
                TextFormatFlags.VerticalCenter | SingleLineText);
            y += S(SessionRowHeight);
            g.DrawLine(divider, 0, y, width, y);
            return y;
        }

        foreach (var booking in upcoming)
        {
            var r = new Rectangle(0, y + 1, width, S(SessionRowHeight) - 1);
            var (badge, role) = now >= booking.Start ? ($"Live · ends {booking.End.ToLocalTime():HH:mm}", theme.Success)
                : now >= booking.Start.AddMinutes(-state.Sessions.RemindMinutes) ? ($"in {Relative(booking.Start - now)}", theme.Warning)
                : ($"in {Relative(booking.Start - now)}", theme.Info);
            if (role == theme.Success || role == theme.Warning)
            {
                using var tint = new SolidBrush(role.Tint);
                g.FillRectangle(tint, r);
            }
            var badgeRect = DrawBadge(g, badge, role.Foreground, role == theme.Info ? theme.Secondary : role.Tint,
                r.Right - pad, r.Top + r.Height / 2, alignRight: true, dot: role == theme.Info ? null : role.Solid);
            int textWidth = badgeRect.Left - pad - S(8);
            TextRenderer.DrawText(g, booking.Position ?? booking.Kind, nameFont, new Rectangle(pad, r.Top + S(7), textWidth, S(20)), theme.Foreground, SingleLineText);
            TextRenderer.DrawText(g, $"{booking.Kind} · {booking.Start.ToLocalTime():ddd HH:mm}–{booking.End.ToLocalTime():HH:mm}", smallFont,
                new Rectangle(pad, r.Top + S(27), textWidth, S(18)), theme.MutedForeground, SingleLineText);
            if (booking.Url != null)
                sessionLinks.Add((r, booking.Url));
            y += S(SessionRowHeight);
            g.DrawLine(divider, 0, y, width, y);
        }
        return y;
    }

    (string, Theme.Role) StatusBadge()
    {
        if (busy)
            return ("Checking", theme.Info);
        return status switch
        {
            FetchStatus.Ok => ("Up to date", theme.Success),
            FetchStatus.LoginRequired => ("Sign in", theme.Warning),
            FetchStatus.Error => ("Error", theme.Destructive),
            _ => ("Starting", theme.Info),
        };
    }

    void DrawRow(Graphics g, WaitingListEntry list, Rectangle r)
    {
        int pad = S(Pad);
        bool recent = list.ChangedAt is DateTime at && DateTime.Now - at < HighlightFor;
        if (recent)
        {
            var role = list.Change < 0 ? theme.Destructive : theme.Success;
            using var tint = new SolidBrush(role.Tint);
            g.FillRectangle(tint, r);
            using var accent = new SolidBrush(role.Solid);
            g.FillRectangle(accent, r.Left, r.Top, S(3), r.Height);
        }

        // Right column: the position as a KPI value with its change underneath.
        string position = list.Position is int p ? $"#{p}" : "—";
        var posSize = TextRenderer.MeasureText(g, position, positionFont, Size.Empty, TextFormatFlags.NoPadding);
        int right = r.Right - pad;
        TextRenderer.DrawText(g, position, positionFont, new Point(right - posSize.Width, r.Top + S(9)),
            list.Position == null ? theme.MutedForeground : theme.Foreground, TextFormatFlags.NoPadding);

        (string text, Color colour, Font font) sub = list switch
        {
            { Position: null } => ("Not ranked", theme.MutedForeground, smallFont),
            { Change: > 0 } => ($"▲ {list.Change}", theme.Success.Foreground, deltaFont),
            { Change: < 0 } => ($"▼ {-list.Change}", theme.Destructive.Foreground, deltaFont),
            _ => ("No change", theme.MutedForeground, smallFont),
        };
        var subSize = TextRenderer.MeasureText(g, sub.text, sub.font, Size.Empty, TextFormatFlags.NoPadding);
        TextRenderer.DrawText(g, sub.text, sub.font, new Point(right - subSize.Width, r.Top + S(37)), sub.colour, TextFormatFlags.NoPadding);

        // Left column: list name, then department and theory badges and the join date.
        int textRight = right - Math.Max(posSize.Width, subSize.Width) - S(12);
        TextRenderer.DrawText(g, list.Name, nameFont, new Rectangle(pad, r.Top + S(9), textRight - pad, S(22)), theme.Foreground, SingleLineText);

        int centerY = r.Top + S(43);
        int x = pad;
        x = DrawBadge(g, list.Department, theme.Foreground, theme.Secondary, x, centerY).Right + S(6);
        if (list.TheoryPassed is bool passed)
        {
            var role = passed ? theme.Success : theme.Warning;
            x = DrawBadge(g, passed ? "Theory ✓" : "Theory ✗", role.Foreground, role.Tint, x, centerY).Right + S(8);
        }
        if (list.Joined.Length > 0 && textRight - x > S(30))
        {
            TextRenderer.DrawText(g, "Joined " + list.Joined, smallFont, new Rectangle(x, centerY - S(10), textRight - x, S(20)),
                theme.MutedForeground, TextFormatFlags.VerticalCenter | SingleLineText);
        }

        // Third line: how long until #1 at your current pace, or why there's no estimate yet.
        (string? text, Color colour) estimate = Estimate.Of(list, DateTime.UtcNow) switch
        {
            Estimate.Next => ("You're next for a training place", theme.Success.Foreground),
            Estimate.Stalled => ("No movement lately, so no estimate yet", theme.MutedForeground),
            Estimate.Gathering => ("Estimate soon, or right-click → Position when I joined", theme.MutedForeground),
            Estimate.Ready e => ($"{Capitalise(Estimate.FormatDays(e.Days))} to #1 · ~{Math.Max(1, Math.Round(e.PlacesPerMonth))} places a month{(e.FromJoin ? " since joining" : "")}", theme.Info.Foreground),
            _ => (null, theme.MutedForeground),
        };
        if (estimate.text != null)
        {
            TextRenderer.DrawText(g, estimate.text, smallFont, new Rectangle(pad, r.Top + S(56), right - pad, S(18)), estimate.colour, SingleLineText);
        }
    }

    static string Capitalise(string s) => s.Length == 0 ? s : char.ToUpperInvariant(s[0]) + s[1..];

    Rectangle DrawBadge(Graphics g, string text, Color foreground, Color background, int x, int centerY, bool alignRight = false, Color? dot = null)
    {
        var size = TextRenderer.MeasureText(g, text, badgeFont, Size.Empty, TextFormatFlags.NoPadding);
        int dotSpace = dot == null ? 0 : S(10);
        int width = size.Width + S(14) + dotSpace;
        int height = S(20);
        var r = new Rectangle(alignRight ? x - width : x, centerY - height / 2, width, height);

        using (var path = RoundedPath(r, S(6)))
        using (var fill = new SolidBrush(background))
            g.FillPath(fill, path);

        if (dot is Color dotColour)
        {
            using var brush = new SolidBrush(dotColour);
            int d = S(6);
            g.FillEllipse(brush, r.Left + S(7), centerY - d / 2, d, d);
        }
        var textRect = new Rectangle(r.Left + dotSpace, r.Top, r.Width - dotSpace, r.Height);
        TextRenderer.DrawText(g, text, badgeFont, textRect, foreground,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
        return r;
    }

    // An alert block with an outline button, like PanelUI's "Update available" alert.
    void DrawAlert(Graphics g, Rectangle r)
    {
        bool login = status == FetchStatus.LoginRequired;
        var role = login ? theme.Warning : theme.Destructive;
        string title = login ? "Sign in needed" : "Couldn't check vatsim.uk";
        string body = login ? "Your vatsim.uk session has expired." : errorMessage ?? "Something went wrong.";
        string action = login ? "Sign in" : "Retry";

        using (var path = RoundedPath(r, S(10)))
        using (var fill = new SolidBrush(role.Tint))
            g.FillPath(fill, path);

        var buttonText = TextRenderer.MeasureText(g, action, badgeFont, Size.Empty, TextFormatFlags.NoPadding);
        alertButton = new Rectangle(r.Right - S(12) - buttonText.Width - S(24), r.Top + (r.Height - S(30)) / 2, buttonText.Width + S(24), S(30));
        using (var path = RoundedPath(alertButton, S(8)))
        using (var fill = new SolidBrush(theme.Card))
        using (var border = new Pen(theme.Border))
        {
            g.FillPath(fill, path);
            g.DrawPath(border, path);
        }
        TextRenderer.DrawText(g, action, badgeFont, alertButton, theme.Foreground,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);

        int textWidth = alertButton.Left - r.Left - S(24);
        TextRenderer.DrawText(g, title, badgeFont, new Rectangle(r.Left + S(12), r.Top + S(11), textWidth, S(18)), role.Foreground, SingleLineText);
        TextRenderer.DrawText(g, body, smallFont, new Rectangle(r.Left + S(12), r.Top + S(30), textWidth, S(18)), theme.MutedForeground, SingleLineText);
    }

    void DrawFooter(Graphics g, Rectangle r)
    {
        string text = busy ? "Checking vatsim.uk…"
            : state.LastChecked is DateTime t ? $"Checked {t:ddd HH:mm} · every {FormatInterval(state.IntervalMinutes)}"
            : "";
        if (!state.NotifyChanges && !busy)
            text += " · notifications off";
        TextRenderer.DrawText(g, text, smallFont, r, theme.MutedForeground, TextFormatFlags.VerticalCenter | SingleLineText);
    }

    int S(int logical) => LogicalToDeviceUnits(logical);

    static GraphicsPath RoundedPath(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        int d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        path.AddArc(r.Left, r.Top, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Top, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    // ---------- Tray icon ----------

    void UpdateTrayIcon()
    {
        int? best = state.Lists.Where(l => l.Position != null).Min(l => l.Position);
        bool login = status == FetchStatus.LoginRequired;
        string label = login ? "!" : best?.ToString() ?? "WL";

        // Contrast with the taskbar, which has its own light/dark setting.
        bool lightTaskbar = Theme.TaskbarIsLight;
        Color fill = login ? Theme.Light.Warning.Solid : lightTaskbar ? Theme.Light.Primary : Theme.Dark.Primary;
        Color ink = login ? Color.FromArgb(23, 23, 23) : lightTaskbar ? Theme.Light.PrimaryForeground : Theme.Dark.PrimaryForeground;

        var size = SystemInformation.SmallIconSize;
        using var bitmap = new Bitmap(size.Width, size.Height);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
            using var brush = new SolidBrush(fill);
            using var path = RoundedPath(new Rectangle(0, 0, size.Width - 1, size.Height - 1), size.Width / 4);
            g.FillPath(brush, path);
            float fontSize = size.Height * (label.Length switch { 1 => 0.66f, 2 => 0.56f, _ => 0.42f });
            using var font = new Font("Segoe UI", fontSize, FontStyle.Bold, GraphicsUnit.Pixel);
            using var format = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            using var inkBrush = new SolidBrush(ink);
            g.DrawString(label, font, inkBrush, new RectangleF(0, 0, size.Width, size.Height + 1), format);
        }

        var handle = bitmap.GetHicon();
        var icon = (Icon)Icon.FromHandle(handle).Clone();
        DestroyIcon(handle);
        tray.Icon = icon;
        browser.Icon = icon;
        trayIcon?.Dispose();
        trayIcon = icon;

        string tip = best is int b ? $"VATSIM UK waiting lists: best position #{b}" : "VATSIM UK waiting lists";
        tray.Text = tip.Length > 63 ? tip[..63] : tip;
    }

    [DllImport("user32.dll")]
    static extern bool DestroyIcon(IntPtr handle);

    // ---------- Menu ----------

    void BuildMenu()
    {
        menu.Items.Add("Check now", null, async (_, _) => await RefreshAsync(false));
        menu.Items.Add("Sign in…", null, async (_, _) => await RefreshAsync(true));
        menu.Items.Add("Open waiting lists in browser", null, (_, _) => OpenInBrowser());
        menu.Items.Add(new ToolStripSeparator());

        var showItem = new ToolStripMenuItem("Show widget", null, (_, _) => SetWidgetVisible(!Visible));
        var topItem = new ToolStripMenuItem("Always on top", null, (_, _) =>
        {
            state.TopMost = TopMost = !TopMost;
            state.Save();
        });

        var themeMenu = new ToolStripMenuItem("Theme");
        foreach (var mode in Enum.GetValues<ThemeMode>())
        {
            themeMenu.DropDownItems.Add(new ToolStripMenuItem(mode == ThemeMode.System ? "Match Windows" : mode.ToString(), null, (_, _) =>
            {
                state.Theme = mode;
                state.Save();
                ApplyTheme();
            }) { Tag = mode });
        }

        var intervalMenu = new ToolStripMenuItem("Check every");
        foreach (int minutes in IntervalChoices)
        {
            intervalMenu.DropDownItems.Add(new ToolStripMenuItem(FormatInterval(minutes), null, (_, _) =>
            {
                state.IntervalMinutes = minutes;
                state.Save();
                timer.Interval = minutes * 60_000;
                Invalidate();
            }) { Tag = minutes });
        }

        var notifyMenu = new ToolStripMenuItem("Notifications");
        var notifyItem = new ToolStripMenuItem("Notify me when my position changes", null, (_, _) =>
        {
            state.NotifyChanges = !state.NotifyChanges;
            state.Save();
            Invalidate();
        });
        notifyMenu.DropDownItems.Add(notifyItem);
        notifyMenu.DropDownItems.Add("Send a test notification", null, (_, _) =>
        {
            var sample = state.Lists.FirstOrDefault(l => l.Position != null);
            string body = sample is { Position: int p }
                ? $"{sample.Name}: #{p + 2} → #{p} (up 2)"
                : "S2 Training: #14 → #12 (up 2)";
            Notifier.Show(tray, "Waiting list update (test)", body);
        });

        // Position when you joined each list, to calibrate its estimate. Rebuilt each time the menu opens.
        var joinMenu = new ToolStripMenuItem("Position when I joined");
        joinMenu.DropDownItems.Add("(no lists yet)");
        menu.Opening += (_, _) =>
        {
            joinMenu.DropDownItems.Clear();
            foreach (var list in state.Lists.Where(l => l.Position != null && Estimate.JoinedAt(l.Joined) != null))
            {
                var entry = list;
                string current = entry.JoinPosition is int p ? $" (#{p})" : "";
                joinMenu.DropDownItems.Add($"{entry.Name}{current}…", null, (_, _) => AskJoinPosition(entry));
            }
            if (joinMenu.DropDownItems.Count == 0)
                joinMenu.DropDownItems.Add(new ToolStripMenuItem("(no lists yet)") { Enabled = false });
            SetMenuColors(joinMenu.DropDownItems);
        };

        var startupItem = new ToolStripMenuItem("Start with Windows", null, (_, _) => SetStartWithWindows(!StartsWithWindows()));

        var sessionsMenu = new ToolStripMenuItem("Sessions");
        var alertsItem = new ToolStripMenuItem("Session alerts (reminders, bookings, feedback, exam results)", null, (_, _) =>
        {
            state.Sessions.Alerts = !state.Sessions.Alerts;
            state.Save();
        });
        var remindMenu = new ToolStripMenuItem("Remind me before");
        foreach (int minutes in ReminderChoices)
        {
            remindMenu.DropDownItems.Add(new ToolStripMenuItem(minutes < 60 ? $"{minutes} min" : minutes == 60 ? "1 hour" : $"{minutes / 60} hours", null, (_, _) =>
            {
                state.Sessions.RemindMinutes = minutes;
                state.Save();
                Invalidate();
            }) { Tag = minutes });
        }
        sessionsMenu.DropDownItems.Add(alertsItem);
        sessionsMenu.DropDownItems.Add(remindMenu);
        sessionsMenu.DropDownItems.Add(new ToolStripSeparator());
        sessionsMenu.DropDownItems.Add("Open My Training History", null, (_, _) => OpenUrl(SessionsService.HistoryUrl));
        sessionsMenu.DropDownItems.Add("Open My Exams", null, (_, _) => OpenUrl(SessionsService.ExamsUrl));
        sessionsMenu.DropDownItems.Add("Open bookings calendar", null, (_, _) => OpenUrl("https://www.vatsim.uk/calendar"));
        menu.Opening += (_, _) =>
        {
            alertsItem.Checked = state.Sessions.Alerts;
            foreach (ToolStripMenuItem item in remindMenu.DropDownItems)
                item.Checked = (int)item.Tag! == state.Sessions.RemindMinutes;
        };

        menu.Items.AddRange(new ToolStripItem[] { showItem, topItem, themeMenu, intervalMenu, notifyMenu, sessionsMenu, joinMenu, startupItem, new ToolStripSeparator() });
        menu.Items.Add("Sign out", null, (_, _) =>
        {
            browser.SignOut();
            Apply(FetchResult.LoginRequired);
        });
        menu.Items.Add("Exit", null, (_, _) => ExitApp());

        menu.Opening += (_, _) =>
        {
            showItem.Checked = Visible;
            topItem.Checked = TopMost;
            startupItem.Checked = StartsWithWindows();
            notifyItem.Checked = state.NotifyChanges;
            foreach (ToolStripMenuItem item in intervalMenu.DropDownItems)
                item.Checked = (int)item.Tag! == state.IntervalMinutes;
            foreach (ToolStripMenuItem item in themeMenu.DropDownItems)
                item.Checked = (ThemeMode)item.Tag! == state.Theme;
        };
    }

    // Asks for your position on the day you joined; it must be at or behind where you are now.
    void AskJoinPosition(WaitingListEntry entry)
    {
        using var dialog = new Form
        {
            Text = "Position when you joined",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterScreen,
            MinimizeBox = false,
            MaximizeBox = false,
            ShowInTaskbar = false,
            TopMost = true,
            AutoScaleMode = AutoScaleMode.Dpi,
            ClientSize = new Size(380, 170),
            BackColor = theme.Card,
            ForeColor = theme.Foreground,
            Font = Theme.Font(9.5f),
        };
        var prompt = new Label
        {
            Text = $"What number were you on {entry.Name} when you joined on {entry.Joined}? " +
                   "The estimate then measures your pace from that day.",
            Location = new Point(16, 14),
            Size = new Size(348, 56),
        };
        var number = new NumericUpDown
        {
            Minimum = Math.Max(1, entry.Position ?? 1),
            Maximum = 99999,
            Value = Math.Max(Math.Max(1, entry.Position ?? 1), entry.JoinPosition ?? entry.Position ?? 1),
            Location = new Point(16, 78),
            Width = 120,
        };
        var save = new Button { Text = "Save", DialogResult = DialogResult.OK, Location = new Point(124, 124), Width = 76 };
        var clear = new Button { Text = "Clear", DialogResult = DialogResult.Abort, Location = new Point(206, 124), Width = 76, Enabled = entry.JoinPosition != null };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, Location = new Point(288, 124), Width = 76 };
        dialog.Controls.AddRange(new Control[] { prompt, number, save, clear, cancel });
        dialog.AcceptButton = save;
        dialog.CancelButton = cancel;
        dialog.HandleCreated += (_, _) => Theme.SetDarkTitleBar(dialog.Handle, theme.IsDark);

        var answer = dialog.ShowDialog(this);
        if (answer == DialogResult.Cancel)
            return;
        entry.JoinPosition = answer == DialogResult.OK ? (int)number.Value : null;
        state.Save();
        Invalidate();
    }

    static string FormatInterval(int minutes) => minutes < 60 ? $"{minutes} min" : minutes == 60 ? "1 hour" : $"{minutes / 60} hours";

    static bool StartsWithWindows()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue(RunValue) != null;
    }

    static void SetStartWithWindows(bool enable)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enable)
            key.SetValue(RunValue, $"\"{Environment.ProcessPath ?? Application.ExecutablePath}\"");
        else
            key.DeleteValue(RunValue, false);
    }

    static void OpenInBrowser() => OpenUrl(BrowserForm.WaitingListsUrl);

    static void OpenUrl(string url) =>
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

    void SetWidgetVisible(bool visible)
    {
        if (visible)
        {
            Show();
            Activate();
        }
        else
        {
            Hide();
        }
        state.Hidden = !visible;
        state.Save();
    }

    void ShowError(string message)
    {
        status = FetchStatus.Error;
        errorMessage = message;
        UpdateLayoutSize();
        Invalidate();
        MessageBox.Show(message, Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
    }

    void ExitApp()
    {
        exiting = true;
        tray.Visible = false;
        state.Save();
        Application.Exit();
    }

    // ---------- Mouse: drag to move, click the alert button, double-click to open ----------

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button == MouseButtons.Left)
        {
            pressPoint = e.Location;
            dragging = false;
        }
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        Cursor = alertButton.Contains(e.Location) || sessionLinks.Any(l => l.Bounds.Contains(e.Location)) ? Cursors.Hand : Cursors.Default;
        if (pressPoint is not Point origin)
            return;
        if (!dragging && Math.Abs(e.X - origin.X) + Math.Abs(e.Y - origin.Y) < S(4))
            return;
        dragging = true;
        Location = new Point(Left + e.X - origin.X, Top + e.Y - origin.Y);
    }

    protected override async void OnMouseUp(MouseEventArgs e)
    {
        base.OnMouseUp(e);
        if (pressPoint == null)
            return;
        pressPoint = null;
        if (dragging)
        {
            dragging = false;
            state.X = Left;
            state.Y = Top;
            state.Save();
        }
        else if (alertButton.Contains(e.Location))
        {
            await RefreshAsync(status == FetchStatus.LoginRequired);
        }
        else if (sessionLinks.FirstOrDefault(l => l.Bounds.Contains(e.Location)) is { Url: not null } link)
        {
            OpenUrl(link.Url);
        }
    }

    protected override async void OnMouseDoubleClick(MouseEventArgs e)
    {
        base.OnMouseDoubleClick(e);
        if (alertButton.Contains(e.Location) || sessionLinks.Any(l => l.Bounds.Contains(e.Location)))
            return;
        if (status == FetchStatus.LoginRequired)
            await RefreshAsync(true);
        else
            OpenInBrowser();
    }

    // ---------- Window ----------

    Point InitialLocation()
    {
        if (state.X is int x && state.Y is int y && Screen.AllScreens.Any(s => s.WorkingArea.Contains(x + 20, y + 20)))
            return new Point(x, y);
        var area = Screen.PrimaryScreen!.WorkingArea;
        return new Point(area.Right - Width - 24, area.Bottom - Height - 24);
    }

    void KeepOnScreen()
    {
        if (!IsHandleCreated)
            return;
        var area = Screen.FromControl(this).WorkingArea;
        if (Bottom > area.Bottom)
            Top = Math.Max(area.Top, area.Bottom - Height);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        nativeCorners = Theme.TryUseNativeCorners(Handle, theme.Border);
        UpdateLayoutSize();
    }

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= 0x80;        // WS_EX_TOOLWINDOW: keep out of Alt+Tab
            cp.ClassStyle |= 0x20000;  // CS_DROPSHADOW: soft card shadow
            return cp;
        }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Alt+F4 or similar just hides the widget; Exit in the menu quits.
        if (!exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            SetWidgetVisible(false);
        }
        base.OnFormClosing(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
            tray.Dispose();
            timer.Dispose();
            clock.Dispose();
            toolTip.Dispose();
            browser.Dispose();
            trayIcon?.Dispose();
            titleFont.Dispose();
            nameFont.Dispose();
            smallFont.Dispose();
            badgeFont.Dispose();
            positionFont.Dispose();
            deltaFont.Dispose();
        }
        base.Dispose(disposing);
    }
}
