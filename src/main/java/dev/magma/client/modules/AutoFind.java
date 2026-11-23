package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.ModeSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.Chat;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.Notify;
import dev.magma.client.util.OreScanner;
import dev.magma.client.util.Theme;
import dev.magma.client.util.WorldRender;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds a structure in the loaded world by its signature blocks (nether bricks = fortress, polished blackstone
 * bricks = bastion, ...) and walks there with Goto's safety. If none is loaded yet, explores and keeps scanning.
 * (The client never receives structure positions, so this is the only way without /locate.)
 */
public class AutoFind extends Module {
    @Override public java.util.List<Class<? extends dev.magma.client.module.Module>> addons() { return java.util.List.of(SafeWalk.class, AutoEat.class, Fullbright.class); }

    private record Kind(ResourceKey<Level> dim, int threshold, Set<Block> markers) {}

    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("Fortress", new Kind(Level.NETHER, 24, Set.of(Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS)));
        KINDS.put("Bastion", new Kind(Level.NETHER, 24, Set.of(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
                Blocks.GILDED_BLACKSTONE, Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICK_WALL)));
        KINDS.put("Ruined Portal", new Kind(null, 2, Set.of(Blocks.CRYING_OBSIDIAN)));
        KINDS.put("Nether Portal", new Kind(null, 1, Set.of(Blocks.NETHER_PORTAL)));
        KINDS.put("Village", new Kind(Level.OVERWORLD, 1, Set.of(Blocks.BELL)));
        KINDS.put("Mineshaft", new Kind(Level.OVERWORLD, 8, Set.of(Blocks.RAIL, Blocks.COBWEB)));
        KINDS.put("Dungeon", new Kind(null, 1, Set.of(Blocks.SPAWNER)));
        KINDS.put("Stronghold", new Kind(Level.OVERWORLD, 1, Set.of(Blocks.END_PORTAL_FRAME, Blocks.INFESTED_STONE_BRICKS)));
        KINDS.put("Ocean Monument", new Kind(Level.OVERWORLD, 20, Set.of(Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN)));
        KINDS.put("Ancient City", new Kind(Level.OVERWORLD, 1, Set.of(Blocks.REINFORCED_DEEPSLATE)));
        KINDS.put("Trial Chambers", new Kind(Level.OVERWORLD, 1, Set.of(Blocks.TRIAL_SPAWNER, Blocks.VAULT)));
        KINDS.put("Desert Temple", new Kind(Level.OVERWORLD, 1, Set.of(Blocks.BLUE_TERRACOTTA)));
        KINDS.put("Jungle Temple", new Kind(Level.OVERWORLD, 2, Set.of(Blocks.STICKY_PISTON)));
        KINDS.put("Woodland Mansion", new Kind(Level.OVERWORLD, 40, Set.of(Blocks.DARK_OAK_PLANKS)));
        KINDS.put("End City", new Kind(Level.END, 20, Set.of(Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR)));
    }

    public final ModeSetting structure = add(new ModeSetting("Structure", "What to look for.", "Fortress", KINDS.keySet().toArray(new String[0])));
    public final NumberSetting radius = add(new NumberSetting("Scan Radius", "How far to scan the loaded world (render distance limits this).", 128, 32, 256, 16));
    public final BoolSetting explore = add(new BoolSetting("Explore", "If nothing is loaded nearby, walk outward and keep scanning.", true));
    public final ModeSetting direction = add(new ModeSetting("Explore Direction", "Which way to explore.", "Facing", "Facing", "North", "South", "East", "West").visibleWhen(() -> explore.get()));
    public final BoolSetting dig = add(new BoolSetting("Dig Through", "Allow tunnelling on the way.", true));
    public final dev.magma.client.setting.ButtonSetting next = add(new dev.magma.client.setting.ButtonSetting("Find Next",
            "Skip this one and look for the next structure of the same kind.", "Skip", this::skipCurrent));
    public final BoolSetting marker = add(new BoolSetting("Show Marker", "Draw a beam where the structure was found.", true));

    private OreScanner.Job job;
    /** Structures already found/visited this session (chunk keys) - never sent there twice. */
    private final java.util.Set<Long> visited = new java.util.HashSet<>();
    private float baseYaw;

    private static long chunkKey(BlockPos p) {
        return (((long) (p.getX() >> 4)) << 32) ^ ((p.getZ() >> 4) & 0xFFFFFFFFL);
    }

    private boolean nearVisited(BlockPos p) {
        int cx = p.getX() >> 4, cz = p.getZ() >> 4;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                if (visited.contains((((long) (cx + dx)) << 32) ^ ((cz + dz) & 0xFFFFFFFFL))) return true;
        return false;
    }

    private void skipCurrent() {
        if (!isEnabled() || found == null) return;
        visited.add(chunkKey(found));
        Chat.info("AutoFind: skipping that " + structure.get() + ", looking for the next one.");
        found = null;
        engine().cancelGoto();
        heading = null;
        startScan();
    }
    private BlockPos found, heading;
    private boolean exploring;
    private int rescan, legs, retries;
    private float exploreYaw;
    private String status = "Idle";

    public AutoFind() {
        super("AutoFind", "Find a fortress, bastion, village, stronghold... in the loaded world and walk there safely.", Category.WORLD, -1);
        advanced(radius, direction, dig, marker);
    }

    @Override public boolean persistEnabled() { return false; }

    @Override public String getInfo() { return structure.get() + " · " + status; }

    private AutoMiner engine() { return ModuleManager.get(AutoMiner.class); }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        Kind k = KINDS.get(structure.get());
        if (k.dim != null && mc.level.dimension() != k.dim) {
            String where = k.dim == Level.NETHER ? "the Nether" : k.dim == Level.END ? "the End" : "the Overworld";
            Chat.info("AutoFind: " + structure.get() + "s are only in " + where + ".");
            setEnabled(false);
            return;
        }
        found = null;
        heading = null;
        exploring = false;
        legs = 0;
        retries = 0;
        baseYaw = switch (direction.get()) {
            case "North" -> 180f;
            case "South" -> 0f;
            case "East" -> -90f;
            case "West" -> 90f;
            default -> Math.round(mc.player.getYRot() / 90f) * 90f;
        };
        exploreYaw = baseYaw;
        startScan();
        Chat.info("AutoFind: scanning for " + structure.get() + "...");
    }

    @Override
    protected void onDisable() {
        job = null;
        if (engine().getGotoTarget() != null) engine().cancelGoto();
        status = "Idle";
    }

    private void startScan() {
        Set<Block> markers = KINDS.get(structure.get()).markers;
        job = new OreScanner.Job(mc.player.blockPosition(), radius.getInt(), s -> markers.contains(s.getBlock()), 20000);
        status = "Scanning";
    }

    /** Group marker blocks per chunk and take the nearest chunk with enough of them. */
    private BlockPos pick(List<BlockPos> hits) {
        int threshold = KINDS.get(structure.get()).threshold;
        Map<Long, int[]> perChunk = new HashMap<>(); // count, sumX, sumY, sumZ
        for (BlockPos p : hits) {
            long key = (((long) (p.getX() >> 4)) << 32) ^ ((p.getZ() >> 4) & 0xFFFFFFFFL);
            int[] a = perChunk.computeIfAbsent(key, k -> new int[4]);
            a[0]++; a[1] += p.getX(); a[2] += p.getY(); a[3] += p.getZ();
        }
        BlockPos me = mc.player.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int[] a : perChunk.values()) {
            if (a[0] < threshold) continue;
            BlockPos c = new BlockPos(a[1] / a[0], a[2] / a[0], a[3] / a[0]);
            if (nearVisited(c)) continue;
            double d = c.distSqr(me);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    @Override
    public void onTick() {
        AutoMiner am = engine();
        // drive the movement engine (same safety as Goto) unless AutoMiner/Goto already does
        am.driveIfIdle();

        if (job != null) {
            if (!job.step(mc.level, 6)) return;
            BlockPos hit = pick(job.result());
            job = null;
            if (hit != null && (found == null || !hit.equals(found))) {
                found = hit;
                exploring = false;
                int dist = (int) Math.sqrt(hit.distSqr(mc.player.blockPosition()));
                Chat.info("AutoFind: " + structure.get() + " found at " + hit.toShortString() + " (" + dist + " blocks). Heading there.");
                Notify.show(structure.get() + " found - " + dist + "m", Theme.OK);
                go(hit);
                return;
            }
            if (found == null) {
                if (!explore.get()) {
                    Chat.info("AutoFind: no " + structure.get() + " in the loaded area. Turn on Explore or move somewhere new.");
                    setEnabled(false);
                    return;
                }
                exploreLeg();
            }
            rescan = 100;
            return;
        }

        // travelling: rescan every 5 s while exploring (new chunks load as we go)
        if (exploring && --rescan <= 0) startScan();

        // the trip ended (arrived or gave up)
        if (heading != null && am.getGotoTarget() == null) {
            if (found != null) {
                double d = Math.sqrt(mc.player.blockPosition().distSqr(found));
                if (d <= 32) {
                    visited.add(chunkKey(found));
                    Chat.info("AutoFind: arrived at the " + structure.get() + ".");
                    Notify.show("Arrived at " + structure.get(), Theme.OK);
                    setEnabled(false);
                } else if (++retries <= 3) {
                    status = "Retrying route (" + retries + "/3)";
                    go(found); // the trip stopped short (blocked) - try again from here
                } else {
                    Chat.info("AutoFind: couldn't reach that " + structure.get() + " safely - skipping it.");
                    visited.add(chunkKey(found));
                    found = null;
                    heading = null;
                    retries = 0;
                    startScan();
                }
            } else if (exploring) {
                exploreLeg();
            }
        }
    }

    private void exploreLeg() {
        if (++legs > 12) {
            Chat.info("AutoFind: explored " + (legs - 1) + " legs without finding a " + structure.get() + ". Stopping.");
            setEnabled(false);
            return;
        }
        exploreYaw = baseYaw + 90f * (legs - 1);
        int length = 96 * ((legs + 1) / 2);
        double rad = Math.toRadians(exploreYaw);
        BlockPos me = mc.player.blockPosition();
        BlockPos t = me.offset((int) Math.round(-Math.sin(rad) * length), 0, (int) Math.round(Math.cos(rad) * length));
        exploring = true;
        status = "Exploring (leg " + legs + ")";
        go(t);
    }

    private void go(BlockPos t) {
        heading = t;
        if (found != null) status = "Going to " + structure.get();
        engine().startGoto(t, dig.get(), false, false);
    }

    @Override
    public void onRenderWorld(RenderLevelStageEvent event) {
        AutoMiner am = engine();
        if (!am.isEnabled() && !ModuleManager.get(Goto.class).isEnabled() && am.getGotoTarget() != null) am.onRenderWorld(event);
        if (!marker.get() || found == null) return;
        BlockPos f = found;
        WorldRender.draw(event, b -> {
            int c = Theme.accent();
            b.box(new AABB(f.getX(), f.getY() - 2, f.getZ(), f.getX() + 1, f.getY() + 40, f.getZ() + 1), ColorUtil.withAlpha(c, 200), ColorUtil.withAlpha(c, 30));
        });
    }
}
