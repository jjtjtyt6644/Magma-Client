// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.fml;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class ModList {
    private static final ModList INSTANCE = new ModList();
    public static ModList get() { return INSTANCE; }

    public ModFileInfo getModFileById(String id) { return new ModFileInfo(id); }

    public boolean isLoaded(String id) { return FabricLoader.getInstance().isModLoaded(id); }

    public static final class ModFileInfo {
        private final String id;
        ModFileInfo(String id) { this.id = id; }
        public ModFile getFile() { return new ModFile(id); }
    }

    public static final class ModFile {
        private final String id;
        ModFile(String id) { this.id = id; }
        /** The jar Magma was loaded from (null in a dev environment). */
        public Path getFilePath() {
            return FabricLoader.getInstance().getModContainer(id)
                    .flatMap(c -> c.getOrigin().getPaths().stream().findFirst()).orElse(null);
        }
    }
}
