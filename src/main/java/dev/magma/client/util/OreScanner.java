package dev.magma.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Fast block scanner: skips whole 16^3 sections whose palette can't contain a match. */
public final class OreScanner {
    private OreScanner() {}

    public static List<BlockPos> scan(Level level, BlockPos center, int range, Predicate<BlockState> match, int max) {
        return scan(level, center, range, range, match, max);
    }

    /**
     * Scan a cylinder: {@code range} blocks around horizontally, {@code vRange} up and down.
     * The client has whole chunk columns loaded, so ores far below you can be found even if you're high up.
     */
    public static List<BlockPos> scan(Level level, BlockPos center, int range, int vRange, Predicate<BlockState> match, int max) {
        List<BlockPos> out = new ArrayList<>();
        int r2 = range * range;
        boolean tall = vRange > range;
        int minCX = (center.getX() - range) >> 4, maxCX = (center.getX() + range) >> 4;
        int minCZ = (center.getZ() - range) >> 4, maxCZ = (center.getZ() + range) >> 4;
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - vRange);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + vRange);

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(match)) continue;
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y < minY || y > maxY) continue;
                        int dy = y - center.getY();
                        for (int lx = 0; lx < 16; lx++) {
                            int x = (cx << 4) + lx;
                            int dx = x - center.getX();
                            for (int lz = 0; lz < 16; lz++) {
                                int z = (cz << 4) + lz;
                                int dz = z - center.getZ();
                                if ((tall ? dx * dx + dz * dz : dx * dx + dy * dy + dz * dz) > r2) continue;
                                if (match.test(section.getBlockState(lx, ly, lz))) out.add(new BlockPos(x, y, z));
                            }
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        if (out.size() > max) return new ArrayList<>(out.subList(0, max));
        return out;
    }

    /** Scan spread over several ticks (a few chunk columns per tick) so big X-Ray ranges never stutter. */
    public static final class Job {
        private final BlockPos center;
        private final int range;
        private final Predicate<BlockState> match;
        private final int max;
        private final List<long[]> columns = new ArrayList<>();
        private final List<BlockPos> out = new ArrayList<>();
        private int next;

        private final int vRange;

        public Job(BlockPos center, int range, Predicate<BlockState> match, int max) {
            this(center, range, range, match, max);
        }

        public Job(BlockPos center, int range, int vRange, Predicate<BlockState> match, int max) {
            this.vRange = vRange;
            this.center = center;
            this.range = range;
            this.match = match;
            this.max = max;
            int minCX = (center.getX() - range) >> 4, maxCX = (center.getX() + range) >> 4;
            int minCZ = (center.getZ() - range) >> 4, maxCZ = (center.getZ() + range) >> 4;
            for (int cx = minCX; cx <= maxCX; cx++) for (int cz = minCZ; cz <= maxCZ; cz++) columns.add(new long[]{cx, cz});
            // nearest columns first so results near the player appear quickly
            int pcx = center.getX() >> 4, pcz = center.getZ() >> 4;
            columns.sort(Comparator.comparingLong(c -> (c[0] - pcx) * (c[0] - pcx) + (c[1] - pcz) * (c[1] - pcz)));
        }

        /** Process up to {@code budget} chunk columns. Returns true when finished. */
        public boolean step(Level level, int budget) {
            int r2 = range * range;
            int minY = Math.max(level.getMinBuildHeight(), center.getY() - vRange);
            int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + vRange);
            boolean tall = vRange > range;
            for (int n = 0; n < budget && next < columns.size(); n++, next++) {
                int cx = (int) columns.get(next)[0], cz = (int) columns.get(next)[1];
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(match)) continue;
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y < minY || y > maxY) continue;
                        int dy = y - center.getY();
                        for (int lx = 0; lx < 16; lx++) {
                            int x = (cx << 4) + lx, dx = x - center.getX();
                            for (int lz = 0; lz < 16; lz++) {
                                int z = (cz << 4) + lz, dz = z - center.getZ();
                                if ((tall ? dx * dx + dz * dz : dx * dx + dy * dy + dz * dz) > r2) continue;
                                if (match.test(section.getBlockState(lx, ly, lz))) out.add(new BlockPos(x, y, z));
                            }
                        }
                    }
                }
            }
            return next >= columns.size();
        }

        public List<BlockPos> result() {
            out.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
            return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
        }
    }
}
