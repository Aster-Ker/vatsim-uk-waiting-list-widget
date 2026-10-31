package io.github.asterker.waitinglist.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.asterker.waitinglist.R
import io.github.asterker.waitinglist.data.WaitingListClient
import java.net.URL

// Signs in with VATSIM SSO in a WebView. The cookies it stores are what the app sends
// later, so the app never sees your password. Closes itself once the waiting lists load.
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val hint = TextView(this).apply {
            text = "Sign in with your VATSIM account. This screen closes by itself once your waiting lists load."
            setTextColor(getColor(R.color.panel_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (finishIfDone(url)) return
                    // After SSO the site may land on the dashboard rather than the page we asked for.
                    val parsed = runCatching { URL(url) }.getOrNull() ?: return
                    if (WaitingListClient.isVatsimUk(parsed) && parsed.path.startsWith("/mship/")) {
                        view.loadUrl(WaitingListClient.WAITING_LISTS_URL)
                    }
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.panel_background))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) web.loadUrl(WaitingListClient.WAITING_LISTS_URL)
        else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun finishIfDone(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (!WaitingListClient.isWaitingListsPage(parsed) || isFinishing) return false
        CookieManager.getInstance().flush()
        setResult(Activity.RESULT_OK)
        finish()
        return true
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
