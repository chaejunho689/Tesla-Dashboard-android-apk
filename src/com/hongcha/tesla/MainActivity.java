package com.hongcha.tesla;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.graphics.Insets;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.graphics.Color;

public class MainActivity extends Activity {

    private String URL;
    private String HOST;

    private static final int MAX_RETRY = 25;     // 최대 재시도
    private static final long RETRY_MS = 1500;   // 재시도 간격

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retries = 0;
    private boolean loaded = false;
    private boolean needReload = false;   // 백그라운드 복귀 시 재연결 여부

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        HOST = getString(R.string.bridge_base).replaceFirst("https?://", "");
        URL  = getString(R.string.bridge_base) + "/?key=" + getString(R.string.bridge_key);

        // Android 13+ 알림 권한 요청, 그리고 백그라운드 상태 감시 서비스 시작
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
        askIgnoreBatteryOptimizations();
        TeslaWatchService.start(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0e0f11"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req != null && !req.isForMainFrame()) return;   // 메인 페이지 실패만
                scheduleRetry();
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains(HOST)) {            // 대시보드 로드 성공
                    loaded = true;
                    retries = 0;
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        // APK 등 파일 다운로드 처리 (WebView 기본 동작 없음 → DownloadManager 위임)
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    req.setAllowedOverMetered(true);
                    req.setAllowedOverRoaming(true);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(req);
                        Toast.makeText(MainActivity.this,
                                fileName + " 다운로드 시작 (알림 확인)", Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "다운로드 실패: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        setContentView(web);

        if (savedInstanceState == null) {
            showConnecting();
            web.loadUrl(URL);
        }
    }

    private void showConnecting() {
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'><style>"
            + "html,body{height:100%;margin:0;background:#000;display:flex;flex-direction:column;"
            + "align-items:center;justify-content:center;font-family:-apple-system,Roboto,sans-serif}"
            + ".spinner{width:52px;height:52px;border:4px solid rgba(255,255,255,.12);"
            + "border-top-color:#e82127;border-radius:50%;animation:spin .9s linear infinite}"
            + "@keyframes spin{to{transform:rotate(360deg)}}"
            + ".txt{margin-top:26px;color:#8a8f96;font-size:13px;font-weight:600;letter-spacing:4px}"
            + ".dot{animation:bl 1.4s steps(1) infinite}.dot:nth-child(2){animation-delay:.35s}"
            + ".dot:nth-child(3){animation-delay:.7s}@keyframes bl{0%,60%{opacity:0}30%{opacity:1}}"
            + "</style></head><body><div class='spinner'></div>"
            + "<div class='txt'>LOADING<span class='dot'>.</span><span class='dot'>.</span><span class='dot'>.</span></div>"
            + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private void scheduleRetry() {
        if (loaded || retries >= MAX_RETRY) return;
        retries++;
        showConnecting();
        handler.postDelayed(new Runnable() {
            @Override public void run() { web.loadUrl(URL); }
        }, RETRY_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        needReload = true;   // 홈으로 나갔음 → 다음 복귀 때 재연결
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) return;
        web.onResume();
        if (needReload) {                       // 홈화면 갔다 돌아온 경우
            needReload = false;
            retries = 0;
            loaded = false;
            handler.removeCallbacksAndMessages(null);
            showConnecting();                    // 로딩 스피너 표시
            web.loadUrl(URL);                    // 재연결/리프레시
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        web.restoreState(savedInstanceState);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();   // 재시도/로딩 히스토리 무시하고 한 번에 종료
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 배터리 최적화 예외 요청(1회). 예외가 없으면 절전 상태에서 폴링·칩 갱신이 멈춘다. */
    private void askIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            android.content.Intent it = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(it);
        } catch (Exception ignore) { /* 기기에 해당 화면이 없으면 무시 */ }
    }
}
