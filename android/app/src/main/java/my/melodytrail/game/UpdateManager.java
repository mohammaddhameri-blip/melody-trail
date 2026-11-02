package my.melodytrail.game;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks the official GitHub latest release on app launch, downloads new APKs
 * to private cache, and asks Android's package installer to install them.
 * No app may install a replacement without the user approving Android's prompt.
 */
public final class UpdateManager {
    private static final String API = "https://api.github.com/repos/mohammaddhameri-blip/melody-trail/releases/latest";
    private static final String RELEASE_PREFIX =
            "https://github.com/mohammaddhameri-blip/melody-trail/releases/download/";
    private static final String APK_NAME = "Melody_Trail.apk";
    private static final long MAX_APK_BYTES = 80L * 1024 * 1024;
    private static final long AUTOMATIC_CHECK_INTERVAL = 12L * 60 * 60 * 1000;
    private static final String PREFS = "melody_updates";
    private final Activity activity;
    private final WebView webView;
    private final SharedPreferences prefs;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile String status = "idle";
    private volatile String detail = "Checking for updates automatically";
    private volatile String version = "";
    private volatile File downloadedApk;
    private volatile int downloadedVersion = 0;
    private boolean expectingInstallPermission = false;

    public UpdateManager(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String tag = prefs.getString("ready_tag", "");
        if (tag.matches("android-[0-9]+")) {
            File file = apkFile(tag);
            if (file.isFile() && file.length() > 0) {
                downloadedApk = file;
                downloadedVersion = Integer.parseInt(tag.substring("android-".length()));
                status = "ready";
                detail = "Update downloaded. Tap Install Update.";
                version = tag;
            }
        }
    }

    public void onPageReady() {
        publish();
        if (isEnabled()) check(false);
    }

    @JavascriptInterface public String getState() {
        JSONObject data = new JSONObject();
        try {
            data.put("enabled", isEnabled());
            data.put("status", status);
            data.put("detail", detail);
            data.put("version", version);
            data.put("installed", installedVersionCode());
        } catch (Exception ignored) { }
        return data.toString();
    }

    @JavascriptInterface public void setAutoEnabled(boolean enabled) {
        prefs.edit().putBoolean("auto", enabled).apply();
        if (enabled) check(false);
        else {
            detail = "Automatic update checking is off";
            publish();
        }
    }

    @JavascriptInterface public void checkNow() {
        check(true);
    }

    @JavascriptInterface public void installReady() {
        activity.runOnUiThread(this::showInstaller);
    }

    public void onResume() {
        if (expectingInstallPermission &&
            (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls())) {
            expectingInstallPermission = false;
            showInstaller();
        }
    }

    private boolean isEnabled() {
        return prefs.getBoolean("auto", true);
    }

