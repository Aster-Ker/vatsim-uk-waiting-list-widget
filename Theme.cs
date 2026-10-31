using System.Drawing.Text;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace WaitingListWidget;

enum ThemeMode { System, Light, Dark }

// Colour tokens from PanelUI's default "Panel" theme (https://panelui.dev), light and dark.
// Translucent tokens (border, secondary, tinted badges) are pre-blended onto the card colour
// because GDI text and fills look best on opaque colours.
sealed class Theme
{
    public required bool IsDark { get; init; }
    public required Color Card { get; init; }
    public required Color Foreground { get; init; }
    public required Color MutedForeground { get; init; }
    public required Color Border { get; init; }
    public required Color Secondary { get; init; }
    public required Color Primary { get; init; }
    public required Color PrimaryForeground { get; init; }
    public required Role Success { get; init; }
    public required Role Warning { get; init; }
    public required Role Destructive { get; init; }
    public required Role Info { get; init; }

    // A status colour: Solid for dots and fills, Foreground for text, Tint for badge and alert backgrounds.
    public sealed record Role(Color Solid, Color Foreground, Color Tint);

    public static Theme Light { get; } = Create(false);
    public static Theme Dark { get; } = Create(true);

    static Theme Create(bool dark)
    {
        var card = dark ? Hex("#191919") : Hex("#ffffff");
        float tint = dark ? 0.14f : 0.08f;
        Role role(string solid, string fg) => new(Hex(solid), Hex(fg), Blend(card, Hex(solid), tint));

        return dark
            ? new Theme
            {
                IsDark = true,
                Card = card,
                Foreground = Hex("#f5f5f5"),
                MutedForeground = Hex("#878787"),
                Border = Blend(card, Color.White, 0.08f),
                Secondary = Blend(card, Color.White, 0.08f),
                Primary = Hex("#f5f5f5"),
                PrimaryForeground = Hex("#262626"),
                Success = role("#10b981", "#34d399"),
                Warning = role("#f59e0b", "#fbbf24"),
                Destructive = role("#f15757", "#f87171"),
                Info = role("#3b82f6", "#60a5fa"),
            }
            : new Theme
            {
                IsDark = false,
                Card = card,
                Foreground = Hex("#262626"),
                MutedForeground = Hex("#686868"),
                Border = Blend(card, Color.Black, 0.08f),
                Secondary = Blend(card, Color.Black, 0.06f),
                Primary = Hex("#262626"),
                PrimaryForeground = Hex("#fafafa"),
                Success = role("#10b981", "#047857"),
                Warning = role("#f59e0b", "#b45309"),
                Destructive = role("#ef4444", "#b91c1c"),
                Info = role("#3b82f6", "#1d4ed8"),
            };
    }

    public static Theme Resolve(ThemeMode mode) => mode switch
    {
        ThemeMode.Light => Light,
        ThemeMode.Dark => Dark,
        _ => ReadPersonalize("AppsUseLightTheme") ? Light : Dark,
    };

    // The taskbar has its own light/dark setting, separate from apps.
    public static bool TaskbarIsLight => ReadPersonalize("SystemUsesLightTheme");

    static bool ReadPersonalize(string name)
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue(name) is not int value || value != 0;
    }

    static Color Hex(string hex) => ColorTranslator.FromHtml(hex);

    static Color Blend(Color under, Color over, float alpha) => Color.FromArgb(
        (int)Math.Round(under.R + (over.R - under.R) * alpha),
        (int)Math.Round(under.G + (over.G - under.G) * alpha),
        (int)Math.Round(under.B + (over.B - under.B) * alpha));

    // PanelUI is set in Inter; fall back to Windows' own UI faces when it isn't installed.
    public static Font Font(float size, bool semibold = false)
    {
        string[] candidates = semibold
            ? new[] { "Inter SemiBold", "Inter Semi Bold", "Segoe UI Variable Text Semibold", "Segoe UI Semibold" }
            : new[] { "Inter", "Segoe UI Variable Text", "Segoe UI" };
        foreach (var name in candidates)
        {
            if (InstalledFamilies.Contains(name))
                return new Font(name, size);
        }
        return new Font("Segoe UI", size, semibold ? FontStyle.Bold : FontStyle.Regular);
    }

    static readonly HashSet<string> InstalledFamilies = LoadFamilies();

    static HashSet<string> LoadFamilies()
    {
        using var fonts = new InstalledFontCollection();
        return fonts.Families.Select(f => f.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
    }

    // ---------- Window chrome ----------

    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    const int DWMWA_BORDER_COLOR = 34;
    const int DWMWCP_ROUND = 2;

    // Windows 11 draws rounded corners, a border and a shadow for us. Returns false on
    // Windows 10, where the caller clips the window itself.
    public static bool TryUseNativeCorners(IntPtr hwnd, Color border)
    {
        int round = DWMWCP_ROUND;
        if (DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref round, sizeof(int)) != 0)
            return false;
        int colorRef = border.R | border.G << 8 | border.B << 16;
        DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, ref colorRef, sizeof(int));
        return true;
    }

    public static void SetDarkTitleBar(IntPtr hwnd, bool dark)
    {
        int value = dark ? 1 : 0;
        DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref value, sizeof(int));
    }
}
