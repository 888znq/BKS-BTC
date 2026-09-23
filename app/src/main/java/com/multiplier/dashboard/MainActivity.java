package com.multiplier.dashboard;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Thin native shell around the original single-file HTML dashboard.
 * The page itself lives untouched in assets/index.html.
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFFF6E6D0; // same as the page's --bg / theme-color
    private static final String URL_APP = "file:///android_asset/index.html";

    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private int errToasts = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Must happen before the first WebView / CookieManager is created.
        try { CookieManager.setAcceptFileSchemeCookies(true); } catch (Throwable ignored) { }

        // Draw edge-to-edge ourselves and pad by the system-bar insets, so the
        // page always gets a clean rectangle (same look as the browser version).
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        WindowInsetsControllerCompat ic =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ic.setAppearanceLightStatusBars(true);
        ic.setAppearanceLightNavigationBars(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect over USB, if ever needed
        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureWebView();
        web.loadUrl(URL_APP);
    }

    private static boolean isAggr(Uri u) {
        String h = u == null ? null : u.getHost();
        return h != null && (h.equals("aggr.trade") || h.endsWith(".aggr.trade"));
    }

    private void toastErr(String msg) {
        if (errToasts++ < 4) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }
    }

    /** Removes X-Frame-Options and CSP frame-ancestors so the aggr.trade page may be shown in the iframe. */
    private WebResourceResponse fetchFrameable(WebResourceRequest r) {
        HttpURLConnection c = null;
        try {
            String url = r.getUrl().toString();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> e : r.getRequestHeaders().entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("Accept-Encoding") || k.equalsIgnoreCase("Cookie")
                        || k.equalsIgnoreCase("Range") || k.equalsIgnoreCase("Host")) continue;
                c.setRequestProperty(k, e.getValue());
            }
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) c.setRequestProperty("Cookie", ck);

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { c.disconnect(); return null; }

            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.equals("set-cookie")) {
                    for (String sc : e.getValue()) CookieManager.getInstance().setCookie(url, sc);
                    continue;
                }
                if (lk.equals("x-frame-options") || lk.equals("content-encoding")
                        || lk.equals("content-length") || lk.equals("transfer-encoding")
                        || lk.equals("connection") || lk.equals("keep-alive")) continue;
                String val = TextUtils.join(", ", e.getValue());
                if (lk.equals("content-security-policy") || lk.equals("content-security-policy-report-only")) {
                    StringBuilder sb = new StringBuilder();
                    for (String part : val.split(";")) {
                        String p = part.trim();
                        if (p.isEmpty() || p.toLowerCase(Locale.ROOT).startsWith("frame-ancestors")) continue;
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(p);
                    }
                    if (sb.length() == 0) continue;
                    val = sb.toString();
                }
                out.put(k, val);
            }

            String mime = "text/html", enc = "utf-8";
            String ct = c.getContentType();
            if (ct != null) {
                String[] parts = ct.split(";");
                if (!parts[0].trim().isEmpty()) mime = parts[0].trim();
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                        enc = p.substring(8).replace("\"", "").trim();
                    }
                }
            }
            String reason = c.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = "OK";
            InputStream in = c.getInputStream();
            return new WebResourceResponse(mime, enc, code, reason, out, in);
        } catch (Exception ex) {
            if (c != null) c.disconnect();
            return null; // fall back to the normal WebView load
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage (lot size, zoom, split, script)
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        // The page calls the Delta REST API with custom headers; from file:// this
        // lets fetch() work regardless of the server's CORS allow-list.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);            // honour <meta viewport>
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 33) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF); // page has its own light theme
        }
        // Look like mobile Chrome (some sites, e.g. the aggr.trade iframe, dislike "; wv")
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true); // aggr.trade runs in an iframe

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (r.isForMainFrame() && !"file".equals(u.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }

            // Only the aggr.trade iframe document is touched; everything else loads normally.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                try {
                    if (!r.isForMainFrame() && isAggr(r.getUrl())
                            && "GET".equalsIgnoreCase(r.getMethod())) {
                        String accept = r.getRequestHeaders().get("Accept");
                        String path = r.getUrl().getLastPathSegment();
                        boolean doc = accept != null ? accept.contains("text/html")
                                : (path == null || !path.contains("."));
                        if (doc) return fetchFrameable(r);
                    }
                } catch (Exception ignored) { }
                return null;
            }

            // If the top pane still fails, show why (code + reason) at the bottom of the screen.
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame() && isAggr(r.getUrl())) {
                    toastErr("aggr.trade error " + e.getErrorCode() + ": " + e.getDescription());
                }
            }

            @TargetApi(Build.VERSION_CODES.O)
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                runOnUiThread(MainActivity.this::recreate);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // Element fullscreen (the page's ⛶ button uses requestFullscreen)
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCb = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.INVISIBLE);
                setBarsHidden(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                setBarsHidden(false);
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
            }

            // The page reports order/close errors with alert()
            @Override
            public boolean onJsAlert(WebView v, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });
    }

    private void setBarsHidden(boolean hidden) {
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (hidden) {
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            c.show(WindowInsetsCompat.Type.systemBars());
        }
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
