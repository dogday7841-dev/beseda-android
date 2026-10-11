package app.beseda;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.regex.Pattern;

/**
 * Оболочка для мессенджера: показывает сайт вашего сервера в окне приложения.
 * Адрес сервера вводится при первом запуске и запоминается.
 */
public class MainActivity extends Activity {

    private static final String SETUP_PAGE = "file:///android_asset/setup.html";
    private static final String ERROR_PAGE = "file:///android_asset/error.html";
    private static final String ASSET_PREFIX = "file:///android_asset/";
    private static final Pattern LOCAL_HOST =
            Pattern.compile("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$", Pattern.CASE_INSENSITIVE);

    private WebView web;
    private SharedPreferences prefs;
    private volatile boolean onLocalPage = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("beseda", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#e9edf5"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);     // файлы из assets всё равно открываются
        s.setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                onLocalPage = url != null && url.startsWith(ASSET_PREFIX);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadUrl(ERROR_PAGE);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String url = u.toString();
                if (url.startsWith(ASSET_PREFIX)) return false;
                Uri server = Uri.parse(prefs.getString("server", ""));
                boolean sameHost = server.getHost() != null && server.getHost().equals(u.getHost());
                if (sameHost) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        String server = prefs.getString("server", "");
        web.loadUrl(server.isEmpty() ? SETUP_PAGE : server);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Если открыт чат, «назад» возвращает к списку чатов. Иначе приложение сворачивается.
        String js = "(function(){var a=document.getElementById('app');"
                + "if(a&&!a.hidden&&a.getAttribute('data-view')==='chat'){document.getElementById('back').click();return 1;}"
                + "return 0;})()";
        web.evaluateJavascript(js, value -> {
            if (!"1".equals(value)) moveTaskToBack(true);
        });
    }

    /** Приводит введённый адрес к виду http(s)://хост[:порт] или возвращает null. */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (!v.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            v = (LOCAL_HOST.matcher(v).matches() ? "http://" : "https://") + v;
        }
        Uri u = Uri.parse(v);
        String scheme = u.getScheme();
        if (u.getHost() == null || scheme == null) return null;
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String out = scheme + "://" + u.getAuthority();
        return out;
    }

    /** Методы, которые вызывают страницы setup.html, error.html и мессенджер. */
    public class Bridge {
        @JavascriptInterface
        public String getServer() {
            return prefs.getString("server", "");
        }

        @JavascriptInterface
        public boolean saveServer(String url) {
            if (!onLocalPage) return false; // сохранять адрес можно только со встроенной страницы
            final String u = normalize(url);
            if (u == null) return false;
            prefs.edit().putString("server", u).apply();
            runOnUiThread(() -> web.loadUrl(u));
            return true;
        }

        @JavascriptInterface
        public void changeServer() {
            runOnUiThread(() -> web.loadUrl(SETUP_PAGE));
        }

        @JavascriptInterface
        public void retry() {
            final String server = prefs.getString("server", "");
            runOnUiThread(() -> web.loadUrl(server.isEmpty() ? SETUP_PAGE : server));
        }
    }
}
