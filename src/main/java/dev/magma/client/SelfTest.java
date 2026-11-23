package dev.magma.client;

import dev.magma.client.gui.ClickGuiScreen;
import dev.magma.client.gui.MenuScreen;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.modules.AutoMiner;
import dev.magma.client.modules.ClickGui;
import dev.magma.client.modules.Xray;
import dev.magma.client.util.OreTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.block.Blocks;

/**
 * DEV ONLY (run config "selftest", -Dmagma.selftest=true): checks X-Ray filter logic and screenshots every menu page
 * from the title screen, then quits. Never active in a normal game.
 */
public final class SelfTest {
    public static final boolean ENABLED = Boolean.getBoolean("magma.selftest");
    private static int t = -1;

    private SelfTest() {}

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "magma_" + name + ".png", mc.getMainRenderTarget(), c -> MagmaClient.LOGGER.info("[SELFTEST] shot {}", name));
    }

    private static void check(String what, boolean ok) {
        MagmaClient.LOGGER.info("[SELFTEST] {} {}", ok ? "PASS" : "FAIL", what);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (t < 0) {
            if (!(mc.screen instanceof TitleScreen) || !ModuleManager.ready()) return;
            t = 0;
        }
        t++;
        try {
            Xray x = ModuleManager.get(Xray.class);
            ClickGui cg = ModuleManager.get(ClickGui.class);
            switch (t) {
                case 20 -> {
                    String oldPreset = x.preset.get();
                    boolean oldDia = x.diamond.get();
                    x.preset.set("Custom");
                    x.diamond.set(false);
                    check("X-Ray Custom + diamond off hides diamond ore", !x.isTarget(Blocks.DIAMOND_ORE.defaultBlockState()));
                    check("X-Ray Custom + diamond off hides deepslate diamond", !x.isTarget(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()));
                    x.diamond.set(true);
                    check("X-Ray Custom + diamond on shows diamond ore", x.isTarget(Blocks.DIAMOND_ORE.defaultBlockState()));
                    x.preset.set("Nether");
                    check("X-Ray Nether preset hides diamonds", !x.isTarget(Blocks.DIAMOND_ORE.defaultBlockState()));
                    check("X-Ray Nether preset shows quartz", x.isTarget(Blocks.NETHER_QUARTZ_ORE.defaultBlockState()));
                    x.preset.set(oldPreset);
                    x.diamond.set(oldDia);
                    MagmaClient.LOGGER.info("[SELFTEST] modded ore groups detected: {}", OreTypes.MODDED.size());
                    MagmaClient.LOGGER.info("[SELFTEST] AutoMiner ore options: {}", ModuleManager.get(AutoMiner.class).ores.getOptions().size());
                }
                case 30 -> { cg.advancedSettings.set(false); MenuScreen.testPage(0, null); mc.setScreen(new MenuScreen()); }
                case 60 -> shot(mc, "1_home");
                case 65 -> MenuScreen.testPage(2, ModuleManager.get(AutoMiner.class));
                case 95 -> shot(mc, "2_world_simple");
                case 100 -> cg.advancedSettings.set(true);
                case 130 -> shot(mc, "3_world_advanced");
                case 135 -> { cg.advancedSettings.set(false); MenuScreen.testPage(4, ModuleManager.get(Xray.class)); }
                case 165 -> shot(mc, "4_render_xray");
                case 170 -> MenuScreen.testPage(3, null);
                case 200 -> shot(mc, "5_automation");
                case 205 -> MenuScreen.testPage(6, null);
                case 235 -> shot(mc, "6_appearance");
                case 240 -> MenuScreen.testPage(1, null);
                case 270 -> shot(mc, "7_safety");
                case 275 -> mc.setScreen(new ClickGuiScreen());
                case 305 -> shot(mc, "8_panels");
                case 315 -> {
                    MenuScreen.testPage(0, null);
                    MagmaClient.LOGGER.info("[SELFTEST] DONE");
                    mc.stop();
                }
                default -> {}
            }
        } catch (Throwable e) {
            MagmaClient.LOGGER.error("[SELFTEST] CRASH at tick " + t, e);
            mc.stop();
        }
    }
}
