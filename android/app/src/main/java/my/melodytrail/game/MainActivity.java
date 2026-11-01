package my.melodytrail.game;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

/**
 * Secure local WebView host for Melody Trail. WebViewAssetLoader provides an
 * HTTPS origin so microphone getUserMedia works without an internet connection.
 */
public class MainActivity extends Activity {
    private static final int MICROPHONE_PERMISSION_CODE = 1108;
    private static final String APP_HOST = "appassets.androidplatform.net";
    private WebView webView;
    private PermissionRequest pendingMicrophone;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF8FAFF);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) return false;
                if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ignored) { }
                    return true;
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (pendingMicrophone != null) { pendingMicrophone.deny(); pendingMicrophone = null; }
                    if (!APP_HOST.equals(request.getOrigin().getHost())) { request.deny(); return; }
                    boolean asksForAudio = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) asksForAudio = true;
                        else { request.deny(); return; }
                    }
                    if (!asksForAudio) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{ PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                    } else {
                        pendingMicrophone = request;
                        requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, MICROPHONE_PERMISSION_CODE);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (request == pendingMicrophone) pendingMicrophone = null;
            }
        });
        setContentView(webView);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MICROPHONE_PERMISSION_CODE && pendingMicrophone != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingMicrophone.grant(new String[]{ PermissionRequest.RESOURCE_AUDIO_CAPTURE });
            } else pendingMicrophone.deny();
            pendingMicrophone = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (pendingMicrophone != null) { pendingMicrophone.deny(); pendingMicrophone = null; }
        if (webView != null) { webView.loadUrl("about:blank"); webView.destroy(); }
        super.onDestroy();
    }
}
