using Microsoft.Toolkit.Uwp.Notifications;

namespace WaitingListWidget;

// Sends Windows toast notifications, which also stay in the notification centre.
// Falls back to the tray balloon if toasts aren't available on this machine.
static class Notifier
{
    static bool toastsAvailable = true;

    public static event Action? Clicked;

    public static void Init()
    {
        try
        {
            ToastNotificationManagerCompat.OnActivated += _ => Clicked?.Invoke();
        }
        catch
        {
            toastsAvailable = false;
        }
    }

    public static void Show(NotifyIcon tray, string title, string body, bool warning = false)
    {
        if (toastsAvailable)
        {
            try
            {
                new ToastContentBuilder()
                    .AddText(title)
                    .AddText(body)
                    .Show();
                return;
            }
            catch
            {
                toastsAvailable = false;
            }
        }
        tray.ShowBalloonTip(15000, title, body, warning ? ToolTipIcon.Warning : ToolTipIcon.Info);
    }
}
