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
    // A page to load and the script that reads it. Pages other than IsTarget mean the session has expired.
    public sealed record PageJob(string Url, Func<Uri, bool> IsTarget, string Script);

    // Json is the script's result. HttpStatus is set when the target page itself returned an error.
    public sealed record PageResult(FetchStatus Status, string? Json = null, int HttpStatus = 0, string? Message = null);

    TaskCompletionSource<PageResult>? pending;
    PageJob? job;
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

    public async Task<FetchResult> FetchAsync(bool showSignIn)
    {
        var page = await LoadAsync(new PageJob(WaitingListsUrl, IsWaitingListsPage, ExtractScript), showSignIn);
        return page.Status switch
        {
            FetchStatus.Ok => ParseLists(page.Json!),
            FetchStatus.LoginRequired => FetchResult.LoginRequired,
            _ => FetchResult.Fail(page.Message ?? "Couldn't check vatsim.uk"),
        };
    }

    public Task<PageResult> LoadAsync(PageJob newJob, bool showSignIn = false)
    {
        if (pending != null)
        {
            if (showSignIn)
                ShowSignIn();
            return pending.Task;
        }

        job = newJob;
        pending = new TaskCompletionSource<PageResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        var task = pending.Task;
        if (showSignIn)
            ShowSignIn();
        else
            StartTimeout(task);
        web.CoreWebView2.Navigate(newJob.Url);
        return task;
    }

    public void SignOut() => web.CoreWebView2?.CookieManager.DeleteAllCookies();

    public static bool IsVatsimUk(Uri uri) =>
        uri.Host.Equals("www.vatsim.uk", StringComparison.OrdinalIgnoreCase)
        || uri.Host.Equals("vatsim.uk", StringComparison.OrdinalIgnoreCase);

    static bool IsWaitingListsPage(Uri uri) =>
        IsVatsimUk(uri) && uri.AbsolutePath.TrimEnd('/').Equals("/mship/waiting-lists", StringComparison.OrdinalIgnoreCase);

    void ShowSignIn()
    {
        interactive = true;
        Show();
        Activate();
    }

    async void StartTimeout(Task<PageResult> task)
    {
        await Task.Delay(TimeSpan.FromSeconds(60));
        if (pending?.Task == task && !interactive)
            Complete(new PageResult(FetchStatus.Error, Message: "Timed out waiting for vatsim.uk"));
    }

    async void OnNavigationCompleted(object? sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        if (pending == null || job == null)
            return;

        int id = ++navigationId;
        var uri = new Uri(web.CoreWebView2.Source);

        if (job.IsTarget(uri))
        {
            if (e.HttpStatusCode >= 400)
            {
                Complete(new PageResult(FetchStatus.Error, HttpStatus: e.HttpStatusCode, Message: $"vatsim.uk returned HTTP {e.HttpStatusCode}"));
                return;
            }
            if (e.IsSuccess)
            {
                string? json = null;
                string? error = null;
                try
                {
                    json = await web.CoreWebView2.ExecuteScriptAsync(job.Script);
                }
                catch (Exception ex)
                {
                    error = "Couldn't read the vatsim.uk page: " + ex.Message;
                }
                if (interactive)
                    Hide();
                Complete(error == null ? new PageResult(FetchStatus.Ok, json) : new PageResult(FetchStatus.Error, Message: error));
                return;
            }
        }

        if (interactive)
        {
            // After SSO the site may land on the dashboard rather than the page we asked for.
            if (IsVatsimUk(uri) && uri.AbsolutePath.StartsWith("/mship/", StringComparison.OrdinalIgnoreCase))
                web.CoreWebView2.Navigate(job.Url);
            return;
        }

        if (!e.IsSuccess)
        {
            Complete(new PageResult(FetchStatus.Error, Message: $"Couldn't reach vatsim.uk ({e.WebErrorStatus})"));
            return;
        }

        // Somewhere other than the page we asked for, which means the session has expired.
        // Give script-driven SSO redirects a few seconds to bring us back first.
        await Task.Delay(TimeSpan.FromSeconds(8));
        if (pending != null && !interactive && id == navigationId)
            Complete(new PageResult(FetchStatus.LoginRequired));
    }

    static FetchResult ParseLists(string json)
    {
        try
        {
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

    void Complete(PageResult result)
    {
        var tcs = pending;
        pending = null;
        job = null;
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
                Complete(new PageResult(FetchStatus.LoginRequired));
        }
        base.OnFormClosing(e);
    }
}
