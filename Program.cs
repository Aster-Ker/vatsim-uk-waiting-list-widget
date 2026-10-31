namespace WaitingListWidget;

static class Program
{
    [STAThread]
    static void Main()
    {
        using var mutex = new Mutex(true, "VatsimUkWaitingListWidget", out bool firstInstance);
        if (!firstInstance)
            return;

        ApplicationConfiguration.Initialize();
        Application.Run(new WidgetForm());
    }
}
