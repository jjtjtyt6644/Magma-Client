package dev.magma.client.neoforge;

import dev.magma.client.MagmaClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** NeoForge entry point (the Fabric jar uses fabric/FabricEntry instead). */
@Mod(value = MagmaClient.MODID, dist = Dist.CLIENT)
public class NeoForgeEntry {
    public NeoForgeEntry(IEventBus modBus, ModContainer container) {
        MagmaClient.LOADER = "neoforge";
        modBus.addListener(FMLClientSetupEvent.class, e -> e.enqueueWork(MagmaClient::setup));
        MagmaClient.init();
    }
}
