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
        server.createContext("/api/schematics", EditorServer::listSchematics);
        server.createContext("/api/studio", EditorServer::studio);
        server.createContext("/api/schematic", EditorServer::schematic);
        server.createContext("/three.module.min.js", ex -> {
            try (InputStream in = EditorServer.class.getResourceAsStream("/assets/magmaclient/editor/three.module.min.js")) {
                if (in == null) { send(ex, 404, "text/plain", "missing".getBytes()); return; }
                ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
                byte[] b = in.readAllBytes();
                ex.getResponseHeaders().set("Content-Type", "text/javascript");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            }
        });
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

    /** The studio's autosave (picture + 3D work), kept in the game's config folder so nothing is lost. */
    private static Path studioFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("magma_studio.json");
    }

    /** GET /api/studio : the last session. POST /api/studio : save the current one. */
    private static void studio(HttpExchange ex) throws IOException {
        Path f = studioFile();
        if ("POST".equals(ex.getRequestMethod())) {
            if (!"1".equals(ex.getRequestHeaders().getFirst("X-Magma"))) { send(ex, 403, "text/plain", "forbidden".getBytes()); return; }
            byte[] body = ex.getRequestBody().readNBytes(60 * 1024 * 1024);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("magma_studio.json.tmp");
            Files.write(tmp, body);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            send(ex, 200, "application/json", "{\"ok\":true}".getBytes());
            return;
        }
        byte[] b = Files.isRegularFile(f) ? Files.readAllBytes(f) : "{}".getBytes();
        send(ex, 200, "application/json", b);
    }

    /** GET /api/schematics : the files in the schematics folder(s). */
    private static void listSchematics(HttpExchange ex) throws IOException {
        JsonArray arr = new JsonArray();
        for (Path p : Schematic.list()) arr.add(p.getFileName().toString());
        send(ex, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** GET /api/schematic?name=... : a schematic as voxels, with a texture for every block in it (for the 3D editor). */
    private static void schematic(HttpExchange ex) throws IOException {
        try {
            String q = ex.getRequestURI().getRawQuery();
            if (q == null || !q.startsWith("name=")) throw new IOException("no name");
            String name = java.net.URLDecoder.decode(q.substring(5), StandardCharsets.UTF_8);
            Path file = Schematic.find(name);
            if (file == null) throw new IOException("no schematic called " + name);
            Schematic sc = Schematic.load(file);
            String json = Minecraft.getInstance().submit(() -> {
                JsonObject o = new JsonObject();
                o.addProperty("name", sc.name);
                Map<BlockState, Integer> idx = new LinkedHashMap<>();
                JsonArray pal = new JsonArray(), blocks = new JsonArray();
                for (Map.Entry<BlockPos, BlockState> e : sc.blocks.entrySet()) {
                    BlockState st = e.getValue();
                    if (st.isAir()) continue;
                    Integer i = idx.get(st);
                    if (i == null) {
                        i = idx.size();
                        idx.put(st, i);
                        pal.add(describe(st));
                    }
                    BlockPos p = e.getKey();
                    blocks.add(p.getX()); blocks.add(p.getY()); blocks.add(p.getZ()); blocks.add(i);
                }
                o.add("palette", pal);
                o.add("blocks", blocks); // flat: x, y, z, palette index
                return o.toString();
            }).get();
            send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            send(ex, 400, "text/plain", ("couldn't open it: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
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


    // ------------------------------------------------------------------ block info for the editor

    /** Every placeable block: texture, colour, shape and how it can face. */
    private static String buildPalette() {
        JsonArray out = new JsonArray();
        for (Block b : BuiltInRegistries.BLOCK) {
            try {
                if (b.asItem() == Items.AIR || b instanceof net.minecraft.world.level.block.LiquidBlock) continue;
                String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
                if (path.contains("command_block") || path.startsWith("structure_") || path.equals("jigsaw") || path.equals("barrier")
                        || path.equals("light") || path.equals("debug_stick") || path.contains("piston_head") || path.equals("moving_piston")) continue;
                JsonObject o = entry(b.defaultBlockState());
                if (o != null) out.add(o);
            } catch (Exception ignored) {
                // a modded block with an unusual model: just leave it out
            }
        }
        return out.toString();
    }

    private static final Map<Block, JsonObject> TEX_CACHE = new java.util.HashMap<>();

    /** Everything the editor needs to show and place one block state (game thread only). */
    private static JsonObject describe(BlockState s) {
        JsonObject o = entry(s);
        if (o != null) return o;
        o = new JsonObject();
        o.addProperty("state", net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s));
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
        o.addProperty("name", s.getBlock().getName().getString());
        JsonArray rgb = new JsonArray(); rgb.add(128); rgb.add(128); rgb.add(128);
        o.add("rgb", rgb);
        o.addProperty("tex", "");
        o.addProperty("shape", "full");
        o.addProperty("group", "Other");
        return o;
    }

    private static JsonObject entry(BlockState s) {
        Block b = s.getBlock();
        JsonObject t = TEX_CACHE.computeIfAbsent(b, k -> texture(k.defaultBlockState()));
        if (t == null) return null;
        String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(b).toString());
        o.addProperty("state", s == b.defaultBlockState() ? BuiltInRegistries.BLOCK.getKey(b).toString()
                : net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s));
        o.addProperty("name", b.getName().getString());
        o.add("rgb", t.get("rgb"));
        o.addProperty("tex", t.get("tex").getAsString());
        o.addProperty("noise", t.get("noise").getAsDouble());
        o.addProperty("see", t.get("see").getAsBoolean());
        BlockState d = b.defaultBlockState();
        boolean solid = d.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        // good for pixel art: plain full blocks that stay put and look the same from the front
        boolean art = solid && !d.hasBlockEntity() && !(b instanceof FallingBlock) && !t.get("tinted").getAsBoolean()
                && !(path.contains("ore") && !path.contains("core")) && !path.contains("infested") && !path.contains("bedrock")
                && !path.contains("budding") && !path.contains("spawner") && !path.contains("reinforced") && !path.contains("trial")
                && !path.contains("vault") && !path.contains("redstone_lamp") && !path.contains("tnt") && !path.contains("_log")
                && !path.contains("pumpkin") && !path.contains("furnace") && !path.contains("observer") && !path.contains("dispenser")
                && !path.contains("dropper") && !path.contains("table") && !path.contains("loom") && !path.contains("beehive");
        o.addProperty("art", art);
        // shape, so stairs/slabs/fences look right in the 3D view
        String shape;
        if (b instanceof net.minecraft.world.level.block.SlabBlock) shape = "slab";
        else if (b instanceof net.minecraft.world.level.block.StairBlock) shape = "stairs";
        else if (b instanceof net.minecraft.world.level.block.FenceBlock || b instanceof net.minecraft.world.level.block.WallBlock
                || b instanceof net.minecraft.world.level.block.IronBarsBlock) shape = "post";
        else if (solid || d.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) shape = "full";
        else {
            var vs = d.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (vs.isEmpty()) shape = "small";
            else {
                var bb = vs.bounds();
                shape = "box";
                JsonArray box = new JsonArray();
                box.add(bb.minX); box.add(bb.minY); box.add(bb.minZ); box.add(bb.maxX); box.add(bb.maxY); box.add(bb.maxZ);
                o.add("box", box);
            }
        }
        o.addProperty("shape", shape);
        // how it turns when placed
        if (d.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) o.addProperty("turn", "axis");
        else if (d.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) o.addProperty("turn", b instanceof net.minecraft.world.level.block.StairBlock ? "look" : "face");
        else if (d.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) o.addProperty("turn", "face6");
        o.addProperty("group", group(path, b, shape));
        return o;
    }

    /** {rgb, tex, noise, tinted, see} for a block's front texture; null when it has none. */
    private static JsonObject texture(BlockState s) {
        try {
            Minecraft mc = Minecraft.getInstance();
            var model = mc.getBlockRenderer().getBlockModel(s);
            RandomSource rand = RandomSource.create(0);
            List<BakedQuad> quads = model.getQuads(s, Direction.NORTH, rand);
            if (quads.isEmpty()) quads = model.getQuads(s, null, rand);
            TextureAtlasSprite sprite = quads.isEmpty() ? model.getParticleIcon() : quads.get(0).getSprite();
            boolean tinted = !quads.isEmpty() && quads.get(0).isTinted();
            ResourceLocation tex = sprite.contents().name();
            var res = mc.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(tex.getNamespace(), "textures/" + tex.getPath() + ".png"));
            if (res.isEmpty()) return null;
            try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in);
                 NativeImage frame = new NativeImage(img.getWidth(), img.getWidth(), false)) {
                long r = 0, g = 0, bl = 0, n = 0, clear = 0;
                double sum = 0, sumSq = 0;
                for (int y = 0; y < frame.getHeight(); y++) for (int x = 0; x < frame.getWidth(); x++) {
                    int abgr = img.getPixelRGBA(x, Math.min(y, img.getHeight() - 1));
                    int pr = abgr & 0xFF, pg = (abgr >> 8) & 0xFF, pb = (abgr >> 16) & 0xFF, pa = abgr >>> 24;
                    if (tinted) { // leaves/grass are grey on disk: colour them like the default plains biome
                        pr = pr * 0x59 / 255; pg = pg * 0xAE / 255; pb = pb * 0x30 / 255;
                    }
                    frame.setPixelRGBA(x, y, (pa << 24) | (pb << 16) | (pg << 8) | pr);
                    if (pa < 128) { clear++; continue; }
                    r += pr; g += pg; bl += pb; n++;
                    double lum = 0.299 * pr + 0.587 * pg + 0.114 * pb;
                    sum += lum; sumSq += lum * lum;
                }
                JsonObject o = new JsonObject();
                JsonArray rgb = new JsonArray();
                rgb.add(n == 0 ? 128 : (int) (r / n)); rgb.add(n == 0 ? 128 : (int) (g / n)); rgb.add(n == 0 ? 128 : (int) (bl / n));
                o.add("rgb", rgb);
                double mean = n == 0 ? 0 : sum / n;
                o.addProperty("noise", n == 0 ? 1 : Math.sqrt(Math.max(0, sumSq / n - mean * mean)) / 255.0);
                o.addProperty("tinted", tinted);
                o.addProperty("see", clear > 0);
                o.addProperty("tex", "data:image/png;base64," + Base64.getEncoder().encodeToString(frame.asByteArray()));
                return o;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String group(String p, Block b, String shape) {
        if (p.endsWith("_wool")) return "Wool";
        if (p.endsWith("_concrete")) return "Concrete";
        if (p.contains("glazed")) return "Glazed terracotta";
        if (p.contains("terracotta")) return "Terracotta";
        if (p.contains("glass")) return "Glass";
        if (shape.equals("stairs") || shape.equals("slab")) return "Stairs & slabs";
        if (shape.equals("post") || p.contains("fence") || p.contains("_wall")) return "Fences & walls";
        if (p.contains("door")) return "Doors";
        if (p.contains("leaves") || p.contains("sapling") || p.contains("flower") || p.contains("grass") || p.contains("fern")
                || p.contains("vine") || p.contains("mushroom") || p.contains("moss") || p.contains("azalea") || p.contains("tulip")
                || p.contains("orchid") || p.contains("daisy") || p.contains("rose") || p.contains("lily") || p.contains("bush")) return "Plants";
        if (p.contains("lantern") || p.contains("torch") || p.contains("lamp") || p.contains("glowstone") || p.contains("froglight")
                || p.contains("candle") || p.contains("sea_lantern") || p.contains("shroomlight")) return "Lights";
        if (p.contains("planks") || p.contains("log") || p.contains("wood") || p.contains("stem") || p.contains("hyphae") || p.contains("bamboo")) return "Wood";
        if (p.contains("stone") || p.contains("deepslate") || p.contains("brick") || p.contains("andesite") || p.contains("diorite")
                || p.contains("granite") || p.contains("tuff") || p.contains("blackstone") || p.contains("basalt") || p.contains("calcite")
                || p.contains("prismarine") || p.contains("purpur") || p.contains("quartz") || p.contains("netherrack") || p.contains("end_stone")) return "Stone";
        if (p.contains("copper")) return "Copper";
        return shape.equals("full") ? "Other" : "Decoration";
    }
}
