package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.ModeSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.setting.Setting;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.OreScanner;
import dev.magma.client.util.Theme;
import dev.magma.client.util.WorldRender;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Xray extends Module {
    @Override public java.util.List<Class<? extends dev.magma.client.module.Module>> addons() { return java.util.List.of(Fullbright.class); }

    public final ModeSetting mode = add(new ModeSetting("Mode", "ESP = outline ores through walls. Classic = hide every other block.", "ESP", "ESP", "Classic", "Both"));
    public final ModeSetting preset = add(new ModeSetting("Ores", "Which ores to show. Custom lets you pick each one.", "All Ores", "All Ores", "Overworld", "Nether", "Custom"));
    public final NumberSetting range = add(new NumberSetting("Range", "Scan radius in blocks (ESP).", 32, 8, 64, 1));
    public final ModeSetting style = add(new ModeSetting("Style", "How ores are outlined.", "Glow", "Glow", "Outline", "Corners", "None"));
    public final BoolSetting models = add(new BoolSetting("Show Ore Blocks", "Draw the real ore blocks on top of the world so you can see them through walls.", true));
    public final NumberSetting blockOpacity = add(new NumberSetting("Block Opacity", "How solid the see-through ore blocks are (lower = you can see mobs behind them).", 55, 15, 100, 5));
    public final BoolSetting pulse = add(new BoolSetting("Pulse", "Ores gently breathe / pulse.", true));
    public final NumberSetting fillAlpha = add(new NumberSetting("Fill Opacity", "Colored fill inside the outline (0 = none).", 0, 0, 160, 5));
    public final NumberSetting maxShown = add(new NumberSetting("Max Ores", "Max ores drawn at once (performance).", 200, 50, 1000, 50));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines from your crosshair to ores.", false));
    public final BoolSetting lava = add(new BoolSetting("Show Lava", "Keep lava visible in Classic mode.", true));
    public final BoolSetting spawners = add(new BoolSetting("Spawners", "Also highlight mob spawners.", false));
    private final java.util.function.BooleanSupplier custom = () -> preset.is("Custom");
    public final BoolSetting quartz = add(new BoolSetting("Quartz", "Nether quartz ore.", true).visibleWhen(custom));
    public final BoolSetting debris = add(new BoolSetting("Ancient Debris", "Ancient debris.", true).visibleWhen(custom));
    public final BoolSetting gilded = add(new BoolSetting("Gilded Blackstone", "Gilded blackstone (gold nuggets).", false).visibleWhen(custom));
    public final BoolSetting gold = add(new BoolSetting("Gold", "Gold, deepslate gold and nether gold ore.", true).visibleWhen(custom));
    public final BoolSetting diamond = add(new BoolSetting("Diamond", "Diamond ore.", true).visibleWhen(custom));
    public final BoolSetting emerald = add(new BoolSetting("Emerald", "Emerald ore.", true).visibleWhen(custom));
    public final BoolSetting iron = add(new BoolSetting("Iron", "Iron ore.", true).visibleWhen(custom));
    public final BoolSetting redstone = add(new BoolSetting("Redstone", "Redstone ore.", true).visibleWhen(custom));
    public final BoolSetting lapis = add(new BoolSetting("Lapis", "Lapis ore.", true).visibleWhen(custom));
    public final BoolSetting coal = add(new BoolSetting("Coal", "Coal ore.", false).visibleWhen(custom));
    public final BoolSetting copper = add(new BoolSetting("Copper", "Copper ore.", false).visibleWhen(custom));
    public final BoolSetting modded = add(new BoolSetting("Modded Ores", "Ores added by your other mods (auto-detected).", true).visibleWhen(custom));
    public final BoolSetting amethyst = add(new BoolSetting("Amethyst", "Budding amethyst (geodes).", false).visibleWhen(custom));

    private List<BlockPos> found = new ArrayList<>();
    private int timer;
    private int lastHash;
    private OreScanner.Job scanJob;

    /** Read by the render mixins on the chunk-build thread. */
    public static volatile boolean classicActive;

    public Xray() {
        super("X-Ray", "See every Overworld & Nether ore through walls (ESP outlines or classic X-Ray).", Category.RENDER, GLFW.GLFW_KEY_X);
        advanced(fillAlpha, style, models, blockOpacity, pulse, maxShown, tracers, lava, spawners);
    }

    @Override public String getInfo() { return mode.get() + " " + found.size(); }

    /** Sodium/Embeddium replace vanilla chunk meshing, so the Classic (hide-other-blocks) trick can't work there. */
    public static final boolean CLASSIC_SUPPORTED = !net.neoforged.fml.ModList.get().isLoaded("sodium")
            && !net.neoforged.fml.ModList.get().isLoaded("embeddium");

    public boolean isClassic() { return CLASSIC_SUPPORTED && (mode.is("Classic") || mode.is("Both")); }
    public boolean isEsp() { return mode.is("ESP") || mode.is("Both") || !CLASSIC_SUPPORTED; }

    private int settingsHash() {
        int h = 1;
        for (Setting<?> s : getSettings()) if (s != range && s != tracers && s != fillAlpha && s != blockOpacity && s != style && s != models && s != pulse && s != maxShown) h = 31 * h + Objects.hashCode(s.get());
        return h;
    }

    @Override
    protected void onEnable() {
        if (!CLASSIC_SUPPORTED && !mode.is("ESP")) {
            dev.magma.client.util.Notify.show("Sodium detected: Classic X-Ray unavailable, using ESP", Theme.WARN);
        }
        timer = 0;
        lastHash = settingsHash();
        classicActive = isClassic();
        if (classicActive) reloadChunks();
    }

    @Override
    protected void onDisable() {
        boolean was = classicActive;
        classicActive = false;
        found = new ArrayList<>();
        if (was) {
            reloadChunks();
            if (!ModuleManager.get(Fullbright.class).isEnabled()) Fullbright.remove();
        }
    }

    private void reloadChunks() {
        if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
    }

    private static boolean overworldOre(BlockState s) {
        return s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
                || (s.is(BlockTags.GOLD_ORES) && !s.is(Blocks.NETHER_GOLD_ORE)) || s.is(BlockTags.REDSTONE_ORES)
                || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES);
    }

    private static boolean netherOre(BlockState s) {
        return s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.NETHER_GOLD_ORE) || s.is(Blocks.ANCIENT_DEBRIS) || s.is(Blocks.GILDED_BLACKSTONE);
    }

    public boolean isTarget(BlockState s) {
        if (spawners.get() && s.is(Blocks.SPAWNER)) return true;
        switch (preset.get()) {
            case "All Ores": return overworldOre(s) || netherOre(s) || dev.magma.client.util.OreTypes.isModdedOre(s);
            case "Overworld": return overworldOre(s);
            case "Nether": return netherOre(s);
            default: break;
        }
        if (quartz.get() && s.is(Blocks.NETHER_QUARTZ_ORE)) return true;
        if (debris.get() && s.is(Blocks.ANCIENT_DEBRIS)) return true;
        if (gilded.get() && s.is(Blocks.GILDED_BLACKSTONE)) return true;
        if (gold.get() && s.is(BlockTags.GOLD_ORES)) return true;
        if (diamond.get() && s.is(BlockTags.DIAMOND_ORES)) return true;
        if (emerald.get() && s.is(BlockTags.EMERALD_ORES)) return true;
        if (iron.get() && s.is(BlockTags.IRON_ORES)) return true;
        if (redstone.get() && s.is(BlockTags.REDSTONE_ORES)) return true;
        if (lapis.get() && s.is(BlockTags.LAPIS_ORES)) return true;
        if (coal.get() && s.is(BlockTags.COAL_ORES)) return true;
        if (copper.get() && s.is(BlockTags.COPPER_ORES)) return true;
        if (amethyst.get() && s.is(Blocks.BUDDING_AMETHYST)) return true;
        if (modded.get() && dev.magma.client.util.OreTypes.isModdedOre(s)) return true;
        return false;
    }

    /** Whether a block should stay visible in Classic mode. */
    public static boolean classicVisible(BlockState s) {
        Xray x = ModuleManager.get(Xray.class);
        if (x.isTarget(s)) return true;
        Block b = s.getBlock();
        if (x.lava.get() && (b == Blocks.LAVA || b == Blocks.MAGMA_BLOCK)) return true;
        return b == Blocks.BEDROCK || b == Blocks.SPAWNER || b == Blocks.CHEST;
    }

    public static int colorFor(BlockState s) {
        if (s.is(Blocks.NETHER_QUARTZ_ORE)) return 0xFFF2E6E0;
        if (s.is(Blocks.ANCIENT_DEBRIS)) return 0xFFA0623C;
        if (s.is(Blocks.GILDED_BLACKSTONE)) return 0xFFE0B040;
        if (s.is(BlockTags.GOLD_ORES)) return 0xFFFFD43B;
        if (s.is(BlockTags.DIAMOND_ORES)) return 0xFF4FE3F0;
        if (s.is(BlockTags.EMERALD_ORES)) return 0xFF3BE07A;
        if (s.is(BlockTags.IRON_ORES)) return 0xFFD8AF93;
        if (s.is(BlockTags.REDSTONE_ORES)) return 0xFFFF3B3B;
        if (s.is(BlockTags.LAPIS_ORES)) return 0xFF3B6BFF;
        if (s.is(BlockTags.COPPER_ORES)) return 0xFFE07B3B;
        if (s.is(BlockTags.COAL_ORES)) return 0xFF4A4A4A;
        if (s.is(Blocks.BUDDING_AMETHYST)) return 0xFFB57CFF;
        if (s.is(Blocks.SPAWNER)) return 0xFFFF55AA;
        // modded ores: a stable colour per block
        int h = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).hashCode();
        return ColorUtil.hsb((h & 0xFFFF) / 65535f, 0.65f, 1f);
    }

    /** Per-ore counts of the last scan, for the menu dashboard. */
    public java.util.Map<String, Integer> counts() {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (BlockPos p : found) {
            if (!isTarget(mc.level.getBlockState(p))) continue; // only what's actually shown
            String n = mc.level.getBlockState(p).getBlock().getName().getString();
            m.merge(n, 1, Integer::sum);
        }
        return m;
    }

    @Override
    public void onTick() {
        int h = settingsHash();
        if (h != lastHash) {
            lastHash = h;
            boolean wantClassic = isClassic();
            if (wantClassic || classicActive) {
                classicActive = wantClassic;
                reloadChunks();
            }
            timer = 0;
            scanJob = null;
        }
        if (classicActive) Fullbright.apply();

        if (isEsp()) {
            // spread the scan over several ticks: a few chunk columns per tick instead of everything at once
            if (scanJob == null && --timer <= 0) {
                scanJob = new OreScanner.Job(mc.player.blockPosition(), range.getInt(), range.getInt() * 2, this::isTarget, 1500);
            }
            if (scanJob != null && scanJob.step(mc.level, 3)) {
                found = scanJob.result();
                scanJob = null;
                timer = 10;
            }
        } else if (!isEsp() && !found.isEmpty()) {
            found = new ArrayList<>();
        }
    }

    public List<BlockPos> getFound() { return found; }

    @Override
    public void onRenderLast(RenderLevelStageEvent event) {
        if (!isEsp() || found.isEmpty()) return;
        Vec3 cam = event.getCamera().getPosition();
        List<BlockPos> list = new ArrayList<>();
        int max = maxShown.getInt();
        for (BlockPos p : found) {
            if (list.size() >= max) break;
            if (isTarget(mc.level.getBlockState(p))) list.add(p);
        }
        // models are the expensive part: only draw them for the closest 120
        if (list.isEmpty()) return;
        long now = System.currentTimeMillis();
        float wave = pulse.get() ? (float) (0.5 + 0.5 * Math.sin(now / 320.0)) : 1f;

        // 1) real ore blocks, drawn over the world (depth cleared so walls don't hide them)
        if (models.get()) renderOreModels(event, list.size() > 120 ? list.subList(0, 120) : list, cam, wave);

        // 2) glow / outline on top
        if (style.is("None") && fillAlpha.getInt() == 0 && !tracers.get()) return;
        Vec3 eye = cam.add(Vec3.directionFromRotation(event.getCamera().getXRot(), event.getCamera().getYRot()).scale(0.5));
        double r = range.get();
        WorldRender.draw(event, b -> {
            for (BlockPos p : list) {
                int c = colorFor(mc.level.getBlockState(p));
                double d = Math.sqrt(p.distToCenterSqr(cam));
                float fade = (float) Math.max(0.35, 1.0 - d / (r * 1.4));
                AABB bb = new AABB(p);
                int fill = fillAlpha.getInt() > 0 ? ColorUtil.withAlpha(c, (int) (fillAlpha.getInt() * fade)) : 0;
                switch (style.get()) {
                    case "Glow" -> {
                        int core = (int) ((170 + 70 * wave) * fade);
                        b.box(bb.inflate(0.002), ColorUtil.withAlpha(c, core), fill);
                        b.box(bb.inflate(0.035 + 0.02 * wave), ColorUtil.withAlpha(c, (int) (85 * fade)), 0);
                        b.box(bb.inflate(0.08 + 0.04 * wave), ColorUtil.withAlpha(c, (int) (32 * fade)), 0);
                    }
                    case "Outline" -> b.box(bb.inflate(0.002), ColorUtil.withAlpha(c, (int) (230 * fade)), fill);
                    case "Corners" -> corners(b, bb.inflate(0.01), ColorUtil.withAlpha(c, (int) ((190 + 60 * wave) * fade)), fill);
                    default -> { if (fill != 0) b.box(bb, 0, fill); }
                }
                if (tracers.get()) b.line(eye.x, eye.y, eye.z, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, ColorUtil.withAlpha(c, (int) (140 * fade)));
            }
        });
    }

    private static void corners(WorldRender.Batch b, AABB bb, int c, int fill) {
        double l = 0.28;
        double[] xs = {bb.minX, bb.maxX}, ys = {bb.minY, bb.maxY}, zs = {bb.minZ, bb.maxZ};
        for (double x : xs) for (double y : ys) for (double z : zs) {
            double sx = x == bb.minX ? l : -l, sy = y == bb.minY ? l : -l, sz = z == bb.minZ ? l : -l;
            b.line(x, y, z, x + sx, y, z, c);
            b.line(x, y, z, x, y + sy, z, c);
            b.line(x, y, z, x, y, z + sz, c);
        }
        if (fill != 0) b.box(bb, 0, fill);
    }

    private void renderOreModels(RenderLevelStageEvent event, List<BlockPos> list, Vec3 cam, float wave) {
        org.joml.Matrix4fStack mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.mul(event.getModelViewMatrix());
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix();
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        // wipe depth so ores show through terrain; they still sort correctly against each other
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.Minecraft.ON_OSX);

        var buffers = mc.renderBuffers().bufferSource();
        var dispatcher = mc.getBlockRenderer();
        // every block goes into the translucent layer with reduced alpha, so mobs/players behind ores stay visible
        float opacity = blockOpacity.getFloat() / 100f;
        net.minecraft.client.renderer.MultiBufferSource seeThrough = type ->
                new dev.magma.client.util.AlphaVertexConsumer(buffers.getBuffer(net.minecraft.client.renderer.RenderType.translucent()), opacity);
        com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
        float scale = pulse.get() ? 1.0f + 0.03f * wave : 1.0f;
        for (BlockPos p : list) {
            BlockState s = mc.level.getBlockState(p);
            ps.pushPose();
            ps.translate(p.getX() - cam.x + 0.5, p.getY() - cam.y + 0.5, p.getZ() - cam.z + 0.5);
            ps.scale(scale, scale, scale);
            ps.translate(-0.5, -0.5, -0.5);
            dispatcher.renderSingleBlock(s, ps, seeThrough, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    net.neoforged.neoforge.client.model.data.ModelData.EMPTY, net.minecraft.client.renderer.RenderType.translucent());
            ps.popPose();
        }
        buffers.endBatch();

        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix();
        mv.popMatrix();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }
}
