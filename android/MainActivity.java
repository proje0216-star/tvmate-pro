package com.tvmate.pro;

import android.annotation.SuppressLint;
import android.app.UiModeManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private boolean tv;
    private volatile boolean textInput;
    private ExoPlayer player;
    private PlayerView pv;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String curUrl;
    private boolean triedAlt, lastHls, wasPlaying;
    private float vol = 1f;
    private boolean muted;

    /** JS köprüsü: window.TVMateNative */
    class Bridge {
        @JavascriptInterface public boolean isTv() { return tv; }
        @JavascriptInterface public void setTextInput(boolean on) { textInput = on; }
        @JavascriptInterface public boolean nativePlayer() { return true; }
        @JavascriptInterface public void load(final String u) { runOnUiThread(() -> loadUrl(u)); }
        @JavascriptInterface public void play() { runOnUiThread(() -> { if (player != null) player.play(); }); }
        @JavascriptInterface public void pause() { runOnUiThread(() -> { if (player != null) player.pause(); }); }
        @JavascriptInterface public void stop() { runOnUiThread(() -> stopPlayer()); }
        @JavascriptInterface public void seekTo(final long ms) { runOnUiThread(() -> { if (player != null) player.seekTo(ms); }); }
        @JavascriptInterface public void setMuted(final boolean m) { runOnUiThread(() -> { muted = m; applyVolume(); }); }
        @JavascriptInterface public void setVolume(final float x) { runOnUiThread(() -> { vol = x; applyVolume(); }); }
        @JavascriptInterface public void setResize(final String m) {
            runOnUiThread(() -> pv.setResizeMode("fill".equals(m) ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                    : "zoom".equals(m) ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT));
        }
        /** Video alanı (cihaz pikseli): sayfadaki şeffaf deliğin konumu */
        @JavascriptInterface public void setRect(final int x, final int y, final int w, final int h) {
            runOnUiThread(() -> {
                if (w < 2 || h < 2 || curUrl == null) { pv.setVisibility(View.GONE); return; }
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
                lp.leftMargin = x; lp.topMargin = y;
                pv.setLayoutParams(lp);
                pv.setVisibility(View.VISIBLE);
            });
        }
        @JavascriptInterface public void setFullscreen(final boolean on) {
            runOnUiThread(() -> {
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (on) c.hide(WindowInsetsCompat.Type.systemBars()); else c.show(WindowInsetsCompat.Type.systemBars());
            });
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        PackageManager pm = getPackageManager();
        tv = um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION
                || pm.hasSystemFeature("android.software.leanback")
                || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        // TV/box: yatay sabit. Telefon/tablet: sensörü izle (dikey de çalışır).
        setRequestedOrientation(tv ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                   : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "TVMateNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return loader.shouldInterceptRequest(u);
                String sc = u.getScheme();
                // Sayfadan giden CORS'lu XHR/fetch (hls.js, M3U/Xtream) -> CORS başlığı ekleyerek yerelde aktar.
                if (("http".equals(sc) || "https".equals(sc)) && r.getRequestHeaders().containsKey("Origin"))
                    return proxy(r);
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());
            }
        });
        FrameLayout rootView = new FrameLayout(this);
        rootView.setBackgroundColor(0xFF090D14);
        pv = (PlayerView) LayoutInflater.from(this).inflate(R.layout.player_view, rootView, false);
        rootView.addView(pv);                                            // altta: native video
        rootView.addView(web, new FrameLayout.LayoutParams(-1, -1));     // üstte: şeffaf arayüz
        setContentView(rootView);

        // Geri tuşu/jesti önce sayfaya sorulur; "true" dönerse tüketilmiştir, aksi halde uygulama kapanır.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.onRemoteBack?window.onRemoteBack():false", v -> {
                    if (!"true".equals(v)) {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });
            }
        });

        web.loadUrl("https://" + HOST + "/assets/index.html");
        web.requestFocus();
    }

    private static String mapKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: return "OK";
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_BUTTON_Y: return "MENU";
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP: return "CH_UP";
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN: return "CH_DOWN";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK: return "PLAY_PAUSE";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "PLAY";
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "PAUSE";
            case KeyEvent.KEYCODE_MEDIA_STOP: return "STOP";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "FF";
            case KeyEvent.KEYCODE_MEDIA_REWIND: return "REW";
            case KeyEvent.KEYCODE_PROG_YELLOW: return "ASPECT";
        }
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return "NUM_" + (k - KeyEvent.KEYCODE_0);
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) return "NUM_" + (k - KeyEvent.KEYCODE_NUMPAD_0);
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String name = mapKey(e.getKeyCode());
        if (name == null || web == null) return super.dispatchKeyEvent(e);
        // Metin kutusunda sol/sağ/OK/rakam klavyeye bırakılır; yukarı/aşağı sayfaya gider (kutudan çıkış).
        if (textInput && !name.equals("UP") && !name.equals("DOWN") && !name.equals("MENU"))
            return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN)
            web.evaluateJavascript("window.onRemoteKey&&window.onRemoteKey('" + name + "'," + e.getRepeatCount() + ")", null);
        return true;
    }

    // ---------------- Media3 ----------------
    private void ensurePlayer() {
        if (player != null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Mozilla/5.0 (Linux; Android) TVMatePro")
                .setConnectTimeoutMs(10000).setReadTimeoutMs(20000);
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(15000, 50000, 2500, 5000).build())
                .build();
        pv.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) { pushState(null); }
            @Override public void onIsPlayingChanged(boolean p) { pushState(null); }
            @Override public void onPlayerError(PlaybackException e) {
                // Uzantısız akışta tahmin yanlışsa (HLS <-> TS/MP4) bir kez diğer türle dene
                if (!triedAlt && curUrl != null
                        && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                        || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)) {
                    triedAlt = true;
                    startUrl(curUrl, !lastHls);
                    return;
                }
                pushState(e.getErrorCodeName());
            }
        });
        ui.post(new Runnable() {
            @Override public void run() {
                if (player == null) return;
                pushState(null);
                ui.postDelayed(this, 500);
            }
        });
    }

    private void loadUrl(String u) {
        ensurePlayer();
        curUrl = u; triedAlt = false;
        String l = u.toLowerCase();
        startUrl(u, l.contains(".m3u8") || l.contains("output=m3u8") || l.contains("output=hls") || l.contains("type=m3u8"));
    }

    private void startUrl(String u, boolean hls) {
        lastHls = hls;
        MediaItem.Builder b = new MediaItem.Builder().setUri(u);
        if (hls) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(b.build());
        player.prepare();
        player.play();
        applyVolume();
    }

    private void stopPlayer() {
        curUrl = null;
        if (player != null) { player.stop(); player.clearMediaItems(); }
        if (pv != null) pv.setVisibility(View.GONE);
    }

    private void applyVolume() { if (player != null) player.setVolume(muted ? 0f : vol); }

    private void pushState(String err) {
        if (player == null || web == null) return;
        long dur = player.getDuration();
        VideoSize vs = player.getVideoSize();
        String j = "{\"pos\":" + player.getCurrentPosition() + ",\"dur\":" + (dur == C.TIME_UNSET ? -1 : dur)
                + ",\"buf\":" + player.getBufferedPosition() + ",\"st\":" + player.getPlaybackState()
                + ",\"pwr\":" + player.getPlayWhenReady() + ",\"vw\":" + vs.width + ",\"vh\":" + vs.height
                + (err != null ? ",\"err\":\"" + err + "\"" : "") + "}";
        web.evaluateJavascript("window.__np&&window.__np(" + j + ")", null);
    }

    /** http(s) isteğini doğrudan çekip CORS izni ekler (3. parti proxy gerekmez). */
    private WebResourceResponse proxy(WebResourceRequest r) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Access-Control-Allow-Headers", "Range, Content-Type, Authorization");
        h.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        h.put("Access-Control-Expose-Headers", "Content-Length, Content-Range, Content-Type");
        if ("OPTIONS".equals(r.getMethod()))
            return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", h, new ByteArrayInputStream(new byte[0]));
        try {
            String u = r.getUrl().toString();
            HttpURLConnection c = null;
            for (int i = 0; i < 6; i++) {   // http<->https yönlendirmelerini elle takip et
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(25000);
                c.setRequestMethod(r.getMethod());
                for (Map.Entry<String, String> e : r.getRequestHeaders().entrySet()) {
                    String k = e.getKey();
                    if (k.equalsIgnoreCase("Origin") || k.equalsIgnoreCase("Referer") || k.equalsIgnoreCase("Accept-Encoding")) continue;
                    c.setRequestProperty(k, e.getValue());
                }
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) { u = new URL(new URL(u), loc).toString(); c.disconnect(); continue; }
                break;
            }
            int code = c.getResponseCode();
            String ct = c.getContentType();
            String mime = ct == null ? "application/octet-stream" : ct.split(";")[0].trim();
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) h.put("Content-Range", cr);
            String msg = c.getResponseMessage();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) in = new ByteArrayInputStream(new byte[0]);
            return new WebResourceResponse(mime, null, code, (msg == null || msg.isEmpty()) ? "OK" : msg, h, in);
        } catch (Exception ex) {
            return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null) { wasPlaying = player.getPlayWhenReady(); player.pause(); }
        if (web != null) web.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.requestFocus(); }
        if (player != null && wasPlaying) player.play();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
}
