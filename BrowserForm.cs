using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace WaitingListWidget;

enum FetchStatus { Ok, LoginRequired, Error }

sealed record FetchResult(FetchStatus Status, List<WaitingListEntry> Lists, string? Message = null)
{
    public static FetchResult LoginRequired { get; } = new(FetchStatus.LoginRequired, new());
    public static FetchResult Fail(string message) => new(FetchStatus.Error, new(), message);
}

// Hosts the embedded browser that reads the waiting list page. It stays hidden for
// background checks and is shown only when the user needs to sign in with VATSIM SSO.
// Cookies live in the WebView2 profile under %LOCALAPPDATA%, so one sign-in lasts
// until vatsim.uk's own session expires.
sealed class BrowserForm : Form
{
    public const string WaitingListsUrl = "https://www.vatsim.uk/mship/waiting-lists";

    // Reads the "ATC Waiting Lists" and "Pilot Waiting Lists" tables. Columns are looked
    // up by header text so the pilot table (which has no roster/theory columns) works too.
    const string ExtractScript = """
        (() => {
          const lists = [];
          let found = false;
          for (const panel of document.querySelectorAll('.panel')) {
            const heading = panel.querySelector('.panel-heading');
            const table = panel.querySelector('table');
            if (!heading || !table || !/waiting lists/i.test(heading.textContent)) continue;
            found = true;
            const department = /pilot/i.test(heading.textContent) ? 'Pilot' : 'ATC';
            const headers = [...table.querySelectorAll('thead th')].map(th => th.textContent.trim().toLowerCase());
            const col = prefix => headers.findIndex(h => h.startsWith(prefix));
            for (const row of table.querySelectorAll('tbody tr')) {
              const cells = row.querySelectorAll('td');
              if (cells.length < 3) continue;
              const text = i => (i >= 0 && cells[i] ? cells[i].textContent.trim() : '');
              const tick = i => {
                const img = i >= 0 && cells[i] ? cells[i].querySelector('img') : null;
                return img ? img.alt === 'Tick' : null;
              };
              lists.push({
                department,
                name: text(col('waiting list')),
                position: text(col('position')),
                joined: text(col('joined')),
                onRoster: tick(col('on roster')),
                theoryPassed: tick(col('theory')),
              });
            }
          }
          return { found, lists };
        })()
        """;

    sealed record ScrapedRow(string Department, string Name, string Position, string Joined, bool? OnRoster, bool? TheoryPassed);
    sealed record ScrapeResult(bool Found, List<ScrapedRow> Lists);

    readonly WebView2 web = new() { Dock = DockStyle.Fill };
    readonly Label hint = new()
    {
        Dock = DockStyle.Top,
        Height = 44,
        Padding = new Padding(14, 0, 14, 0),
        TextAlign = ContentAlignment.MiddleLeft,
        Text = "Sign in with your VATSIM account. This window closes by itself once your waiting lists load.",
    };
    TaskCompletionSource<FetchResult>? pending;
    bool interactive;
    int navigationId;

    public BrowserForm()
    {
        Text = "Sign in to VATSIM UK";
        ClientSize = new Size(560, 760);
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(web);
        Controls.Add(hint);
    }

    public void ApplyTheme(Theme theme)
    {
        BackColor = theme.Card;
        hint.BackColor = theme.Card;
        hint.ForeColor = theme.MutedForeground;
        hint.Font?.Dispose();
        hint.Font = Theme.Font(9f);
        Theme.SetDarkTitleBar(Handle, theme.IsDark);
    }

    public async Task InitAsync()
    {
        _ = Handle; // WebView2 needs a window handle even while the form stays hidden.
        var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(AppState.DataDir, "WebView2"));
        await web.EnsureCoreWebView2Async(env);
        web.CoreWebView2.Settings.AreDevToolsEnabled = false;
        web.CoreWebView2.NavigationCompleted += OnNavigationCompleted;
    }

    public Task<FetchResult> FetchAsync(bool showSignIn)
    {
        if (pending != null)
        {
            if (showSignIn)
                ShowSignIn();
            return pending.Task;
        }

        pending = new TaskCompletionSource<FetchResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        var task = pending.Task;
        if (showSignIn)
            ShowSignIn();
        else
            StartTimeout(task);
        web.CoreWebView2.Navigate(WaitingListsUrl);
        return task;
    }

    public void SignOut() => web.CoreWebView2?.CookieManager.DeleteAllCookies();

    void ShowSignIn()
    {
        interactive = true;
        Show();
        Activate();
    }

    async void StartTimeout(Task<FetchResult> task)
    {
        await Task.Delay(TimeSpan.FromSeconds(60));
        if (pending?.Task == task && !interactive)
            Complete(FetchResult.Fail("Timed out waiting for vatsim.uk"));
    }

    async void OnNavigationCompleted(object? sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        if (pending == null)
            return;

        int id = ++navigationId;
        var uri = new Uri(web.CoreWebView2.Source);
        bool onVatsimUk = uri.Host.Equals("www.vatsim.uk", StringComparison.OrdinalIgnoreCase)
                          || uri.Host.Equals("vatsim.uk", StringComparison.OrdinalIgnoreCase);
        bool onWaitingLists = onVatsimUk && uri.AbsolutePath.TrimEnd('/').Equals("/mship/waiting-lists", StringComparison.OrdinalIgnoreCase);

        if (onWaitingLists && e.IsSuccess)
        {
            var result = await ExtractAsync();
            if (interactive)
                Hide();
            Complete(result);
            return;
        }

        if (interactive)
        {
            // After SSO the site may land on the dashboard rather than the page we asked for.
            if (onVatsimUk && uri.AbsolutePath.StartsWith("/mship/", StringComparison.OrdinalIgnoreCase))
                web.CoreWebView2.Navigate(WaitingListsUrl);
            return;
        }

        if (!e.IsSuccess)
        {
            Complete(FetchResult.Fail($"Couldn't reach vatsim.uk ({e.WebErrorStatus})"));
            return;
        }

        // Somewhere other than the waiting lists, which means the session has expired.
        // Give script-driven SSO redirects a few seconds to bring us back first.
        await Task.Delay(TimeSpan.FromSeconds(8));
        if (pending != null && !interactive && id == navigationId)
            Complete(FetchResult.LoginRequired);
    }

    async Task<FetchResult> ExtractAsync()
    {
        try
        {
            var json = await web.CoreWebView2.ExecuteScriptAsync(ExtractScript);
            var scraped = JsonSerializer.Deserialize<ScrapeResult>(json, new JsonSerializerOptions(JsonSerializerDefaults.Web));
            if (scraped == null || !scraped.Found)
                return FetchResult.Fail("Couldn't find the waiting list tables. The page layout may have changed.");

            var lists = scraped.Lists.Select(r => new WaitingListEntry
            {
                Department = r.Department,
                Name = r.Name,
                Position = int.TryParse(r.Position, out int p) ? p : null,
                Joined = r.Joined,
                OnRoster = r.OnRoster,
                TheoryPassed = r.TheoryPassed,
            }).ToList();
            return new FetchResult(FetchStatus.Ok, lists);
        }
        catch (Exception ex)
        {
            return FetchResult.Fail("Couldn't read the waiting list page: " + ex.Message);
        }
    }

    void Complete(FetchResult result)
    {
        var tcs = pending;
        pending = null;
        interactive = false;
        tcs?.TrySetResult(result);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            // Closing the sign-in window just hides it, ready for next time.
            e.Cancel = true;
            Hide();
            if (interactive)
                Complete(FetchResult.LoginRequired);
        }
        base.OnFormClosing(e);
    }
}
