package dev.magma.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Shared helpers for modules that drive the player (aiming, forced mining, tool selection). */
public final class PlayerControl {
    private PlayerControl() {}

    /**
     * When true, vanilla's continueAttack() is told the attack button is held, even if the window
     * isn't focused. Cleared at the end of every client tick before modules run.
     */
    public static volatile boolean forceAttack;

    private static Minecraft mc() { return Minecraft.getInstance(); }

    /** Smoothly rotate toward yaw/pitch (max degrees per tick). */
    public static void rotate(float yaw, float pitch, float maxStep) {
        var p = mc().player;
        float cy = p.getYRot();
        float ny = cy + Mth.clamp(Mth.wrapDegrees(yaw - cy), -maxStep, maxStep);
        float np = p.getXRot() + Mth.clamp(pitch - p.getXRot(), -maxStep, maxStep);
        p.setYRot(ny);
        p.setXRot(Mth.clamp(np, -90f, 90f));
        p.setYHeadRot(ny);
    }

    /** Rotate toward a point; returns true when aim is within ~1.5 degrees. */
    public static boolean lookAt(Vec3 target, float maxStep) {
        var p = mc().player;
        Vec3 d = target.subtract(p.getEyePosition());
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        // directly above/below: the yaw is meaningless and flips wildly -> keep the current one (this caused the spinning)
        float yaw = h < 0.3 ? p.getYRot() : (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
        rotate(yaw, pitch, maxStep);
        return Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 1.5f && Math.abs(pitch - p.getXRot()) < 1.5f;
    }

    /**
     * Best point to aim at to hit {@code pos}: the centre of the closest exposed face that points at the eye.
     * Aiming at the block centre often clips a neighbouring block first, which was a big cause of the miner stalling.
     */
    public static Vec3 aimPoint(Level level, BlockPos pos, Vec3 eye) {
        Vec3 c = Vec3.atCenterOf(pos);
        Vec3 best = c;
        double bd = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
            Vec3 normal = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
            Vec3 fc = c.add(normal.scale(0.49));
            if (eye.subtract(fc).dot(normal) <= 0) continue;
            double dd = fc.distanceToSqr(eye);
            if (dd < bd) {
                bd = dd;
                best = fc;
            }
        }
        return best;
    }

    /** Best hotbar slot for mining {@code state}; skips tools about to break when {@code saveTools}. */
    public static int bestToolSlot(BlockState state, boolean saveTools) {
        return bestToolSlot(state, saveTools ? 8 : -1);
    }

    /**
     * Picks the RIGHT tool for the block, not just any fast item:
     * pickaxe for stone/ores, axe for wood, shovel for dirt/gravel/sand/soul soil, hoe for leaves/wart blocks,
     * shears for wool/leaves. Prefers a tool that actually drops the block, never digs with a sword
     * (except cobwebs) and won't wear down a tool that doesn't speed the block up - an empty hand or a block
     * stack is used instead.
     *
     * @param minDurability tools with this much durability or less are skipped (-1 = don't care)
     */
    public static int bestToolSlot(BlockState state, int minDurability) {
        Inventory inv = mc().player.getInventory();
        int best = inv.selected;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean needsTool = state.requiresCorrectToolForDrops();
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getItem(i);
            if (minDurability >= 0 && st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() <= minDurability) continue;
            float speed = st.getDestroySpeed(state);
            boolean helps = speed > 1.0f;
            boolean drops = !needsTool || st.isCorrectToolForDrops(state);
            double score = speed;
            if (drops) score += 100;                                   // getting the item matters most
            if (st.is(net.minecraft.tags.ItemTags.SWORDS) && !state.is(net.minecraft.world.level.block.Blocks.COBWEB)) score -= 200; // keep swords sharp
            if (st.isDamageableItem() && !helps) score -= 50;          // don't burn durability for nothing
            if (i == inv.selected) score += 0.01;                      // avoid pointless switching on ties
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** True while the server hasn't confirmed our break of this block yet (on lag it can pop back). */
    public static boolean unconfirmed(BlockPos pos) {
        try {
            var lvl = mc().level;
            if (lvl == null) return false;
            var handler = ((dev.magma.client.mixin.ClientLevelAccessor) lvl).magma$getPrediction();
            return ((dev.magma.client.mixin.PredictionHandlerAccessor) handler).magma$pending().containsKey(pos.asLong());
        } catch (Throwable t) {
            return false;
        }
    }

    public static void selectSlot(int slot) {
        if (slot >= 0 && slot < 9) mc().player.getInventory().selected = slot;
    }

    public static void releaseMovement() {
        var o = mc().options;
        o.keyUp.setDown(false);
        o.keyJump.setDown(false);
        o.keySprint.setDown(false);
        o.keyShift.setDown(false);
        o.keyDown.setDown(false);
        o.keyLeft.setDown(false);
        o.keyRight.setDown(false);
        o.keyUse.setDown(false);
        forceAttack = false;
    }
}
