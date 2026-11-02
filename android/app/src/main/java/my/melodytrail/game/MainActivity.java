package my.melodytrail.game;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;

/**
 * Offline Melody Trail WebView host. A native AudioRecord bridge supplies PCM
 * from the device's built-in microphone on Android, avoiding devices where
 * WebView getUserMedia fails with "Could not start audio source".
 */
public class MainActivity extends Activity {
    private static final int MICROPHONE_PERMISSION_CODE = 1108;
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final int MIC_SAMPLE_RATE = 44100;
    private static final int MIC_WINDOW_SIZE = 4096;

    private WebView webView;
    private PermissionRequest pendingMicrophone;
    private boolean nativePermissionPending = false;
    private AudioRecord nativeRecorder;
    private volatile boolean nativeRecording = false;
    private final Object sampleLock = new Object();
    private final short[] recentSamples = new short[MIC_WINDOW_SIZE * 2];
    private int samplePosition = 0;
    private int sampleCount = 0;

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
        // This bridge is only exposed to the bundled, trusted offline game.
        webView.addJavascriptInterface(new NativeMicrophone(), "MelodyNativeMic");
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

    private class NativeMicrophone {
        @JavascriptInterface public void start() {
            runOnUiThread(() -> {
                if (nativeRecording) { notifyMicrophoneReady(true, "Device microphone ready"); return; }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    nativePermissionPending = true;
                    requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, MICROPHONE_PERMISSION_CODE);
                } else startNativeRecording();
            });
        }

        @JavascriptInterface public void stop() {
            runOnUiThread(() -> {
                nativePermissionPending = false;
                stopNativeRecording();
            });
        }

        @JavascriptInterface public String getSamples() {
            if (!nativeRecording) return "";
            byte[] bytes = new byte[MIC_WINDOW_SIZE * 2];
            synchronized (sampleLock) {
                if (sampleCount < MIC_WINDOW_SIZE) return "";
                int first = (samplePosition - MIC_WINDOW_SIZE + recentSamples.length) % recentSamples.length;
                for (int i = 0; i < MIC_WINDOW_SIZE; i++) {
                    short value = recentSamples[(first + i) % recentSamples.length];
                    bytes[2 * i] = (byte) (value & 255);
                    bytes[2 * i + 1] = (byte) ((value >> 8) & 255);
                }
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        }
    }

    @SuppressLint("MissingPermission")
    private void startNativeRecording() {
        stopNativeRecording();
        try {
            int minBuffer = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IllegalStateException("Device microphone is unavailable");
            int bufferSize = Math.max(minBuffer, MIC_WINDOW_SIZE * 4);
            AudioRecord record = null;
            for (int source : new int[]{ MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION }) {
                try {
                    record = new AudioRecord(source, MIC_SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                    if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                        record.startRecording();
                        if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) break;
                    }
                } catch (Exception ignored) { }
                if (record != null) record.release();
                record = null;
            }
            if (record == null || record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Could not open the device microphone. Close other recording apps and retry.");
            nativeRecorder = record;
            synchronized (sampleLock) { samplePosition = 0; sampleCount = 0; }
            nativeRecording = true;
            final AudioRecord active = record;
            Thread reader = new Thread(() -> {
                short[] block = new short[1024];
                while (nativeRecording && nativeRecorder == active) {
                    int received = active.read(block, 0, block.length);
                    if (received < 0) break;
                    if (received == 0) continue;
                    synchronized (sampleLock) {
                        if (!nativeRecording || nativeRecorder != active) break;
                        for (int i = 0; i < received; i++) {
                            recentSamples[samplePosition] = block[i];
                            samplePosition = (samplePosition + 1) % recentSamples.length;
                        }
                        sampleCount = Math.min(recentSamples.length, sampleCount + received);
                    }
                }
            }, "MelodyTrailMicReader");
            reader.setDaemon(true);
            reader.start();
            notifyMicrophoneReady(true, "Device microphone ready");
        } catch (Exception error) {
            stopNativeRecording();
            notifyMicrophoneReady(false, error.getMessage() == null ? "Microphone unavailable" : error.getMessage());
        }
    }

    private void stopNativeRecording() {
        nativeRecording = false;
        AudioRecord record = nativeRecorder;
        nativeRecorder = null;
        if (record != null) {
            try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
            catch (Exception ignored) { }
            try { record.release(); } catch (Exception ignored) { }
        }
    }

    private void notifyMicrophoneReady(boolean ready, String message) {
        if (webView == null) return;
        String script = "window.onMelodyMicrophoneReady && window.onMelodyMicrophoneReady("
                + ready + "," + JSONObject.quote(message) + ")";
        webView.evaluateJavascript(script, null);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MICROPHONE_PERMISSION_CODE) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingMicrophone != null) {
                if (granted) pendingMicrophone.grant(new String[]{ PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                else pendingMicrophone.deny();
                pendingMicrophone = null;
            }
            if (nativePermissionPending) {
                nativePermissionPending = false;
                if (granted) startNativeRecording();
                else notifyMicrophoneReady(false, "Microphone permission denied. Allow it in Android Settings > Apps > Melody Trail > Permissions.");
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        // The Android permission prompt can itself pause this Activity; do not
        // cancel the first microphone grant while the user answers the dialog.
        if (nativeRecording) {
            stopNativeRecording();
            if (webView != null) webView.evaluateJavascript(
                    "window.onMelodyMicrophoneInterrupted && window.onMelodyMicrophoneInterrupted()", null);
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopNativeRecording();
        if (pendingMicrophone != null) { pendingMicrophone.deny(); pendingMicrophone = null; }
        if (webView != null) { webView.loadUrl("about:blank"); webView.destroy(); }
        super.onDestroy();
    }
}
