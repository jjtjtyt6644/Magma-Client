package dev.magma.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/** All the "will breaking this let lava reach me?" logic lives here. */
public final class LavaUtil {
    private LavaUtil() {}

    /** Set by LavaGuard: refuse to dig out the block under you if the drop below is taller than this (-1 = off). */
    public static volatile int maxSafeDrop = 3;

    public static boolean isLava(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.LAVA);
    }

    /** Any of the 6 faces touch lava. */
    public static boolean touchesLava(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (isLava(level, pos.relative(d))) return true;
        }
        return false;
    }

    /** Lava within manhattan distance 2 (incl. diagonals) – catches lava one air-gap away. */
    public static boolean nearLavaStrict(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2) continue;
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (isLava(level, m)) return true;
                }
        return false;
    }

    /**
     * Returns a short reason if breaking {@code pos} could let lava flow in or drop the player into lava,
     * otherwise {@code null}.
     */
    public static String breakDanger(Level level, BlockPos pos, Player player, boolean strict) {
        if (touchesLava(level, pos)) return "Lava is directly behind this block";
        if (strict && nearLavaStrict(level, pos)) return "Lava is 2 blocks from this block";

        // Gravel / sand stacked above: breaking it makes the column fall and may open a lava path.
        BlockPos up = pos.above();
        for (int i = 0; i < 12; i++) {
            BlockState s = level.getBlockState(up);
            if (!(s.getBlock() instanceof FallingBlock)) break;
            if (touchesLava(level, up)) return "Falling blocks above touch lava";
            up = up.above();
        }

        // Digging straight down: check what we'd fall into, and how far.
        if (player != null) {
            BlockPos feet = player.blockPosition();
            if (pos.getX() == feet.getX() && pos.getZ() == feet.getZ() && pos.getY() < feet.getY()) {
                BlockPos below = pos.below();
                int air = 0;
                for (int i = 0; i < 24; i++) {
                    if (isLava(level, below)) return "Lava below - you would fall in";
                    if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) break;
                    air++;
                    below = below.below();
                }
                if (maxSafeDrop >= 0 && air > maxSafeDrop) return "Drop of " + (air >= 24 ? "24+" : air) + " blocks below you";
            }
        }
        return null;
    }

    /** Nearest lava source/flow within radius, or null. */
    public static BlockPos nearestLava(Level level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!isLava(level, m)) continue;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = m.immutable();
                    }
                }
        return best;
    }
}
