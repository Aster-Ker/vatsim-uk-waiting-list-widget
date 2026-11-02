using Microsoft.Toolkit.Uwp.Notifications;

namespace WaitingListWidget;

// Sends Windows toast notifications, which also stay in the notification centre.
// Falls back to the tray balloon if toasts aren't available on this machine.
static class Notifier
{
    static bool toastsAvailable = true;

    // Carries the URL to open (a mentor report), or null to show the widget.
    public static event Action<string?>? Clicked;

    public static void Init()
    {
        try
        {
            ToastNotificationManagerCompat.OnActivated += e =>
            {
                var args = ToastArguments.Parse(e.Argument);
                Clicked?.Invoke(args.Contains("url") ? args["url"] : null);
            };
        }
        catch
        {
            toastsAvailable = false;
        }
    }

    public static void Show(NotifyIcon tray, string title, string body, bool warning = false, string? url = null)
    {
        if (toastsAvailable)
        {
            try
            {
                var builder = new ToastContentBuilder()
                    .AddText(title)
                    .AddText(body);
                if (url != null)
                    builder.AddArgument("url", url);
                builder.Show();
                return;
            }
            catch
            {
                toastsAvailable = false;
            }
        }
        tray.ShowBalloonTip(15000, title, body, warning ? ToolTipIcon.Warning : ToolTipIcon.Info);
    }

    // A session reminder that rings with the alarm sound and stays on screen until dismissed.
    public static void ShowReminder(NotifyIcon tray, string title, string body)
    {
        if (toastsAvailable)
        {
            try
            {
                new ToastContentBuilder()
                    .SetToastScenario(ToastScenario.Alarm)
                    .AddText(title)
                    .AddText(body)
                    .AddAudio(new Uri("ms-winsoundevent:Notification.Looping.Alarm"), loop: true)
                    .AddButton(new ToastButtonDismiss("Dismiss"))
                    .Show();
                return;
            }
            catch
            {
                toastsAvailable = false;
            }
        }
        tray.ShowBalloonTip(30000, title, body, ToolTipIcon.Warning);
    }
}
