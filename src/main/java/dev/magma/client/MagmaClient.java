package dev.magma.client;

import com.mojang.logging.LogUtils;
import dev.magma.client.config.ConfigManager;
import dev.magma.client.module.ModuleManager;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Loader-neutral core. The NeoForge entry ({@code neoforge/NeoForgeEntry}) and the Fabric entry
 * ({@code fabric/FabricEntry}) both call {@link #init()} at startup and {@link #setup()} once the game is ready.
 */
public final class MagmaClient {
    public static final String MODID = "magmaclient";
    public static final String NAME = "Magma";
    public static final String VERSION = "1.14.2";
    public static final Logger LOGGER = LogUtils.getLogger();
    /** "neoforge" or "fabric": which jar the updater downloads. */
    public static String LOADER = "neoforge";

    private MagmaClient() {}

    /** Register our event listeners. */
    public static void init() {
        NeoForge.EVENT_BUS.register(new ClientEvents());
        NeoForge.EVENT_BUS.register(new dev.magma.client.gui.TitleUpdateCard());
    }

    /** Game is ready: modules, config, updates. */
    public static void setup() {
        ModuleManager.init();
        ConfigManager.load();
        ModuleManager.get(dev.magma.client.modules.Hud.class).applyDefaults();
        dev.magma.client.util.Updater.resumePending();
        var cg = ModuleManager.get(dev.magma.client.modules.ClickGui.class);
        if (cg.checkUpdates.get()) dev.magma.client.util.Updater.checkAsync(cg.autoUpdate.get());
        if (!net.neoforged.fml.loading.FMLEnvironment.production) {
            // dev check: load mixin targets early so injection errors surface at startup
            try {
                Class.forName("net.minecraft.client.multiplayer.MultiPlayerGameMode");
                Class.forName("net.minecraft.client.multiplayer.ClientLevel");
                Class.forName("net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler");
                LOGGER.info("[Magma] Mixin targets verified");
            } catch (Throwable t) {
                LOGGER.error("[Magma] Mixin target check failed", t);
            }
        }
        LOGGER.info("[Magma] Loaded {} modules ({}). Press Right Shift to open the ClickGUI.", ModuleManager.all().size(), LOADER);
    }
}
