// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.fml.loading;

public final class FMLEnvironment {
    public static final boolean production = !net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();
    private FMLEnvironment() {}
}
