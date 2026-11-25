package dev.magma.client.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.magma.client.MagmaClient;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

/**
 * One-click updates from GitHub Releases (github.com/jjtjtyt6644/Magma-Client).
 * Check -> download the new jar next to the old one (as .new) -> a tiny helper swaps the files the moment
 * Minecraft closes (the running jar is locked on Windows). Next launch runs the new version.
 */
public final class Updater {
    public static final String REPO = "jjtjtyt6644/Magma-Client";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

    private static volatile State state = State.IDLE;
    private static volatile String latest = "";
    private static volatile String assetUrl, assetName;
    private static volatile float progress;
    private static volatile String message = "";
    private static volatile String notes = "";
    private static volatile long checkedAt, downloaded, total;
    private static volatile boolean dismissed;

    /** First line or two of the release notes ("what's new"). */
    public static String notes() { return notes; }
    public static long checkedAt() { return checkedAt; }
    public static long downloaded() { return downloaded; }
    public static long total() { return total; }
    /** "Later" on the banner hides it for this session (Settings still shows it). */
    public static boolean dismissed() { return dismissed; }
    public static void dismiss() { dismissed = true; }

    /**
     * The version we compare against GitHub. The dev/test copy can pretend to be older
     * (gradlew runClient -PmagmaFakeVersion=1.4.3) to preview the update screens.
     */
    public static String installed() {
        String fake = System.getProperty("magma.fakeVersion", "");
        return !FMLEnvironment.production && !fake.isBlank() ? fake : MagmaClient.VERSION;
    }

    /** Set by "Update & restart": close the game as soon as the download is done. */
    private static volatile boolean quitWhenReady;

    public static boolean quitting() { return quitWhenReady; }

    /**
     * One click: download (if needed), then save + leave the world and close Minecraft. The helper armed at
     * download time deletes the old jar and moves the new one in the moment the game has closed.
     */
    public static void installAndRestart() {
        if (!canInstall()) {
            message = "Test copy - this button works in your real modpack";
            Notify.show("Test copy can't update itself - works in the real modpack", Theme.WARN);
            return;
        }
        if (state == State.READY) {
            quitNow();
        } else if (state == State.AVAILABLE) {
            quitWhenReady = true;
            downloadAsync();
        } else if (state == State.ERROR || state == State.UP_TO_DATE || state == State.IDLE) {
            quitWhenReady = true;
            checkAsync(true); // re-check, download, then close
        }
    }

