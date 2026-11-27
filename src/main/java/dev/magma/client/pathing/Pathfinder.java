package dev.magma.client.pathing;

import dev.magma.client.util.LavaUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A* over standing positions (feet block). The player is 2 blocks tall.
 * Moves may tunnel through blocks, but only blocks that are safe to break (no lava behind them).
 */
public final class Pathfinder {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double INF = Double.MAX_VALUE;
    /** Weighted A*: greedier search so long tunnels through rock don't exhaust the node budget. */
    private static final double H_WEIGHT = 3.0;

    private final Level level;
    private final boolean strict;
    private final int maxNodes;
    private double lastCost;
    /** Never path below this Y (e.g. keep above the Nether lava sea). */
    public int minY = Integer.MIN_VALUE;
    /** Extra cost for stepping/digging within 2 blocks of lava when not in strict mode (keeps a safety margin). */
    public double lavaMarginCost = 8;
    /** Extra cost for changing direction - gives straight, tidy tunnels instead of zig-zags. */
    public double turnCost = 0.15; // zig-zags are fine, this only breaks ties toward tidier tunnels
    private final Map<BlockState, Double> breakCache = new HashMap<>();
    // per-search caches: the same cells get asked about thousands of times (this was the main source of lag)
    private final Map<Long, Boolean> passCache = new HashMap<>();
    private final Map<Long, Byte> lavaCache = new HashMap<>(); // 0 = none, 1 = within 2, 2 = touching
    private final Map<Long, Boolean> breakSafeCache = new HashMap<>();
    /** Hard cap on how long one search may run, so the game never stutters. */
    public long timeLimitNanos = 12_000_000L;
    /** How many scaffold blocks the route may place (0 = never place). */
    public int placeBudget = 0;
    /** Spots where placing a block already failed - never plan to place there again this run. */
    public java.util.Set<Long> noPlace = java.util.Set.of();

    /**
     * Can a block actually be placed into this cell? Walk-through isn't enough: torches, rails, signs, buttons,
     * redstone etc. let you walk through but Minecraft won't place a block there (that froze bridging forever).
     */
    public boolean canPlaceInto(BlockPos p) {
        if (noPlace.contains(p.asLong())) return false;
        BlockState s = level.getBlockState(p);
        return s.canBeReplaced() && !LavaUtil.isLava(level, p);
    }

    /** Deepest drop a route may walk off (blocks). */
    public int maxDrop = 3;
    /** false = never dig, only walk/climb/bridge (used by Goto when "Dig Through" is off). */
    public boolean allowDig = true;

    /** Total cost (walk + dig effort) of the last successful search. */
    public double lastCost() { return lastCost; }

    public Pathfinder(Level level, boolean strict, int maxNodes) {
        this.level = level;
        this.strict = strict;
        this.maxNodes = maxNodes;
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final double g, f, h;
        final Node parent;
        int placed;
        /** The block under this node is one the route will place (not in the world yet). */
        boolean floorPlaced;

        Node(BlockPos pos, double g, double h, Node parent) {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.f = g + h;
            this.parent = parent;
        }

        @Override public int compareTo(Node o) { return Double.compare(f, o.f); }
    }

    // ---------------------------------------------------------------- block classification

    public boolean isLava(BlockPos p) { return LavaUtil.isLava(level, p); }

    /** 0 = no lava near, 1 = lava within 2 blocks, 2 = touching lava. Cached. */
    private byte lavaLevel(BlockPos p) {
        return lavaCache.computeIfAbsent(p.asLong(), k -> LavaUtil.touchesLava(level, p) ? (byte) 2 : LavaUtil.nearLavaStrict(level, p) ? (byte) 1 : (byte) 0);
    }

    /** No collision and no dangerous fluid / block. */
    public boolean passable(BlockPos p) {
        return passCache.computeIfAbsent(p.asLong(), k -> passableRaw(p));
    }