    private int installedVersionCode() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) { return 0; }
    }

    private File apkFile(String tag) {
        File directory = new File(activity.getCacheDir(), "updates");
        return new File(directory, "Melody_Trail_" + tag + ".apk");
    }

    private void publish() {
        String script = "window.onMelodyUpdate && window.onMelodyUpdate(" +
                JSONObject.quote(status) + "," + JSONObject.quote(detail) + "," +
                JSONObject.quote(version) + ")";
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed())
                webView.evaluateJavascript(script, null);
        });
    }

    private void report(String state, String message, String targetVersion) {
        status = state;
        detail = message;
        version = targetVersion;
        publish();
    }

    private void check(boolean manual) {
        if (!manual && !isEnabled()) return;
        if (!busy.compareAndSet(false, true)) return;
        if (!manual && System.currentTimeMillis() - prefs.getLong("last_check", 0) < AUTOMATIC_CHECK_INTERVAL) {
            busy.set(false);
            publish();
            return;
        }
        new Thread(() -> {
            try {
                report("checking", "Checking GitHub Releases…", "");
                HttpURLConnection connection = open(new URL(API));
                JSONObject release;
                try {
                    if (connection.getResponseCode() != 200) {
                        throw new IllegalStateException("GitHub update check unavailable (" + connection.getResponseCode() + ")");
                    }
                    release = readJson(connection);
                } finally {
                    connection.disconnect();
                }
                String tag = release.optString("tag_name", "");
                if (!tag.matches("android-[0-9]+"))
                    throw new IllegalStateException("Unexpected release version");
                int remoteVersion = Integer.parseInt(tag.substring("android-".length()));
                int currentVersion = installedVersionCode();
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
                if (remoteVersion <= currentVersion) {
                    report("current", "You're using the latest Android version", tag);
                    return;
                }
                JSONArray assets = release.optJSONArray("assets");
                JSONObject selected = null;
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject item = assets.optJSONObject(i);
                    if (item != null && APK_NAME.equals(item.optString("name"))) {
                        selected = item;
                        break;
                    }
                }
                if (selected == null) throw new IllegalStateException("No APK attached to this GitHub release");
                String downloadUrl = selected.optString("browser_download_url", "");
                if (!downloadUrl.startsWith(RELEASE_PREFIX + tag + "/") ||
                        !downloadUrl.endsWith("/" + APK_NAME))
                    throw new SecurityException("Unrecognized update download URL");

                File target = apkFile(tag);
                long assetSize = selected.optLong("size", -1);
                String digest = selected.optString("digest", "");
                if (assetSize > MAX_APK_BYTES) throw new IllegalStateException("Update is too large");
                if (!target.isFile() || target.length() != assetSize || !digestValid(target, digest)) {
                    report("downloading", "Downloading update " + tag + "…", tag);
                    downloadApk(downloadUrl, target, assetSize, digest);
                }

                PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(
                        target.getAbsolutePath(), 0);
                if (archive == null || !activity.getPackageName().equals(archive.packageName) ||
                        (Build.VERSION.SDK_INT >= 28
                            ? archive.getLongVersionCode() : archive.versionCode) != remoteVersion) {
                    target.delete();
                    throw new SecurityException("Downloaded file is not the expected Melody Trail APK");
                }

                downloadedApk = target;
                downloadedVersion = remoteVersion;
                prefs.edit().putString("ready_tag", tag).apply();
                report("ready", "Update downloaded. Tap Install Update.", tag);
            } catch (Exception error) {
                report("error", "Update check: " + safeMessage(error), "");
            } finally {
                busy.set(false);
            }
        }, "MelodyGitHubUpdater").start();
    }

    private HttpURLConnection open(URL url) throws Exception {
        if (!"https".equalsIgnoreCase(url.getProtocol()))
            throw new SecurityException("HTTPS is required for updates");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "MelodyTrail-Android-Updater");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private JSONObject readJson(HttpURLConnection connection) throws Exception {
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 1024 * 1024)
                    throw new IllegalStateException("GitHub response unexpectedly large");
            }
            return new JSONObject(output.toString("UTF-8"));
        }
    }

    private void downloadApk(String url, File target, long expectedSize, String expectedDigest) throws Exception {
        File folder = target.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IllegalStateException("Could not create update cache");
        File temp = new File(folder, "download.tmp");
        HttpURLConnection connection = open(new URL(url));
        try {
            if (connection.getResponseCode() != 200)
                throw new IllegalStateException("Download failed (" + connection.getResponseCode() + ")");
            URL finalUrl = connection.getURL();
            if (!"https".equalsIgnoreCase(finalUrl.getProtocol()))
                throw new SecurityException("Insecure update redirect");
            long announced = connection.getContentLengthLong();
            if (announced > MAX_APK_BYTES)
                throw new IllegalStateException("Update is too large");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_APK_BYTES)
                        throw new IllegalStateException("Update is too large");
                    sha256.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            if (total == 0 || (expectedSize > 0 && total != expectedSize))
                throw new IllegalStateException("Incomplete APK download");
            if (!matchesDigest(sha256.digest(), expectedDigest))
                throw new SecurityException("GitHub APK checksum did not match");
            if (target.exists() && !target.delete())
                throw new IllegalStateException("Could not replace cached APK");
            if (!temp.renameTo(target))
                throw new IllegalStateException("Could not save downloaded APK");
        } finally {
            connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    private boolean digestValid(File file, String digest) throws Exception {
        if (digest == null || !digest.startsWith("sha256:")) return true;
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) sha256.update(buffer, 0, n);
        }
        return matchesDigest(sha256.digest(), digest);
    }

    private boolean matchesDigest(byte[] hash, String expected) {
        if (expected == null || !expected.startsWith("sha256:")) return true;
        StringBuilder actual = new StringBuilder();
        for (byte b : hash) actual.append(String.format(Locale.US, "%02x", b & 255));
        return actual.toString().equalsIgnoreCase(expected.substring(7));
    }

    private String safeMessage(Exception error) {
        String msg = error.getMessage();
        return (msg == null || msg.isEmpty()) ? "Try again when connected to the internet" : msg;
    }

    private void showInstaller() {
        File apk = downloadedApk;
        if (apk == null || !apk.isFile() || apk.length() == 0 ||
                downloadedVersion <= installedVersionCode()) {
            report("error", "No newer APK is ready. Tap Check Now.", "");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            expectingInstallPermission = true;
            report("permission", "Allow Melody Trail to install updates, then return to the app.", version);
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try { activity.startActivity(settings); }
            catch (Exception e) { report("error", "Open Android settings and enable Install unknown apps for Melody Trail.", version); }
            return;
        }
        try {
            Uri contentUri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(contentUri, "application/vnd.android.package-archive");
            install.setClipData(ClipData.newRawUri("Melody Trail update", contentUri));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
            report("ready", "Confirm installation in Android's installer.", version);
        } catch (Exception e) {
            report("error", "Android installer could not open: " + safeMessage(e), version);
        }
    }
}
