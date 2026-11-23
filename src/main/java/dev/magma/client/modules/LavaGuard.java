package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.LavaUtil;
import dev.magma.client.util.Render2D;
import dev.magma.client.util.Theme;
import dev.magma.client.util.WorldRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class LavaGuard extends Module {
    @Override public java.util.List<Class<? extends dev.magma.client.module.Module>> addons() { return java.util.List.of(SafeWalk.class); }

    public final NumberSetting radius = add(new NumberSetting("Warn Radius", "Warn when lava is within this many blocks.", 5, 2, 10, 1));
    public final BoolSetting blockMining = add(new BoolSetting("Block Mining", "Cancel your mouse click on blocks that would let lava in.", true));
    public final BoolSetting strict = add(new BoolSetting("Strict", "Also block when lava is 2 blocks away (catches flowing lava).", false));
    public final BoolSetting sound = add(new BoolSetting("Sound Alert", "Play a warning sound.", true));
    public final BoolSetting vignette = add(new BoolSetting("Red Vignette", "Tint the screen edges red when lava is close.", true));
    public final BoolSetting esp = add(new BoolSetting("Highlight Lava", "Outline nearby lava through walls.", true));
    public final BoolSetting dropGuard = add(new BoolSetting("Drop Guard", "Block mining the block under you when there's a big drop below it.", true));
    public final NumberSetting maxDrop = add(new NumberSetting("Max Safe Drop", "Drops up to this many blocks are allowed (3 = no fall damage).", 3, 1, 20, 1).visibleWhen(() -> dropGuard.get()));
    public final BoolSetting pill = add(new BoolSetting("Distance Indicator", "Show the LAVA distance + direction pill under the crosshair.", true));
    public final BoolSetting popup = add(new BoolSetting("Blocked Popup", "Show the MINING BLOCKED / UNSAFE BLOCK popup.", true));
    public final BoolSetting outlineTarget = add(new BoolSetting("Unsafe Outline", "Red outline on the unsafe block you're looking at.", true));

    private BlockPos nearest;
    private double nearestDist;
    private final List<BlockPos> nearbyLava = new ArrayList<>();
    private BlockPos dangerTarget;
    private String dangerReason;
    private BlockPos blockedPos;
    private long blockedAt;
    private int soundCooldown;
    private int scanTimer;

    public LavaGuard() {
        super("LavaGuard", "Warns you about nearby lava and stops you mining blocks that would release it.", Category.SAFETY, GLFW.GLFW_KEY_L);
        advanced(strict, maxDrop, vignette, esp, pill, popup, outlineTarget);
        setEnabled(true);
    }

    @Override
    public String getInfo() {
        return nearest == null ? null : String.format("%.1fm", nearestDist);
    }

    /** Distance to the nearest lava within the warn radius, or -1. */
    public double nearestLavaDistance() { return nearest == null ? -1 : nearestDist; }

    /** True if the block the crosshair is on must not be mined. */
    public boolean shouldBlockCurrentTarget() {
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        if (isBlocked(pos)) {
            notifyBlocked(pos);
            return true;
        }
        return false;
    }

    public boolean isBlocked(BlockPos pos) {
        if (!blockMining.get() || mc.level == null || mc.player == null) return false;
        if (mc.level.getBlockState(pos).isAir()) return false;
        return LavaUtil.breakDanger(mc.level, pos, mc.player, strict.get()) != null;
    }

    public void notifyBlocked(BlockPos pos) {
        long now = System.currentTimeMillis();
        if (sound.get() && (blockedPos == null || now - blockedAt > 600)) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6f));
        }
        blockedPos = pos;
        blockedAt = now;
    }

    @Override
    public void onTick() {
        LavaUtil.maxSafeDrop = dropGuard.get() ? maxDrop.getInt() : -1;
        if (soundCooldown > 0) soundCooldown--;
        if (++scanTimer >= 4) {
            scanTimer = 0;
            BlockPos p = mc.player.blockPosition();
            int r = radius.getInt();
            nearest = LavaUtil.nearestLava(mc.level, p, r);
            nearbyLava.clear();
            if (nearest != null) {
                nearestDist = Math.sqrt(nearest.distToCenterSqr(mc.player.getX(), mc.player.getY() + 0.5, mc.player.getZ()));
                if (esp.get()) {
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    for (int dx = -r; dx <= r; dx++)
                        for (int dy = -r; dy <= r; dy++)
                            for (int dz = -r; dz <= r; dz++) {
                                m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                                if (LavaUtil.isLava(mc.level, m) && nearbyLava.size() < 600) nearbyLava.add(m.immutable());
                            }
                }
            }
        }

        dangerTarget = null;
        dangerReason = null;
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            if (!mc.level.getBlockState(pos).isAir()) {
                dangerReason = LavaUtil.breakDanger(mc.level, pos, mc.player, strict.get());
                if (dangerReason != null) dangerTarget = pos;
            }
        }

        if (sound.get() && nearest != null && nearestDist < 2.5 && soundCooldown == 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 0.5f));
            soundCooldown = 30;
        }
    }

    @Override
    public void onRenderWorld(RenderLevelStageEvent event) {
        if (nearbyLava.isEmpty() && dangerTarget == null) return;
        float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0));
        WorldRender.draw(event, b -> {
            if (esp.get()) {
                for (BlockPos p : nearbyLava) b.block(p, 0x90FF5A1F, 0x18FF5A1F);
            }
            if (dangerTarget != null && outlineTarget.get()) {
                b.block(dangerTarget, ColorUtil.withAlpha(Theme.DANGER, 160 + (int) (95 * pulse)), ColorUtil.withAlpha(Theme.DANGER, 40 + (int) (40 * pulse)));
            }
        });
    }

    @Override
    public void onRenderHud(GuiGraphics g, float pt) {
        int sw = g.guiWidth(), sh = g.guiHeight();
        var font = mc.font;
        long now = System.currentTimeMillis();
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 150.0));

        if (nearest != null) {
            float closeness = (float) Mth.clamp(1.0 - nearestDist / (radius.get() + 1), 0, 1);
            if (vignette.get()) {
                int a = (int) (closeness * (90 + 60 * pulse));
                int edge = ColorUtil.withAlpha(0xFF2200, a);
                int clear = 0x00FF2200;
                int t = Math.max(20, sh / 6);
                Render2D.vGradient(g, 0, 0, sw, t, edge, clear);
                Render2D.vGradient(g, 0, sh - t, sw, t, clear, edge);
                Render2D.hGradient(g, 0, 0, t, sh, edge, clear);
                Render2D.hGradient(g, sw - t, 0, t, sh, clear, edge);
            }

            if (pill.get()) {
            String arrow = directionArrow(nearest);
            String text = "⚠ LAVA " + String.format("%.1fm", nearestDist) + " " + arrow;
            int tw = font.width(text);
            float x = sw / 2f - tw / 2f - 8, y = sh / 2f + 18;
            int col = ColorUtil.lerp(Theme.WARN, Theme.DANGER, closeness);
            Render2D.shadow(g, x, y, tw + 16, 16, 6, 6, ColorUtil.withAlpha(col, 120));
            Render2D.roundedRect(g, x, y, tw + 16, 16, 6, 0xE0140C0C);
            Render2D.roundedOutline(g, x, y, tw + 16, 16, 6, 1, ColorUtil.withAlpha(col, 140 + (int) (115 * pulse)));
            g.drawString(font, text, (int) (x + 8), (int) (y + 4), col, false);
            }
        }

        boolean recentlyBlocked = blockedPos != null && now - blockedAt < 1500;
        if (popup.get() && (dangerTarget != null || recentlyBlocked)) {
            String head = recentlyBlocked ? "⛔ MINING BLOCKED" : "⛔ UNSAFE BLOCK";
            String reason = dangerReason != null ? dangerReason : "Lava behind this block";
            int w = Math.max(font.width(head), font.width(reason)) + 20;
            float x = sw / 2f - w / 2f, y = sh / 2f - 52;
            float fade = recentlyBlocked ? 1f : 0.85f;
            Render2D.shadow(g, x, y, w, 28, 6, 8, ColorUtil.withAlpha(Theme.DANGER, (int) (90 + 80 * pulse)));
            Render2D.roundedRect(g, x, y, w, 28, 6, ColorUtil.mulAlpha(0xE8200A0A, fade));
            Render2D.roundedGradient(g, x, y, w, 2, 1, Theme.DANGER, Theme.WARN, false);
            g.drawString(font, head, (int) (sw / 2f - font.width(head) / 2f), (int) y + 5, Theme.DANGER, false);
            g.drawString(font, reason, (int) (sw / 2f - font.width(reason) / 2f), (int) y + 16, 0xFFE8C4C4, false);
        }
    }

    private String directionArrow(BlockPos target) {
        double dx = target.getX() + 0.5 - mc.player.getX();
        double dz = target.getZ() + 0.5 - mc.player.getZ();
        double dy = target.getY() + 0.5 - (mc.player.getY() + 1);
        String vert = dy > 1.5 ? " ▲" : dy < -1.5 ? " ▼" : "";
        if (dx * dx + dz * dz < 0.8) return dy > 0 ? "▲ above" : "▼ below";
        double angle = Math.toDegrees(Math.atan2(dz, dx)) - 90 - mc.player.getYRot();
        angle = Mth.wrapDegrees(angle);
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int idx = (int) Math.floorMod(Math.round(angle / 45.0), 8);
        return arrows[idx] + vert;
    }
}