    public boolean isWater(BlockPos p) {
        return level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER);
    }

    private boolean passableRaw(BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!s.getFluidState().isEmpty()) {
            // water you can wade through is fine; lava (and waterlogged solid blocks) never
            return s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && s.getCollisionShape(level, p).isEmpty();
        }
        if (s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.COBWEB) || s.is(Blocks.WITHER_ROSE)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)) return false;
        return s.getCollisionShape(level, p).isEmpty();
    }

    /** Safe to mine: breakable, not a container, nothing lava-related behind it, no gravel above. */
    public boolean canBreakSafely(BlockPos p) {
        return breakSafeCache.computeIfAbsent(p.asLong(), k -> canBreakSafelyRaw(p));
    }

    private boolean canBreakSafelyRaw(BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false;
        float hardness = s.getDestroySpeed(level, p);
        if (hardness < 0 || hardness > 30) return false; // bedrock / obsidian-tier
        if (s.hasBlockEntity()) return false;
        if (level.getBlockState(p.above()).getBlock() instanceof FallingBlock) return false;
        byte lv = lavaLevel(p);
        if (lv == 2) return false;
        if (strict && lv == 1) return false;
        return LavaUtil.breakDanger(level, p, null, strict) == null;
    }

    /** Cost to make the cell walk-through: 0 if open, break cost if mineable, INF if impossible/unsafe. */
    public double cellCost(BlockPos p) {
        if (passable(p)) {
            byte lv = lavaLevel(p);
            if (lv == 2) return INF;
            if (isWater(p)) return 2.5; // wading is slow
            return lv == 1 ? (strict ? lavaMarginCost * 2 : lavaMarginCost) : 0;
        }
        if (!allowDig || !canBreakSafely(p)) return INF;
        // water right above pours in and floods the tunnel (that's how people drowned); water beside it is risky
        if (isWater(p.above())) return INF;
        double c = breakCost(level.getBlockState(p), p);
        if (isWater(p.north()) || isWater(p.south()) || isWater(p.east()) || isWater(p.west())) c += 6;
        if (lavaLevel(p) == 1) c += lavaMarginCost;
        return c;
    }

    /**
     * Real time to dig the block with the best tool in the hotbar, in "blocks walked" units
     * (walking one block takes ~4.6 ticks). Includes vanilla's 5-tick pause between blocks.
     */
    private double breakCost(BlockState state, BlockPos p) {
        Double cached = breakCache.get(state);
        if (cached != null) return cached;
        float hardness = state.getDestroySpeed(level, p);
        double ticks;
        if (hardness <= 0) {
            ticks = 1;
        } else {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            float speed = 1f;
            boolean harvest = !state.requiresCorrectToolForDrops();
            if (player != null) {
                for (int i = 0; i < 9; i++) {
                    var st = player.getInventory().getItem(i);
                    speed = Math.max(speed, st.getDestroySpeed(state));
                    if (st.isCorrectToolForDrops(state)) harvest = true;
                }
            }
            double progress = speed / hardness / (harvest ? 30.0 : 100.0);
            ticks = Math.ceil(1.0 / Math.max(progress, 1e-4)) + 5;
        }
        double cost = 1.0 + ticks / 4.6;
        breakCache.put(state, cost);
        return cost;
    }

    /** Walking on these is slow (or sticky): prefer other ground when it's not a big detour. */
    private double floorCost(BlockPos floor) {
        BlockState s = level.getBlockState(floor);
        if (s.is(Blocks.SOUL_SAND) || s.is(Blocks.HONEY_BLOCK)) return 1.5;
        if (s.is(Blocks.SLIME_BLOCK)) return 1.0;
        return 0;
    }

    /** A solid, harmless block to stand on. */
    public boolean solidFloor(BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return false;
        if (s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.CACTUS)
                || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.POINTED_DRIPSTONE)
                || s.is(Blocks.SWEET_BERRY_BUSH)) return false;
        return !s.getCollisionShape(level, p).isEmpty();
    }

    // ---------------------------------------------------------------- search

    /** Path to a node from which {@code ore} is directly adjacent to the feet or head. */
    public List<BlockPos> findToOre(BlockPos start, BlockPos ore) {
        return findToOre(start, ore, true);
    }

    /** @param allowReach false = must end right next to the ore (used after a failed long-reach attempt). */
    public List<BlockPos> findToOre(BlockPos start, BlockPos ore, boolean allowReach) {
        return search(start, n -> touchesOre(n, ore) || (allowReach && inReach(n, ore)), ore, false);
    }

    /** Reach distance used for "can mine it from this spot" goals. */
    public double reach = 4.2;

    /** Standing at n (already open), can we see and reach the ore? Lets paths stop early instead of walking up to it. */
    public boolean inReach(BlockPos n, BlockPos ore) {
        if (!passable(n) || !passable(n.above())) return false;
        net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
        net.minecraft.world.phys.Vec3 aim = dev.magma.client.util.PlayerControl.aimPoint(level, ore, eye);
        if (eye.distanceToSqr(aim) > reach * reach) return false;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(ore);
    }

    /** Best-effort path toward {@code goal}; returns the partial path that got closest. */
    /** Path to any standing spot within {@code dist} blocks horizontally (and 1 vertically) of target - e.g. to pick up items. */
    public List<BlockPos> findNear(BlockPos start, BlockPos target, int dist) {
        return search(start, n -> Math.abs(n.getX() - target.getX()) <= dist && Math.abs(n.getZ() - target.getZ()) <= dist
                && Math.abs(n.getY() - target.getY()) <= 1, target, false);
    }

    /**
     * Distance estimate. Vertical distance is weighted like digging (each block up/down usually costs a dig),
     * so long shafts down/up are found quickly instead of the search wandering sideways until it runs out of budget.
     */
    private double estimate(BlockPos a, BlockPos b) {
        // octile distance: diagonal steps are allowed, so the straight-line "hypotenuse" is the cheapest way
        int dx = Math.abs(a.getX() - b.getX()), dz = Math.abs(a.getZ() - b.getZ());
        double horiz = Math.max(dx, dz) + 0.4142 * Math.min(dx, dz);
        int vert = Math.abs(a.getY() - b.getY());
        return horiz * H_WEIGHT + vert * (allowDig ? 4.0 : 2.5);
    }

    public List<BlockPos> findToward(BlockPos start, BlockPos goal) {
        return search(start, n -> manhattan(n, goal) <= 2, goal, true);
    }

    public static boolean touchesOre(BlockPos n, BlockPos ore) {
        BlockPos head = n.above();
        if (ore.equals(head.above())) return true;
        for (Direction d : HORIZONTAL) {
            if (ore.equals(n.relative(d)) || ore.equals(head.relative(d))) return true;
        }
        return false;
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private List<BlockPos> search(BlockPos start, Predicate<BlockPos> goal, BlockPos target, boolean partial) {
        Job j = new Job(start, goal, target);
        j.step(timeLimitNanos);
        if (j.result != null) return j.result;
        return partial ? j.partialResult() : null;
    }

    /** Resumable A*: call {@link #step} every tick with a small time slice until {@link #done()}. */
    public Job startToOre(BlockPos start, BlockPos ore, boolean allowReach) {
        return new Job(start, n -> touchesOre(n, ore) || (allowReach && inReach(n, ore)), ore);
    }

    public Job startNear(BlockPos start, BlockPos target, int dist) {
        return new Job(start, n -> Math.abs(n.getX() - target.getX()) <= dist && Math.abs(n.getZ() - target.getZ()) <= dist
                && Math.abs(n.getY() - target.getY()) <= 1, target);
    }

    public Job startToward(BlockPos start, BlockPos goal) {
        return new Job(start, n -> manhattan(n, goal) <= 2, goal);
    }

    public final class Job {
        private final PriorityQueue<Node> open = new PriorityQueue<>();
        private final Map<Long, Double> best = new HashMap<>();
        private final Predicate<BlockPos> goal;
        private final BlockPos start, target;
        private final Node startNode;
        private Node closest;
        private int expanded;
        private boolean done;
        private List<BlockPos> result;
        private double cost;

        Job(BlockPos start, Predicate<BlockPos> goal, BlockPos target) {
            this.start = start;
            this.goal = goal;
            this.target = target;
            this.startNode = new Node(start, 0, estimate(start, target), null);
            this.closest = startNode;
            open.add(startNode);
            best.put(start.asLong(), 0.0);
        }

        public boolean done() { return done; }
        public List<BlockPos> result() { return result; }
        public double cost() { return cost; }
        public int expanded() { return expanded; }

        /** Best partial route found so far (toward the target), or null if it got nowhere. */
        public List<BlockPos> partialResult() {
            if (closest == startNode || manhattan(start, target) - manhattan(closest.pos, target) < 3) return null;
            return build(closest);
        }

        /** Run for up to {@code nanos}. Returns true once finished (found, or truly out of options/budget). */
        public boolean step(long nanos) {
            if (done) return true;
            long deadline = System.nanoTime() + nanos;
            int n = 0;
            while (!open.isEmpty() && expanded < maxNodes) {
                if ((n++ & 63) == 0 && System.nanoTime() > deadline) return false;
                Node cur = open.poll();
                Double known = best.get(cur.pos.asLong());
                if (known != null && cur.g > known) continue;
                expanded++;
                if (goal.test(cur.pos)) {
                    lastCost = cost = cur.g;
                    result = build(cur);
                    done = true;
                    return true;
                }
                if (cur.h < closest.h) closest = cur;

            for (Move m : neighbors(cur.pos, cur.floorPlaced)) {
                    if (m.to.getY() < minY) continue;
                    if (isWater(m.to.above())) continue; // never walk with our head underwater
                    if (m.place > 0 && cur.placed + m.place > placeBudget) continue;
                    double g = cur.g + m.cost + floorCost(m.to.below());
                    if (cur.parent != null && turnCost > 0) {
                        int pdx = Integer.signum(cur.pos.getX() - cur.parent.pos.getX()), pdz = Integer.signum(cur.pos.getZ() - cur.parent.pos.getZ());
                        int ndx = Integer.signum(m.to.getX() - cur.pos.getX()), ndz = Integer.signum(m.to.getZ() - cur.pos.getZ());
                        if ((pdx != 0 || pdz != 0) && (ndx != 0 || ndz != 0) && (pdx != ndx || pdz != ndz)) g += turnCost;
                    }
                    long key = m.to.asLong();
                    Double prev = best.get(key);
                    if (prev != null && prev <= g) continue;
                    best.put(key, g);
                    Node nn = new Node(m.to, g, estimate(m.to, target), cur);
                    nn.placed = cur.placed + m.place;
                    nn.floorPlaced = m.place > 0;
                    open.add(nn);
                }
            }
            done = true;
            lastCost = closest.g;
            return true;
        }
    }

    private static List<BlockPos> build(Node n) {
        List<BlockPos> path = new ArrayList<>();
        for (Node c = n; c != null; c = c.parent) path.add(c.pos);
        Collections.reverse(path);
        return path;
    }

    private record Move(BlockPos to, double cost, int place) {
        Move(BlockPos to, double cost) { this(to, cost, 0); }
    }

    private List<Move> neighbors(BlockPos n, boolean floorPlaced) {
        List<Move> out = new ArrayList<>(10);
        boolean standing = floorPlaced || solidFloor(n.below());
        for (Direction d : HORIZONTAL) {
            BlockPos side = n.relative(d);

            // flat walk / tunnel
            if (solidFloor(side.below())) {
                double c = cellCost(side) + cellCost(side.above());
                if (c < INF) out.add(new Move(side, 1 + c));
            } else {
                // bridge: place a block in the gap and walk across (never over/into lava)
                if (placeBudget > 0 && standing && passable(side.below()) && canPlaceInto(side.below()) && lavaLevel(side.below()) < 2 && !isLava(side.below(2))) {
                    double c = cellCost(side) + cellCost(side.above());
                    if (c < INF) out.add(new Move(side, 3.5 + c, 1));
                }
                // drop down 1..3 (only the top two cells may need breaking)
                double c = cellCost(side) + cellCost(side.above());
                if (c < INF && !isLava(side.below())) {
                    for (int k = 1; k <= maxDrop; k++) {
                        BlockPos land = side.below(k);
                        if (!passable(land) || isLava(land) || LavaUtil.touchesLava(level, land)) break;
                        if (solidFloor(land.below())) {
                            out.add(new Move(land, 2 + k * 3.0 + c)); // dropping is a last resort: walking around wins
                            break;
                        }
                        if (isLava(land.below())) break;
                    }
                }
            }

            // step up (jump onto the block in front)
            BlockPos up = side.above();
            if (!passable(side) && solidFloor(side)) {
                double c = cellCost(n.above(2)) + cellCost(up) + cellCost(up.above());
                if (c < INF) out.add(new Move(up, 2 + c));
            }
        }

        // pillar: jump and place a block under ourselves to go straight up
        if (placeBudget > 0 && standing && canPlaceInto(n)) {
            double c = cellCost(n.above(2));
            if (c < INF && lavaLevel(n.above()) < 2) out.add(new Move(n.above(), 4.5 + c, 1));
        }

        // diagonal walk (open ground only): both side squares must be open too, so we never clip a corner
        for (int[] d : DIAGONALS) {
            BlockPos to = n.offset(d[0], 0, d[1]);
            BlockPos sideA = n.offset(d[0], 0, 0), sideB = n.offset(0, 0, d[1]);
            if (!standing || !solidFloor(to.below())) continue;
            if (cellCost(to) != 0 || cellCost(to.above()) != 0) continue;
            if (cellCost(sideA) != 0 || cellCost(sideA.above()) != 0 || cellCost(sideB) != 0 || cellCost(sideB.above()) != 0) continue;
            out.add(new Move(to, 1.4142));
        }

        // dig straight down
        BlockPos below = n.below();
        if (allowDig && !floorPlaced && !passable(below) && canBreakSafely(below) && solidFloor(below.below()) && !isLava(below.below())) {
            double c = cellCost(below);
            if (c < INF) out.add(new Move(below, 14 + c)); // only when there's truly no way around
        }
        return out;
    }
}