    /** Save and leave the world the normal way (like "Save and Quit to Title"), then quit the game. */
    private static void quitNow() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            try {
                if (mc.level != null) {
                    boolean local = mc.isLocalServer();
                    mc.level.disconnect();
                    if (local) mc.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(
                            net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
                    else mc.disconnect();
                }
            } catch (Throwable t) {
                MagmaClient.LOGGER.warn("[Magma] Couldn't leave the world cleanly before updating: {}", t.toString());
            }
            mc.stop();
        });
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Updater() {}

    public static State state() { return state; }
    public static String latest() { return latest; }
    public static float progress() { return progress; }
    public static String message() { return message; }

    /** Installed as a real jar (not the dev environment), so we can replace it. */
    public static boolean canInstall() {
        return FMLEnvironment.production && currentJar() != null;
    }

    private static Path currentJar() {
        try {
            Path p = ModList.get().getModFileById(MagmaClient.MODID).getFile().getFilePath();
            return p != null && p.toString().endsWith(".jar") && Files.isRegularFile(p) ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Release notes -> one short line: first bullet points, markdown stripped. */
    private static String summarize(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\r?\n")) {
            String t = line.replaceAll("[*_`#>]", "").replaceFirst("^\s*[-•]\s*", "").trim();
            if (t.isEmpty() || t.toLowerCase(Locale.ROOT).startsWith("needs minecraft")) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(t);
            if (sb.length() > 90) break;
        }
        return sb.toString();
    }

    /** a > b ? (versions like 1.2.10 vs 1.2.9) */
    static boolean newer(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Check GitHub in the background. {@code autoDownload}: fetch it straight away if there's an update. */
    public static void checkAsync(boolean autoDownload) {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.READY) return;
        state = State.CHECKING;
        message = "Checking for updates...";
        Thread t = new Thread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "MagmaClient-Updater")
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 404) {
                    quitWhenReady = false;
                    state = State.UP_TO_DATE;
                    message = "No releases published yet";
                    return;
                }
                if (res.statusCode() != 200) throw new IllegalStateException("GitHub answered " + res.statusCode());
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                String tag = o.get("tag_name").getAsString().replaceFirst("^[vV]", "");
                String url = null, name = null;
                JsonArray assets = o.getAsJsonArray("assets");
                for (JsonElement e : assets) {
                    JsonObject a = e.getAsJsonObject();
                    String n = a.get("name").getAsString();
                    if (!n.toLowerCase(Locale.ROOT).endsWith(".jar") || n.toLowerCase(Locale.ROOT).contains("sources")) continue;
                    url = a.get("browser_download_url").getAsString();
                    name = n;
                    if (n.toLowerCase(Locale.ROOT).startsWith("magmaclient")) break;
                }
                latest = tag;
                checkedAt = System.currentTimeMillis();
                notes = summarize(o.has("body") && !o.get("body").isJsonNull() ? o.get("body").getAsString() : "");
                if (!newer(tag, installed())) {
                    quitWhenReady = false; // nothing to install: never close the game later by surprise
                    state = State.UP_TO_DATE;
                    message = "You're up to date";
                    return;
                }
                if (url == null || !url.startsWith("https://github.com/")) {
                    state = State.ERROR;
                    message = "Release " + tag + " has no jar attached";
                    return;
                }
                assetUrl = url;
                assetName = name;
                state = State.AVAILABLE;
                message = "Version " + tag + " is available";
                dismissed = false;
                MagmaClient.LOGGER.info("[Magma] Update available: {} -> {}", installed(), tag);
                if (autoDownload && canInstall()) downloadAsync();
            } catch (Exception e) {
                quitWhenReady = false;
                state = State.ERROR;
                message = "Update check failed (offline?)";
                MagmaClient.LOGGER.warn("[Magma] Update check failed: {}", e.toString());
            }
        }, "Magma-Updater");
        t.setDaemon(true);
        t.start();
    }

    /** Download the new jar and arm the swap that happens when Minecraft closes. */
    public static void downloadAsync() {
        if (state != State.AVAILABLE || assetUrl == null) return;
        if (!canInstall()) {
            message = "Can't self-update here (dev build) - download it from GitHub";
            return;
        }
        state = State.DOWNLOADING;
        progress = 0;
        message = "Downloading " + latest + "...";
        Thread t = new Thread(() -> {
            try {
                Path mods = FMLPaths.MODSDIR.get();
                Path part = mods.resolve(assetName + ".part");
                Path fresh = mods.resolve(assetName + ".new"); // not a .jar, so the loader ignores it
                HttpRequest req = HttpRequest.newBuilder(URI.create(assetUrl))
                        .header("User-Agent", "MagmaClient-Updater")
                        .timeout(Duration.ofMinutes(2)).GET().build();
                HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) throw new IllegalStateException("download answered " + res.statusCode());
                long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
                Updater.total = Math.max(0, total);
                Updater.downloaded = 0;
                try (InputStream in = res.body(); var out = Files.newOutputStream(part)) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        Updater.downloaded = done;
                        if (total > 0) progress = (float) done / total;
                    }
                }
                if (Files.size(part) < 10_000) throw new IllegalStateException("download looks broken");
                Files.move(part, fresh, StandardCopyOption.REPLACE_EXISTING);
                armSwap(fresh);
                progress = 1;
                state = State.READY;
                message = "Update " + latest + " ready - click Restart now";
                MagmaClient.LOGGER.info("[Magma] Update {} downloaded; it will be installed when the game closes", latest);
                if (quitWhenReady) quitNow();
            } catch (Exception e) {
                quitWhenReady = false;
                state = State.ERROR;
                message = "Download failed - try again";
                MagmaClient.LOGGER.warn("[Magma] Update download failed: {}", e.toString());
            }
        }, "Magma-Updater-Download");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Start a small helper that waits for this Minecraft process to exit, then deletes the old jar and renames
     * the downloaded one into place. (Windows keeps the running jar locked, so it can't be replaced while playing.)
     */
    private static void armSwap(Path fresh) throws Exception {
        Path old = currentJar();
        if (old == null) return;
        String finalName = fresh.getFileName().toString().replaceFirst("\\.new$", "");
        long pid = ProcessHandle.current().pid();
        ProcessBuilder pb;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String ps = "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1; "
                    + "Remove-Item -LiteralPath '" + old.toString().replace("'", "''") + "' -Force -ErrorAction SilentlyContinue; "
                    + "Move-Item -LiteralPath '" + fresh.toString().replace("'", "''") + "' -Destination '"
                    + fresh.resolveSibling(finalName).toString().replace("'", "''") + "' -Force";
            pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps);
        } else {
            String sh = "while kill -0 " + pid + " 2>/dev/null; do sleep 1; done; rm -f '" + old + "'; mv -f '" + fresh + "' '"
                    + fresh.resolveSibling(finalName) + "'";
            pb = new ProcessBuilder("sh", "-c", sh);
        }
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }

    /** On startup: a previous update was downloaded but not swapped in (e.g. the PC shut down) - arm it again. */
    public static void resumePending() {
        if (!canInstall()) return;
        try (var files = Files.list(FMLPaths.MODSDIR.get())) {
            files.filter(p -> p.getFileName().toString().startsWith("magmaclient") && p.getFileName().toString().endsWith(".jar.new"))
                    .findFirst().ifPresent(p -> {
                        try {
                            armSwap(p);
                            state = State.READY;
                            message = "Update ready - restart Minecraft to finish";
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}
