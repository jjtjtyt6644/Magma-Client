package dev.magma.client.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.magma.client.MagmaClient;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * The Magma Guide: its own little website on this PC (localhost:25591, separate from Builder Studio) with how to use
 * every feature, the chat commands and fixes for common problems. Only reachable from this computer.
 */
public final class GuideServer {
    private static final int PREFERRED_PORT = 25591;
    private static HttpServer server;

    private GuideServer() {}

    /** Start (if needed) and open the guide in the default browser. */
    public static void open() {
        try {
            int port = start();
            String url = "http://localhost:" + port + "/";
            net.minecraft.Util.getPlatform().openUri(url);
            Chat.info("Magma Guide is open in your browser: " + url);
            Notify.show("Magma Guide opened in your browser", "localhost:" + port + " - how every feature works, the chat commands, and fixes for common problems.", Theme.OK);
        } catch (Exception e) {
            Notify.show("Magma Guide didn't start", String.valueOf(e.getMessage()), Theme.DANGER);
        }
    }

    private static synchronized int start() throws IOException {
        if (server != null) return server.getAddress().getPort();
        InetAddress lo = InetAddress.getLoopbackAddress();
        try {
            server = HttpServer.create(new InetSocketAddress(lo, PREFERRED_PORT), 0);
        } catch (IOException busy) {
            server = HttpServer.create(new InetSocketAddress(lo, 0), 0);
        }
        setUp(server);
        try { // "localhost" can be the IPv6 loopback too
            setUp(HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), server.getAddress().getPort()), 0));
        } catch (IOException ignored) {}
        return server.getAddress().getPort();
    }

    private static void setUp(HttpServer s) {
        s.createContext("/", GuideServer::page);
        s.createContext("/api/features", GuideServer::features);
        s.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Magma-Guide");
            t.setDaemon(true);
            return t;
        }));
        s.start();
    }

    private static void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "not found".getBytes()); return; }
        try (InputStream in = GuideServer.class.getResourceAsStream("/assets/magmaclient/guide/index.html")) {
            if (in == null) { send(ex, 500, "text/plain", "guide page missing".getBytes()); return; }
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    /** Live list of features: what's on right now and their keys (so the guide matches your game). */
    private static void features(HttpExchange ex) throws IOException {
        try {
            String json = Minecraft.getInstance().submit(() -> {
                JsonObject root = new JsonObject();
                root.addProperty("version", MagmaClient.VERSION);
                root.addProperty("loader", MagmaClient.LOADER);
                JsonArray arr = new JsonArray();
                for (Module m : ModuleManager.all()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", m.getName());
                    o.addProperty("description", m.getDescription());
                    o.addProperty("category", m.getCategory().name());
                    o.addProperty("enabled", m.isEnabled());
                    o.addProperty("key", m.getKey() == -1 ? "" : com.mojang.blaze3d.platform.InputConstants.getKey(m.getKey(), 0).getDisplayName().getString());
                    arr.add(o);
                }
                root.add("features", arr);
                return root.toString();
            }).get();
            send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            send(ex, 500, "text/plain", ("failed: " + e).getBytes());
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}
