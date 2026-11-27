package dev.magma.client;

import com.mojang.logging.LogUtils;
import dev.magma.client.config.ConfigManager;
import dev.magma.client.module.ModuleManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value = MagmaClient.MODID, dist = Dist.CLIENT)
public class MagmaClient {
    public static final String MODID = "magmaclient";
    public static final String NAME = "Magma";
    public static final String VERSION = "1.11.0";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MagmaClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.register(new ClientEvents());
        NeoForge.EVENT_BUS.register(new dev.magma.client.gui.TitleUpdateCard());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ModuleManager.init();
            ConfigManager.load();
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
            LOGGER.info("[Magma] Loaded {} modules. Press Right Shift to open the ClickGUI.", ModuleManager.all().size());
        });
    }
}
