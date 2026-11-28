// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.fml.loading;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public enum FMLPaths {
    GAMEDIR, MODSDIR, CONFIGDIR;

    public Path get() {
        Path game = FabricLoader.getInstance().getGameDir();
        return switch (this) {
            case GAMEDIR -> game;
            case MODSDIR -> game.resolve("mods");
            case CONFIGDIR -> FabricLoader.getInstance().getConfigDir();
        };
    }
}
