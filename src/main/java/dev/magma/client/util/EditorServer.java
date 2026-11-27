package dev.magma.client.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.magma.client.MagmaClient;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.modules.Builder;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The image converter website, served from your own PC (127.0.0.1 only - nothing leaves your computer).
 * Turns a picture into a schematic with real block colours, lets you edit it, and sends it straight to the Builder.
 */
public final class EditorServer {
    private static final int PREFERRED_PORT = 25590;
    private static HttpServer server;
    private static String paletteJson;

    private EditorServer() {}

    /** Start (if needed) and open the editor in the default browser. */
    public static void open() {
        try {
            int port = start();
            String url = "http://127.0.0.1:" + port + "/";
            net.minecraft.Util.getPlatform().openUri(url);
            Chat.info("Image converter opened in your browser: " + url);
        } catch (Exception e) {
            Chat.info("Couldn't start the image converter: " + e.getMessage());
            MagmaClient.LOGGER.error("[Magma] editor server", e);
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
        server.createContext("/", EditorServer::page);
        server.createContext("/api/palette", EditorServer::palette);
        server.createContext("/api/save", EditorServer::save);
        server.createContext("/api/fetch", EditorServer::fetch);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Magma-Editor");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ handlers

    private static void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "not found".getBytes()); return; }
        try (InputStream in = EditorServer.class.getResourceAsStream("/assets/magmaclient/editor/index.html")) {
            if (in == null) { send(ex, 500, "text/plain", "editor page missing".getBytes()); return; }
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private static void palette(HttpExchange ex) throws IOException {
        try {
            if (paletteJson == null) {
                // textures/models belong to the game thread
                paletteJson = Minecraft.getInstance().submit(EditorServer::buildPalette).get();
            }
            send(ex, 200, "application/json", paletteJson.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MagmaClient.LOGGER.error("[Magma] palette", e);
            send(ex, 500, "text/plain", ("palette failed: " + e).getBytes());
        }
    }

    /** POST {name, w, h, d, palette:[ids], data:[index per cell, x fastest then z then y], load:true} */
    private static void save(HttpExchange ex) throws IOException {
        // only our own page can call this: browsers won't send a custom header cross-site without asking first
        if (!"POST".equals(ex.getRequestMethod()) || !"1".equals(ex.getRequestHeaders().getFirst("X-Magma"))) {
            send(ex, 403, "text/plain", "forbidden".getBytes());
            return;
        }
        try {
            JsonObject req = new Gson().fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            String name = req.get("name").getAsString().replaceAll("[^A-Za-z0-9_\\- ]", "").trim();
            if (name.isEmpty()) name = "converted";
            int w = req.get("w").getAsInt(), h = req.get("h").getAsInt(), d = req.get("d").getAsInt();
            if (w < 1 || h < 1 || d < 1 || (long) w * h * d > 4_000_000) throw new IOException("bad size");
            JsonArray pal = req.getAsJsonArray("palette");
            JsonArray data = req.getAsJsonArray("data");
            if (data.size() != w * h * d) throw new IOException("data size mismatch");
            Path file = Schematic.folder().resolve(name + ".schem");
            writeSponge(file, w, h, d, pal, data);
            boolean load = !req.has("load") || req.get("load").getAsBoolean();
            String n = name;
            if (load) Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null) ModuleManager.get(Builder.class).load(n);
            });
            JsonObject res = new JsonObject();
            res.addProperty("ok", true);
            res.addProperty("file", file.getFileName().toString());
            res.addProperty("inGame", Minecraft.getInstance().player != null);
            send(ex, 200, "application/json", res.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            send(ex, 400, "text/plain", ("couldn't save: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** GET /api/fetch?url=... : download a picture from a link (browsers can't read other sites' pixels themselves). */
    private static void fetch(HttpExchange ex) throws IOException {
        try {
            String q = ex.getRequestURI().getRawQuery();
            if (q == null || !q.startsWith("url=")) throw new IOException("no link");
            String url = java.net.URLDecoder.decode(q.substring(4), StandardCharsets.UTF_8);
            java.net.URI uri = java.net.URI.create(url);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw new IOException("only http(s) links");
            var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            var resp = client.send(java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0").build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            String type = resp.headers().firstValue("Content-Type").orElse("");
            if (resp.statusCode() != 200) throw new IOException("the site answered " + resp.statusCode());
            if (!type.startsWith("image/")) throw new IOException("that link isn't a picture (" + type + ")");
            byte[] body;
            try (InputStream in = resp.body()) { body = in.readNBytes(20 * 1024 * 1024); }
            send(ex, 200, type, body);
        } catch (Exception e) {
            send(ex, 400, "text/plain", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ------------------------------------------------------------------ schematic writing

    private static void writeSponge(Path file, int w, int h, int d, JsonArray pal, JsonArray data) throws IOException {
        CompoundTag palette = new CompoundTag();
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        palette.putInt("minecraft:air", 0);
        int next = 1;
        for (int i = 0; i < pal.size(); i++) {
            String id = pal.get(i).getAsString();
            if (id.equals("minecraft:air")) { remap.put(i, 0); continue; }
            if (!palette.contains(id)) palette.putInt(id, next++);
            remap.put(i, palette.getInt(id));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < data.size(); i++) {
            int v = remap.getOrDefault(data.get(i).getAsInt(), 0);
            while ((v & ~0x7F) != 0) { bytes.write((v & 0x7F) | 0x80); v >>>= 7; }
            bytes.write(v);
        }
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.putShort("Width", (short) w);
        root.putShort("Height", (short) h);
        root.putShort("Length", (short) d);
        root.put("Palette", palette);
        root.putInt("PaletteMax", next);
        root.putByteArray("BlockData", bytes.toByteArray());
        root.putIntArray("Offset", new int[]{0, 0, 0});
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);
    }

    // ------------------------------------------------------------------ palette

    /** Every full, solid, placeable block with its front texture and average colour. */
    private static String buildPalette() {
        Minecraft mc = Minecraft.getInstance();
        JsonArray out = new JsonArray();
        RandomSource rand = RandomSource.create(0);
        for (Block b : BuiltInRegistries.BLOCK) {
            try {
                BlockState s = b.defaultBlockState();
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
                String path = id.getPath();
                if (b.asItem() == Items.AIR || s.hasBlockEntity() || b instanceof FallingBlock) continue;
                if (!s.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) continue;
                if (path.contains("ore") && !path.contains("core") || path.contains("infested") || path.contains("command")
                        || path.contains("structure") || path.contains("jigsaw") || path.contains("bedrock") || path.contains("budding")
                        || path.contains("spawner") || path.contains("barrier") || path.contains("reinforced") || path.contains("trial")
                        || path.contains("vault") || path.contains("redstone_lamp") || path.contains("tnt")) continue;
                var model = mc.getBlockRenderer().getBlockModel(s);
                List<BakedQuad> quads = model.getQuads(s, Direction.NORTH, rand);
                TextureAtlasSprite sprite = quads.isEmpty() ? model.getParticleIcon() : quads.get(0).getSprite();
                ResourceLocation tex = sprite.contents().name();
                var res = mc.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(tex.getNamespace(), "textures/" + tex.getPath() + ".png"));
                if (res.isEmpty()) continue;
                byte[] png;
                int[] avg;
                try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                    int size = img.getWidth(); // animated textures are a vertical strip: use the first frame
                    long r = 0, g = 0, bl = 0, n = 0;
                    try (NativeImage frame = new NativeImage(size, size, false)) {
                        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                            int abgr = img.getPixelRGBA(x, Math.min(y, img.getHeight() - 1));
                            frame.setPixelRGBA(x, y, abgr | 0xFF000000);
                            if ((abgr >>> 24) < 128) continue;
                            r += abgr & 0xFF;
                            g += (abgr >> 8) & 0xFF;
                            bl += (abgr >> 16) & 0xFF;
                            n++;
                        }
                        if (n == 0) continue;
                        avg = new int[]{(int) (r / n), (int) (g / n), (int) (bl / n)};
                        png = frame.asByteArray();
                    }
                }
                // tinted textures (grass, leaves) are grey on disk: skip them, the colour would be wrong
                if (quads.stream().anyMatch(BakedQuad::isTinted)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", id.toString());
                o.addProperty("name", b.getName().getString());
                JsonArray rgb = new JsonArray();
                for (int c : avg) rgb.add(c);
                o.add("rgb", rgb);
                o.addProperty("group", group(path));
                o.addProperty("tex", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
                out.add(o);
            } catch (Exception ignored) {
                // a modded block with an unusual model: just leave it out
            }
        }
        return out.toString();
    }

    private static String group(String p) {
        if (p.endsWith("_wool")) return "Wool";
        if (p.endsWith("_concrete")) return "Concrete";
        if (p.contains("glazed")) return "Glazed terracotta";
        if (p.contains("terracotta")) return "Terracotta";
        if (p.contains("planks") || p.contains("log") || p.contains("wood") || p.contains("stem") || p.contains("hyphae") || p.contains("bamboo")) return "Wood";
        if (p.contains("stone") || p.contains("deepslate") || p.contains("brick") || p.contains("andesite") || p.contains("diorite")
                || p.contains("granite") || p.contains("tuff") || p.contains("blackstone") || p.contains("basalt") || p.contains("calcite")
                || p.contains("prismarine") || p.contains("purpur") || p.contains("quartz") || p.contains("netherrack") || p.contains("end_stone")) return "Stone";
        if (p.contains("copper")) return "Copper";
        return "Other";
    }
}
