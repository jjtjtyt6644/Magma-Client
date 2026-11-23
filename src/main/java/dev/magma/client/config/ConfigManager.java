package dev.magma.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.magma.client.MagmaClient;
import dev.magma.client.gui.ClickGuiScreen;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loading;
    /** Saving is only allowed after the first load, so constructing modules with defaults can't clobber the user's file. */
    private static boolean loaded;

    private ConfigManager() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("magmaclient.json");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) {
            loaded = true;
            save(); // first run: write the defaults (only LavaGuard on)
            return;
        }
        loading = true;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject mods = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
            for (Module m : ModuleManager.all()) {
                String key = m.getName();
                if (!mods.has(key) && key.equals("AutoMiner") && mods.has("QuartzMiner")) key = "QuartzMiner"; // renamed
                if (mods.has(key)) m.fromJson(mods.getAsJsonObject(key));
            }
            if (root.has("gui")) ClickGuiScreen.loadLayout(root.getAsJsonObject("gui"));
        } catch (Exception e) {
            MagmaClient.LOGGER.error("[Magma] Failed to load config", e);
        } finally {
            loading = false;
            loaded = true;
        }
    }

    public static void save() {
        if (loading || !loaded || !ModuleManager.ready()) return;
        JsonObject root = new JsonObject();
        JsonObject mods = new JsonObject();
        for (Module m : ModuleManager.all()) mods.add(m.getName(), m.toJson());
        root.add("modules", mods);
        root.add("gui", ClickGuiScreen.saveLayout());
        try {
            Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MagmaClient.LOGGER.error("[Magma] Failed to save config", e);
        }
    }
}
