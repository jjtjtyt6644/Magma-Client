package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.pathing.Pathfinder;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.ModeSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.Chat;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.LavaUtil;
import dev.magma.client.util.OreScanner;
import dev.magma.client.util.PlayerControl;
import dev.magma.client.util.Theme;
import dev.magma.client.util.WorldRender;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Automated quartz mining: X-Ray scan for ore -> lava-safe A* path -> tunnel/walk -> mine -> repeat.
 */
public class AutoMiner extends Module {
    @Override public java.util.List<Class<? extends dev.magma.client.module.Module>> addons() { return java.util.List.of(AutoTool.class, SafeWalk.class, InvCleaner.class, Xray.class, Fullbright.class); }

    @Override public boolean persistEnabled() { return false; }

    public final dev.magma.client.setting.MultiSetting ores = add(new dev.magma.client.setting.MultiSetting("Ores", "Which ores to mine - pick as many as you like.", dev.magma.client.util.OreTypes.ALL, "Quartz"));
    public final ModeSetting speed = add(new ModeSetting("Speed", "Fast = nearest ore, no planning, rips through whole veins. Normal = plans the best route. Human = slower, smooth, human-like.", "Normal", "Fast", "Normal", "Human"));
    public final NumberSetting radius = add(new NumberSetting("Search Radius", "How far to X-Ray for ore.", 24, 8, 48, 1));
    public final BoolSetting strict = add(new BoolSetting("Strict Safety", "Keep 2 blocks away from any lava (safest, skips more ore).", true));
    public final BoolSetting explore = add(new BoolSetting("Explore When Empty", "Tunnel forward safely only when there is no ore at all in range.", true));
    public final BoolSetting autoTool = add(new BoolSetting("Auto Tool", "Use the right tool for each block: pickaxe for stone/ores, axe for wood, shovel for dirt/gravel/sand...", true));
    public final BoolSetting stopFull = add(new BoolSetting("Stop When Full", "Turn off when the inventory is full.", true));
    public final BoolSetting showPath = add(new BoolSetting("Show Path", "Draw the planned route line and target box (off = no lines on screen).", false));
    public final BoolSetting collectDrops = add(new BoolSetting("Collect Drops", "Walk over and pick up the loot before moving on.", true));
    public final NumberSetting collectRadius = add(new NumberSetting("Collect Radius", "Pick up dropped items within this many blocks.", 6, 2, 12, 1).visibleWhen(() -> collectDrops.get()));
    public final BoolSetting veinMine = add(new BoolSetting("Vein Mine", "Finish the whole ore vein before travelling to the next one.", true));
    public final NumberSetting candidates = add(new NumberSetting("Route Options", "How many ores to plan routes for before picking the cheapest (GPS).", 4, 1, 8, 1).visibleWhen(() -> !speed.is("Fast")));
    public final ModeSetting pickSlot = add(new ModeSetting("Pickaxe Slot", "Hotbar slot of the pickaxe to use (Auto = best tool).", "Auto", "Auto", "1", "2", "3", "4", "5", "6", "7", "8", "9"));
    public final BoolSetting defend = add(new BoolSetting("Auto Defend", "Swing your sword at hostile mobs that come close, then resume.", true));
    public final ModeSetting swordSlot = add(new ModeSetting("Sword Slot", "Hotbar slot of the weapon (Auto = strongest sword/axe).", "Auto", "Auto", "1", "2", "3", "4", "5", "6", "7", "8", "9"));
    public final dev.magma.client.setting.MultiSetting mobTargets = add(new dev.magma.client.setting.MultiSetting("Mob Targets",
            "Which mobs Auto Defend fights. Unticked neutral mobs are still fought if they attack you.",
            List.of("Hostiles", "Piglins", "Piglin Brutes", "Zombified Piglins", "Endermen", "Hoglins"), "Hostiles", "Hoglins"));
    public final NumberSetting defendRange = add(new NumberSetting("Defend Range", "Fight mobs within this many blocks.", 4.5, 2, 8, 0.5));
    public final ModeSetting rangedMode = add(new ModeSetting("Ranged Mobs", "Skeletons/pillagers shooting from afar. Chase = run over on a safe path and kill. Cover = back out of their sight. Ignore = keep mining.", "Chase", "Chase", "Cover", "Ignore"));
    public final NumberSetting rangedRange = add(new NumberSetting("Ranged Range", "React to shooters within this many blocks.", 16, 6, 32, 1));
    public final BoolSetting placeBlocks = add(new BoolSetting("Place Blocks", "Pillar up and bridge gaps using throwaway blocks (netherrack, cobble, dirt...) from your hotbar.", true));
    public final BoolSetting returnHome = add(new BoolSetting("Return To Base When Full", "When the inventory is full, walk back to your Base (set it in the Base module) instead of just stopping.", false));
    public final BoolSetting bigVeins = add(new BoolSetting("Prefer Big Veins", "When choosing the next ore, favour ones with more ore around them.", true));
    public final BoolSetting avoidLavaSea = add(new BoolSetting("Avoid Lava Sea", "In the Nether, never route down to the lava ocean level (Y 32 and below).", true));
    public final NumberSetting stopAfter = add(new NumberSetting("Stop After", "Turn off after mining this many ores this run (0 = never).", 0, 0, 1024, 8));
    public final BoolSetting toolSaver = add(new BoolSetting("Tool Saver", "Stop mining (or switch tools) before a pickaxe or weapon breaks.", true));
    public final NumberSetting minDurability = add(new NumberSetting("Min Durability", "Durability left at which a tool counts as 'about to break'.", 12, 2, 100, 1).visibleWhen(() -> toolSaver.get()));
    public final BoolSetting autoEat = add(new BoolSetting("Auto Eat", "Eat to keep health regenerating; golden apple in an emergency.", true));
    public final NumberSetting eatHp = add(new NumberSetting("Eat Below HP", "Top up food when health is under this.", 16, 4, 20, 1).visibleWhen(() -> autoEat.get()));
    public final ModeSetting afterDeath = add(new ModeSetting("After Death", "What to do if you die. Return = respawn, walk to the nearest Nether portal and keep mining.", "Return", "Stop", "Return"));
    public final BoolSetting deathSpot = add(new BoolSetting("Go To Death Spot", "After returning to the Nether, head back toward where you died first.", false).visibleWhen(() -> afterDeath.is("Return")));
    public final BoolSetting pauseOnInput = add(new BoolSetting("Pause On Input", "Pause for a few seconds whenever you press a movement key yourself.", true));
    public final NumberSetting lowHp = add(new NumberSetting("Pause At HP", "Pause mining when health drops to this (0 = never). Resumes when healed.", 6, 0, 18, 1));
    public final BoolSetting oreSound = add(new BoolSetting("Ore Sound", "Play a ding each time an ore is mined.", false));
    public final dev.magma.client.setting.ButtonSetting resetTotal = add(new dev.magma.client.setting.ButtonSetting("Total Mined", "All-time ores mined. Only resets when you press Reset.", "Reset", this::resetTotal));
    public final NumberSetting effort = add(new NumberSetting("Search Effort", "Max pathfinding nodes (higher = smarter but can lag).", 20000, 4000, 60000, 2000));

    private enum State { SEARCH, FOLLOW, MINE_ORE, COLLECT, FIND_PORTAL, ENTER_PORTAL, RETURN_DEATH }

    /** Where FOLLOW goes when a path with no ore at the end finishes. */
    private State afterPath = State.SEARCH;
    private BlockPos deathPos, portal;
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> deathDim;
    private boolean dead;
    private int deathTicks, portalTicks, deathTrips, portalFails;
    private OreScanner.Job portalJob;
    private boolean eating;
    private int eatPrevSlot = -1;
    private long inputPauseUntil;
    // loot pickup
    private int collectTries, collectWait;
    private final Set<Integer> ignoredItems = new HashSet<>();
    // go-to-coordinates
    private BlockPos gotoTarget;
    private boolean gotoOwns, gotoDig = true, gotoMine, gotoExactY, finishingGoto, returningHome;
    private int gotoFails;

    private State state = State.SEARCH;
    private List<BlockPos> path;
    private int idx;
    private BlockPos ore;
    private final Map<Long, Integer> blacklist = new HashMap<>();
    private final Set<BlockPos> unsafeOres = new HashSet<>();
    private int cooldown, walkStuck, wait, aimMiss, mined, replans;
    private BlockPos breaking;
    private int breakTicks;
    private int lastIdx = -1;
    private Vec3 lastPos = Vec3.ZERO;
    private String status = "Idle";
    private float exploreYaw;
    private boolean hasExploreYaw;
    private BlockPos lastOre;
    private List<BlockPos> cands;
    private Pathfinder.Job job;
    private BlockPos jobOre;
    private List<BlockPos> jobPartial;
    private Pathfinder.Job gotoJob;
    private int candIdx;
    private List<BlockPos> bestPath;
    private BlockPos bestOre, firstSafe;
    private double bestCost;
    private net.minecraft.world.entity.LivingEntity threat;
    private int routeLen;
    private long totalMined;
    private long sessionStart;
    private int humanDelay;
    private int recenter, stuckRounds, stepJumps, stepPause, sprintScore;

    private void stopSprint() {
        sprintScore = 0;
        mc.options.keySprint.setDown(false);
        if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }
    private BlockPos aimFor;
    private Vec3 aimCache = Vec3.ZERO;
    /** Ores we failed to hit from a distance: next attempt walks right up to them. */
    private final Set<BlockPos> adjacentOnly = new HashSet<>();

    private List<BlockPos> route(Pathfinder pf, BlockPos start, BlockPos o) {
        return pf.findToOre(start, o, !adjacentOnly.contains(o));
    }

    /** Couldn't hit the block: for the ore itself, reposition next to it before ever giving up on it. */
    private void cantReach(BlockPos pos) {
        if (ore != null && pos.equals(ore) && adjacentOnly.add(pos)) {
            replan("Repositioning closer to ore");
            return;
        }
        blacklist.put(pos.asLong(), 200);
        replan("Can't reach block");
    }
    private boolean lowHpPaused;
    private Vec3 jitter = Vec3.ZERO;
    private final java.util.Random rng = new java.util.Random();
    private final Set<BlockPos> vein = new HashSet<>();
    private net.minecraft.world.entity.LivingEntity shooter;
    private List<BlockPos> chasePath;
    private int chaseIdx, chaseRepath;

    public AutoMiner() {
        super("AutoMiner", "Auto-mines the ores you pick (quartz, debris, diamonds...) using X-Ray, routing around lava. Never breaks a block next to lava.", Category.WORLD, GLFW.GLFW_KEY_K);
        advanced(radius, veinMine, candidates, pickSlot, swordSlot, mobTargets, defendRange, rangedMode, rangedRange, bigVeins, avoidLavaSea, stopAfter, minDurability, eatHp, deathSpot, pauseOnInput, lowHp, oreSound, effort, collectRadius, autoTool, showPath);
    }

    @Override public String getInfo() { return status; }
    public String getStatus() { return status; }
    public int getMined() { return mined; }
    public long getTotalMined() { return totalMined; }

    public void resetTotal() {
        totalMined = 0;
        dev.magma.client.config.ConfigManager.save();
        Chat.info("AutoMiner total reset.");
    }

    /** Ores per hour this session. */
    public int getRate() {
        if (!isEnabled() || mined == 0) return 0;
        double hours = (System.currentTimeMillis() - sessionStart) / 3_600_000.0;
        return hours <= 0 ? 0 : (int) Math.round(mined / hours);
    }

    private boolean fast() { return speed.is("Fast"); }
    private boolean human() { return speed.is("Human"); }

    /** Degrees per tick we may turn. */
    private float rot() {
        return switch (speed.get()) {
            case "Fast" -> 180f;
            case "Human" -> 24f + rng.nextFloat() * 14f;
            default -> 55f;
        };
    }

    @Override
    public com.google.gson.JsonObject toJson() {
        com.google.gson.JsonObject o = super.toJson();
        o.addProperty("totalMined", totalMined);
        return o;
    }

    @Override
    public void fromJson(com.google.gson.JsonObject o) {
        if (o.has("totalMined")) totalMined = o.get("totalMined").getAsLong();
        super.fromJson(o);
    }
    public int getRouteLength() { return routeLen; }
    public BlockPos getTargetOre() { return ore; }

    @Override
    protected void onEnable() {
        initEngine();
        if (ores.get().isEmpty()) {
            Chat.info("AutoMiner: pick at least one ore type in its settings.");
            setEnabled(false);
            return;
        }
        Chat.info("AutoMiner started (" + speed.get() + ", " + String.join(", ", ores.get()) + "). Press K or use the menu to stop.");
    }

    /** Shared start-up for mining and for Goto (which uses this engine without turning AutoMiner on). */
    private void initEngine() {
        reset();
        blacklist.clear();
        unsafeOres.clear();
        adjacentOnly.clear();
        noPlace.clear();
        returningHome = false;
        mined = 0;
        replans = 0;
        sessionStart = System.currentTimeMillis();
        vein.clear();
        lowHpPaused = false;
        humanDelay = 0;
        hasExploreYaw = false;
        lastOre = null;
        cands = null;
        LavaGuard guard = ModuleManager.get(LavaGuard.class);
        if (!guard.isEnabled()) {
            guard.setEnabled(true);
            Chat.info("LavaGuard turned on for extra safety.");
        }
        ignoredItems.clear();
    }

    /** Stop whatever the engine is doing: AutoMiner, or a stand-alone Goto. */
    private void halt() {
        if (!isEnabled() && gotoTarget != null) {
            gotoOwns = true;
            finishGoto(false, "Goto stopped");
        } else {
            setEnabled(false);
        }
    }

    /**
     * Is this column one of the next steps of the current route? SafeWalk lets those through (the planner already
     * checked them and bridges are placed before stepping), otherwise it would freeze bridging/drops mid-move.
     */
    public boolean isPlannedStep(BlockPos c) {
        List<BlockPos> p = path;
        if (p == null || state != State.FOLLOW) return false;
        for (int i = Math.max(0, idx); i < Math.min(p.size(), idx + 3); i++) {
            BlockPos n = p.get(i);
            if (n.getX() == c.getX() && n.getZ() == c.getZ()) return true;
        }
        return false;
    }

    private int lastDriveTick = -1;

    /** Goto / AutoFind / Base call this every tick; the engine runs at most once per tick. */
    public void driveIfIdle() {
        if (isEnabled() || gotoTarget == null || mc.player == null || lastDriveTick == mc.player.tickCount) return;
        lastDriveTick = mc.player.tickCount;
        onTick();
    }

    /** True while AutoMiner is mining OR the engine is running a Goto on its own. */
    public boolean engineRunning() { return isEnabled() || gotoTarget != null; }

    private void stopEngine() {
        stopEating();
        PlayerControl.releaseMovement();
        status = "Idle";
        path = null;
        ore = null;
    }

    @Override
    protected void onDisable() {
        if (gotoTarget != null && !finishingGoto) {
            gotoTarget = null;
            Goto g = ModuleManager.get(Goto.class);
            if (g.isEnabled()) g.setEnabled(false);
        }
        stopEngine();
    }

    private void reset() {
        state = State.SEARCH;
        path = null;
        idx = 0;
        ore = null;
        cooldown = 0;
        walkStuck = 0;
        aimMiss = 0;
        breaking = null;
        breakTicks = 0;
        lastIdx = -1;
        cands = null;
        job = null;
        gotoJob = null;
        threat = null;
        afterPath = State.SEARCH;
        PlayerControl.releaseMovement();
    }

    private boolean isTarget(BlockState s) {
        if (s.isAir()) return false;
        for (String t : ores.get()) if (dev.magma.client.util.OreTypes.matches(t, s)) return true;
        return false;
    }

    private Pathfinder tickPf;
    private int tickPfAge = -1;

    private Pathfinder pf() {
        // reuse within the same tick: the world hasn't changed, and the caches make it much cheaper
        if (tickPf != null && tickPfAge == mc.player.tickCount) return tickPf;
        tickPf = newPf(effort.getInt());
        tickPfAge = mc.player.tickCount;
        return tickPf;
    }

    private Pathfinder newPf(int nodes) {
        Pathfinder p = new Pathfinder(mc.level, strict.get(), nodes);
        p.reach = reach();
        p.placeBudget = placeBlocks.get() ? Math.min(48, scaffoldCount()) : 0;
        p.noPlace = noPlace;
        p.maxDrop = ModuleManager.get(SafeWalk.class).fallLimit();
        p.allowDig = gotoTarget == null || gotoDig;
        if (avoidLavaSea.get() && mc.level.dimension() == net.minecraft.world.level.Level.NETHER) {
            p.minY = Math.min(33, feet().getY()); // never go lower than we already are
            if (gotoTarget != null) p.minY = Math.min(p.minY, gotoTarget.getY() - 1); // unless you asked to go there
        }
        return p;
    }

    // ---------------- scaffold blocks
    private static final java.util.Set<net.minecraft.world.level.block.Block> SCAFFOLD = java.util.Set.of(
            Blocks.NETHERRACK, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE,
            Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
            Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MUD,
            Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
            Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.POLISHED_BASALT, Blocks.SOUL_SOIL, Blocks.NETHER_BRICKS,
            Blocks.END_STONE, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA, Blocks.CLAY, Blocks.PACKED_MUD,
            Blocks.MUD_BRICKS, Blocks.STONE_BRICKS, Blocks.DEEPSLATE_BRICKS, Blocks.MOSS_BLOCK);

    private static boolean isScaffold(ItemStack st) {
        return st.getItem() instanceof net.minecraft.world.item.BlockItem bi && SCAFFOLD.contains(bi.getBlock());
    }

    private int scaffoldCount() {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (isScaffold(st)) n += st.getCount();
        }
        return n;
    }

    private int scaffoldSlot() {
        for (int i = 0; i < 9; i++) if (isScaffold(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    private int placeCooldown;

    /** Right-click a face directly (no ray-trace needed), like vanilla does when you click that face. */
    private final Set<Long> noPlace = new HashSet<>();
    private BlockPos placeTarget;
    private int placeTicks;

    /** Gives up on a spot after ~3 s of failed placing: remember it and route another way (no more freezing). */
    private boolean placeTimedOut(BlockPos target) {
        if (!target.equals(placeTarget)) {
            placeTarget = target;
            placeTicks = 0;
        }
        if (++placeTicks > 60) {
            noPlace.add(target.asLong());
            placeTarget = null;
            replan("Can't place a block there - rerouting");
            return true;
        }
        return false;
    }

    private void placeAgainst(BlockPos against, Direction face) {
        placeAgainst(against, face, 4);
    }

    private void placeAgainst(BlockPos against, Direction face, int cooldownTicks) {
        if (placeCooldown > 0) return;
        Vec3 hitVec = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        PlayerControl.lookAt(hitVec, rot());
        mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, face, against, false));
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        placeCooldown = cooldownTicks;
    }

    /** Jump and place a block under ourselves. */
    private void pillar(BlockPos cur) {
        int slot = scaffoldSlot();
        if (slot == -1) { replan("Out of blocks"); return; }
        status = "Pillaring up";
        if (placeTimedOut(cur)) return;
        double dx = cur.getX() + 0.5 - mc.player.getX(), dz = cur.getZ() + 0.5 - mc.player.getZ();
        if (mc.player.onGround() && dx * dx + dz * dz > 0.2 * 0.2) { walkTo(cur, false); return; }
        mc.options.keyUp.setDown(false);
        PlayerControl.selectSlot(slot);
        PlayerControl.rotate(mc.player.getYRot(), 90f, 180f);
        mc.options.keyJump.setDown(mc.player.onGround() && feet().getY() == cur.getY());
        // the block can only go in while our feet are above the cell: try every tick in that short window
        if (!mc.player.onGround() && mc.player.getY() > cur.getY() + 1.0 && pf().canPlaceInto(cur)) placeAgainst(cur.below(), Direction.UP, 1);
    }

    /** Sneak to the edge and place a floor block in the gap. Returns true once the floor exists. */
    private boolean bridge(BlockPos cur, BlockPos nx) {
        BlockPos target = nx.below();
        if (pf().solidFloor(target)) return true;
        int slot = scaffoldSlot();
        if (slot == -1) { replan("Out of blocks"); return false; }
        status = "Bridging";
        if (placeTimedOut(target)) return false;
        if (!pf().canPlaceInto(target)) {
            noPlace.add(target.asLong());
            replan("Can't place a block there - rerouting");
            return false;
        }
        mc.options.keyShift.setDown(true); // sneaking: can't fall off the edge
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        PlayerControl.selectSlot(slot);
        Direction d = Direction.getNearest(nx.getX() - cur.getX(), 0, nx.getZ() - cur.getZ());
        placeAgainst(cur.below(), d);
        return false;
    }

    /** How many wanted ores touch this one - bigger veins are worth more. */
    private int veinSize(BlockPos o) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    if (isTarget(mc.level.getBlockState(o.offset(dx, dy, dz)))) n++;
                }
        return n;
    }

    private BlockPos feet() {
        return BlockPos.containing(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void onTick() {
        if (handleDeath()) return;
        // standing on magma/campfire? sneaking stops magma damage; the route never chooses these floors
        if (mc.player.onGround() && SafeWalk.hurtFloor(mc.player.getOnPos())) mc.options.keyShift.setDown(true);
        if (mc.screen != null && mc.screen.isPauseScreen()) { PlayerControl.releaseMovement(); return; }
        // only the route-walking code may run; any other activity (mining, fighting, eating...) walks
        if (state != State.FOLLOW) stopSprint();

        // you pressed a movement key yourself: hand control back for a moment
        if (pauseOnInput.get() && mc.screen == null && userMoving()) inputPauseUntil = System.currentTimeMillis() + 3000;
        if (System.currentTimeMillis() < inputPauseUntil) {
            PlayerControl.forceAttack = false;
            status = "Paused (you're moving)";
            return;
        }

        // Emergency: touching lava or burning -> back off and re-plan
        if (mc.player.isInLava() || (mc.player.isOnFire() && !mc.player.hasEffect(MobEffects.FIRE_RESISTANCE))) {
            status = "Escaping lava!";
            if (path != null && idx > 0) walkTo(path.get(idx - 1), true);
            else mc.options.keyJump.setDown(true);
            state = State.SEARCH;
            cooldown = 10;
            return;
        }

        if (returnHome.get() && gotoTarget == null && !returningHome && mc.player.getInventory().getFreeSlot() == -1
                && !ModuleManager.get(InvCleaner.class).canMakeRoom()) {
            Base base = ModuleManager.get(Base.class);
            BlockPos home = base.targetFromHere();
            if (home != null) {
                Chat.info("AutoMiner: inventory full (" + mined + " ores this run) - heading back to base" + base.dimensionNote() + ".");
                dev.magma.client.util.Notify.show("Full - returning to base", Theme.accent());
                returningHome = true;
                startGoto(home, true, false, false);
                return;
            }
            Chat.info("AutoMiner: inventory full, but no Base is set (Base module -> Set here).");
        }
        if (stopFull.get() && gotoTarget == null && mc.player.getInventory().getFreeSlot() == -1
                && !ModuleManager.get(InvCleaner.class).canMakeRoom()) {
            Chat.info("Inventory full - mined " + mined + " ores this run (" + totalMined + " total). Stopping.");
            halt();
            return;
        }

        if (placeCooldown > 0) placeCooldown--;
        blacklist.entrySet().removeIf(e -> {
            e.setValue(e.getValue() - 1);
            return e.getValue() <= 0;
        });

        gotoProgress();
        // very low health: eat (golden apple first) before anything else, even mid-fight
        if (autoEat.get() && mc.player.getHealth() <= 6 && eat(true)) return;
        if (defend.get() && defend()) return;
        if (autoEat.get() && eat(false)) return;

        // low health: stop and wait to heal
        float hp = mc.player.getHealth();
        if (lowHp.getInt() > 0 && (hp <= lowHp.getInt() || (lowHpPaused && hp < lowHp.getInt() + 4))) {
            if (!lowHpPaused) Chat.info("Low health - AutoMiner paused until you heal.");
            lowHpPaused = true;
            PlayerControl.releaseMovement();
            status = "Low HP - paused";
            return;
        }
        lowHpPaused = false;

        // human mode: short reaction pauses between actions
        if (humanDelay > 0) {
            humanDelay--;
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            return;
        }

        switch (state) {
            case SEARCH -> search();
            case FOLLOW -> follow();
            case MINE_ORE -> mineOre();
            case COLLECT -> collect();
            case FIND_PORTAL -> findPortal();
            case ENTER_PORTAL -> enterPortal();
            case RETURN_DEATH -> returnToDeath();
        }
    }

    private boolean inVein(BlockPos o) {
        return veinMine.get() && lastOre != null && o.distManhattan(lastOre) <= 3;
    }

    /**
     * GPS-style planning, spread over several ticks so it never freezes the game:
     * tick 1 scans with X-Ray, the next ticks each plan one candidate route, then the cheapest is taken.
     * Ores in the vein we just mined are always tried first.
     */
    private void search() {
        PlayerControl.releaseMovement();
        if (cooldown-- > 0) return;
        BlockPos start = feet();
        if (gotoTarget != null) {
            gotoStep(start);
            return;
        }
        Pathfinder pf = pf();

        // 1) anything we can already hit from here? mine it without moving
        if (mc.player.onGround() || mc.player.isInWater()) {
            BlockPos here = oreInReach();
            if (here != null) {
                startPath(List.of(start), here);
                status = "Mining in reach";
                return;
            }
        }

        // pick up everything lying around first (ore drops and tunnel blocks), as long as it fits
        if (cands == null && collectDrops.get() && (gotoTarget == null) && nearestDrop() != null) {
            state = State.COLLECT;
            wait = 0;
            return;
        }

        // 2) plan a route. The search runs ~8 ms per tick and simply continues next tick, so long or zig-zag
        //    routes get found instead of "No safe path", and the game never stutters.
        if (cands == null) {
            status = "Scanning ores...";
            // whole column height: ores far below (or above) you are found even when you're up high
            List<BlockPos> ores = OreScanner.scan(mc.level, start, radius.getInt(), 400, this::isTarget, 128);
            List<BlockPos> safe = new ArrayList<>();
            for (BlockPos o : ores) {
                if (blacklist.containsKey(o.asLong())) continue;
                if (!pf.canBreakSafely(o)) {
                    unsafeOres.add(o);
                    blacklist.put(o.asLong(), 1200);
                    continue;
                }
                safe.add(o);
            }
            safe.sort(java.util.Comparator.comparingDouble(o -> (inVein(o) ? -1000 : 0) + o.distSqr(start)));
            // never wander off while there's still ore right around us
            List<BlockPos> near = new ArrayList<>();
            for (BlockPos o : safe) if (o.distSqr(start) <= 36) near.add(o);
            if (!near.isEmpty()) safe = near;
            firstSafe = safe.isEmpty() ? null : safe.get(0);
            int k = fast() ? 2 : candidates.getInt();
            cands = new ArrayList<>(safe.subList(0, Math.min(safe.size(), k)));
            candIdx = 0;
            bestPath = null;
            bestOre = null;
            bestCost = Double.MAX_VALUE;
            job = null;
            jobPartial = null;
        }

        long deadline = System.nanoTime() + 8_000_000L;
        while (true) {
            if (job == null) {
                if (candIdx >= cands.size()) break;
                jobOre = cands.get(candIdx++);
                if (Pathfinder.touchesOre(start, jobOre) || minableFromHere(jobOre)) {
                    bestPath = List.of(start);
                    bestOre = jobOre;
                    bestCost = -1;
                    candIdx = cands.size();
                    break;
                }
                job = newPf(effort.getInt()).startToOre(start, jobOre, !adjacentOnly.contains(jobOre));
            }
            long left = deadline - System.nanoTime();
            if (left <= 0 || !job.step(left)) {
                status = "Planning route" + (cands.size() > 1 ? " " + candIdx + "/" + cands.size() : "") + " (" + job.expanded() + ")";
                return; // keep searching next tick
            }
            if (job.result() != null) {
                double c = job.cost() - (inVein(jobOre) ? 8 : 0) - (bigVeins.get() ? Math.min(8, veinSize(jobOre)) * 1.5 : 0);
                if (c < bestCost) {
                    bestCost = c;
                    bestPath = job.result();
                    bestOre = jobOre;
                }
                // reasonably direct? take it now instead of planning other ores too
                int direct = Math.abs(start.getX() - jobOre.getX()) + Math.abs(start.getY() - jobOre.getY()) + Math.abs(start.getZ() - jobOre.getZ());
                if (fast() || inVein(jobOre) || job.cost() <= direct * 3.0 + 10) candIdx = cands.size();
            } else {
                if (jobPartial == null) jobPartial = job.partialResult(); // remember how far we got toward the nearest ore
                blacklist.put(jobOre.asLong(), 200);
            }
            job = null;
            if (candIdx >= cands.size()) break;
        }

        List<BlockPos> chosen = bestPath;
        BlockPos chosenOre = bestOre;
        List<BlockPos> part = jobPartial;
        cands = null;
        jobPartial = null;
        if (chosen != null) {
            startPath(chosen, chosenOre);
            return;
        }
        // no complete route: walk the part we found toward the ore, then plan again from closer
        if (part != null && part.size() > 1) {
            startPath(part, null);
            status = "Approaching ore";
            return;
        }

        if (explore.get() && firstSafe == null) {
            if (!hasExploreYaw) {
                exploreYaw = Math.round(mc.player.getYRot() / 90f) * 90f;
                hasExploreYaw = true;
            }
            for (int turn = 0; turn < 4; turn++) {
                float yaw = exploreYaw + turn * 90f;
                double rad = Math.toRadians(yaw);
                BlockPos goal = start.offset((int) Math.round(-Math.sin(rad) * 24), 0, (int) Math.round(Math.cos(rad) * 24));
                List<BlockPos> p = pf.findToward(start, goal);
                if (p != null && p.size() > 1) {
                    exploreYaw = yaw;
                    startPath(p, null);
                    status = "Exploring...";
                    return;
                }
            }
        }
        status = firstSafe == null ? "No ore in range" : "No safe path (lava/drops in the way)";
        cooldown = 10;
    }

    /** Can we hit this block from where we stand right now (reach + clear line of sight)? */
    private double reach() {
        return Math.max(3.0, mc.player.blockInteractionRange() - 0.3);
    }

    private boolean minableFromHere(BlockPos o) {
        if (adjacentOnly.contains(o)) return Pathfinder.touchesOre(feet(), o);
        Vec3 eye = mc.player.getEyePosition();
        Vec3 aim = PlayerControl.aimPoint(mc.level, o, eye);
        double r = reach();
        if (eye.distanceToSqr(aim) > r * r) return false;
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, aim, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos hp = hit.getBlockPos();
        if (hp.equals(o)) return true;
        // one safe block in the way (not our floor): we'll dig it, then take the ore
        return hp.distManhattan(o) == 1 && !hp.equals(feet().below()) && eye.distanceToSqr(Vec3.atCenterOf(hp)) <= r * r
                && pf().canBreakSafely(hp);
    }

    /** Nearest safe target ore we can mine right now without taking a step, or null. */
    private BlockPos oreInReach() {
        int r = (int) Math.ceil(reach()) + 1;
        Pathfinder pf = pf();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos o : OreScanner.scan(mc.level, feet(), r, this::isTarget, 64)) {
            if (blacklist.containsKey(o.asLong()) || !pf.canBreakSafely(o) || !minableFromHere(o)) continue;
            double d = o.distSqr(feet());
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    /** Fast mode: nearest reachable ore, same tick, no route comparison. */
    private boolean fastSearch(BlockPos start, Pathfinder pf) {
        status = "Locating ore";
        List<BlockPos> ores = OreScanner.scan(mc.level, start, radius.getInt(), 400, this::isTarget, 64);
        int tries = 0;
        for (BlockPos o : ores) {
            if (blacklist.containsKey(o.asLong())) continue;
            if (!pf.canBreakSafely(o)) {
                unsafeOres.add(o);
                blacklist.put(o.asLong(), 1200);
                continue;
            }
            if (Pathfinder.touchesOre(start, o) || minableFromHere(o)) {
                startPath(List.of(start), o);
                return true;
            }
            if (++tries > 3) break;
            List<BlockPos> p = route(pf, start, o);
            if (p != null) {
                startPath(p, o);
                return true;
            }
            blacklist.put(o.asLong(), 600);
        }
        return false; // fall through to approach / explore
    }

    /** Remember every target ore touching the one we just mined (26-neighbourhood) - that's the vein. */
    private void growVein(BlockPos mined) {
        Pathfinder pf = pf();
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 3) continue;
                    BlockPos p = mined.offset(dx, dy, dz);
                    if (p.equals(mined) || blacklist.containsKey(p.asLong())) continue;
                    if (isTarget(mc.level.getBlockState(p)) && pf.canBreakSafely(p)) vein.add(p);
                }
        vein.remove(mined);
    }

    /** Fast mode: jump straight to the next ore of the same vein. Returns false when the vein is done. */
    private boolean nextVeinOre() {
        vein.removeIf(p -> !isTarget(mc.level.getBlockState(p)));
        BlockPos start = feet();
        Pathfinder pf = new Pathfinder(mc.level, strict.get(), 6000);
        while (!vein.isEmpty()) {
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (BlockPos p : vein) {
                double d = p.distSqr(start);
                if (d < bd) { bd = d; best = p; }
            }
            vein.remove(best);
            if (!pf.canBreakSafely(best)) continue;
            if (Pathfinder.touchesOre(start, best) || minableFromHere(best)) {
                startPath(List.of(start), best);
                status = "Vein: mining";
                return true;
            }
            List<BlockPos> p = route(pf, start, best);
            if (p != null) {
                startPath(p, best);
                status = "Vein: next ore";
                return true;
            }
        }
        return false;
    }

    private void onOreMined(BlockPos o) {
        collectWait = 0;
        mined++;
        totalMined++;
        lastOre = o;
        if (oreSound.get()) mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
        if (fast() || veinMine.get()) growVein(o);
        vein.remove(o);
        if (human()) humanDelay = 2 + rng.nextInt(4);
        if (totalMined % 5 == 0) dev.magma.client.config.ConfigManager.save();
        if (stopAfter.getInt() > 0 && mined >= stopAfter.getInt()) {
            Chat.info("AutoMiner: mined " + mined + " ores - target reached, stopping.");
            halt();
        }
    }

    // ------------------------------------------------------------------ go to coordinates

    public BlockPos getGotoTarget() { return gotoTarget; }

    /** Walk/dig/climb to a position using all the miner's safety rules. */
    public void startGoto(BlockPos target, boolean dig, boolean mineOnWay, boolean exactY) {
        gotoExactY = exactY;
        gotoDig = dig;
        gotoBest = Double.MAX_VALUE;
        gotoImprovedAt = System.currentTimeMillis();
        gotoSpeed = 0;
        gotoLastDist = -1;
        gotoMine = mineOnWay;
        gotoFails = 0;
        if (isEnabled()) {
            gotoTarget = target;
            gotoOwns = false;
            reset();
            Chat.info("Goto: heading to " + target.toShortString() + " (mining resumes after).");
        } else {
            // Goto alone: run the movement/safety engine WITHOUT switching AutoMiner on (it isn't shown as active)
            gotoTarget = target;
            gotoOwns = true;
            initEngine();
        }
    }

    public void cancelGoto() {
        if (gotoTarget == null) return;
        finishGoto(false, "Goto cancelled");
    }

    private void finishGoto(boolean arrived, String msg) {
        finishingGoto = true;
        try {
            dev.magma.client.util.Notify.show(msg, arrived ? Theme.OK : Theme.WARN);
            Chat.info(msg);
            gotoTarget = null;
            Goto g = ModuleManager.get(Goto.class);
            if (g.isEnabled()) g.setEnabled(false);
            if (gotoOwns) stopEngine();
            else if (returningHome) {
                returningHome = false;
                if (arrived) {
                    Chat.info("AutoMiner: back at base with " + mined + " ores. Stopping.");
                    dev.magma.client.util.Notify.show("Home - " + mined + " ores", Theme.OK);
                }
                setEnabled(false);
            } else { reset(); state = State.SEARCH; }
        } finally {
            finishingGoto = false;
        }
    }

    /**
     * The typed Y is often mid-air or inside rock. Once the target area is loaded, snap to the closest spot in that
     * column you can actually stand on (within 12 blocks), so Goto can arrive instead of giving up.
     */
    private BlockPos resolveGoal(BlockPos t) {
        if (!mc.level.hasChunkAt(t)) return t;
        Pathfinder pf = pf();
        for (int d = 0; d <= 12; d++) {
            for (int sgn : d == 0 ? new int[]{0} : new int[]{-1, 1}) {
                BlockPos c = t.offset(0, d * sgn, 0);
                if (pf.passable(c) && pf.passable(c.above()) && pf.solidFloor(c.below()) && !pf.isWater(c.above())
                        && !LavaUtil.touchesLava(mc.level, c)) return c;
            }
        }
        return t;
    }

    private double gotoBest = Double.MAX_VALUE;
    private long gotoImprovedAt;
    private double gotoSpeed; // blocks per second (smoothed)
    private double gotoLastDist = -1;
    private long gotoLastSample;

    /** ~seconds left on the current trip, or -1 if unknown. */
    public int gotoEtaSeconds() {
        if (gotoTarget == null || gotoSpeed < 0.3 || mc.player == null) return -1;
        return (int) (Math.sqrt(mc.player.blockPosition().distSqr(gotoTarget)) / gotoSpeed);
    }

    /** Watchdog: track progress; if a trip stops getting closer, try digging through, then give up with a reason. */
    private void gotoProgress() {
        if (gotoTarget == null) return;
        long now = System.currentTimeMillis();
        double d = Math.sqrt(mc.player.blockPosition().distSqr(gotoTarget));
        if (now - gotoLastSample >= 1000) {
            if (gotoLastDist >= 0) {
                double v = Math.max(0, (gotoLastDist - d) / ((now - gotoLastSample) / 1000.0));
                gotoSpeed = gotoSpeed == 0 ? v : gotoSpeed * 0.8 + v * 0.2;
            }
            gotoLastDist = d;
            gotoLastSample = now;
        }
        if (d < gotoBest - 2) {
            gotoBest = d;
            gotoImprovedAt = now;
        } else if (gotoImprovedAt > 0 && now - gotoImprovedAt > 30_000) {
            if (!gotoDig) {
                gotoDig = true;
                gotoImprovedAt = now;
                Chat.info("Goto: no progress for 30s - allowing digging to get through.");
                replan("Trying to dig through");
            } else {
                finishGoto(false, "Goto: stuck for 30s near " + mc.player.blockPosition().toShortString() + " (blocked by lava/drops?)");
            }
        }
    }

    private void gotoStep(BlockPos start) {
        BlockPos t = resolveGoal(gotoTarget);
        int dxz = Math.max(Math.abs(t.getX() - start.getX()), Math.abs(t.getZ() - start.getZ()));
        int dy = Math.abs(t.getY() - start.getY());
        if (dxz <= 1 && (dy <= 1 || !gotoExactY)) {
            finishGoto(true, "Goto: arrived at " + start.toShortString());
            return;
        }
        double dist = Math.sqrt(start.distSqr(t));
        if (gotoJob == null) {
            Pathfinder p = newPf(effort.getInt() * 2);
            gotoJob = dist <= 48 ? p.startNear(start, t, gotoExactY ? 1 : 0) : p.startToward(start, t);
        }
        if (!gotoJob.step(8_000_000L)) {
            status = "Goto: planning (" + gotoJob.expanded() + ")";
            return; // keep searching next tick
        }
        List<BlockPos> route = gotoJob.result() != null ? gotoJob.result() : gotoJob.partialResult(); // partial = as far as we safely can
        gotoJob = null;
        if (route != null && route.size() > 1) {
            gotoFails = 0;
            startPath(route, null);
            status = "Goto: " + (int) Math.sqrt(start.distSqr(t)) + " blocks left";
        } else {
            gotoFails++;
            status = "Goto: no safe way yet (" + gotoFails + "/8)";
            cooldown = 30;
            if (gotoFails > 8) {
                String why = !mc.level.hasChunkAt(t) ? " (that area isn't loaded and the way there is blocked)"
                        : scaffoldCount() == 0 && placeBlocks.get() ? " (no blocks in hotbar to bridge/pillar - carry some netherrack/cobble)"
                        : !gotoDig ? " (try turning on Dig Through)" : " (lava or big drops in the way)";
                finishGoto(false, "Goto: couldn't find a safe way to " + gotoTarget.toShortString() + why);
            }
        }
    }

    // ------------------------------------------------------------------ manual input

    private boolean userMoving() {
        long w = mc.getWindow().getWindow();
        for (var k : new net.minecraft.client.KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump}) {
            var key = k.getKey();
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                    && com.mojang.blaze3d.platform.InputConstants.isKeyDown(w, key.getValue())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ eating

    private static boolean goodFood(ItemStack st) {
        if (st.isEmpty() || st.get(net.minecraft.core.component.DataComponents.FOOD) == null) return false;
        return !(st.is(net.minecraft.world.item.Items.ROTTEN_FLESH) || st.is(net.minecraft.world.item.Items.SPIDER_EYE)
                || st.is(net.minecraft.world.item.Items.POISONOUS_POTATO) || st.is(net.minecraft.world.item.Items.PUFFERFISH)
                || st.is(net.minecraft.world.item.Items.CHORUS_FRUIT) || st.is(net.minecraft.world.item.Items.SUSPICIOUS_STEW));
    }

    private static boolean goldenApple(ItemStack st) {
        return st.is(net.minecraft.world.item.Items.GOLDEN_APPLE) || st.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE);
    }

    /**
     * Eat when it actually helps: food below 20 and (hungry or hurt), or a golden apple when health is critical.
     * Returns true while eating (the miner waits).
     */
    private boolean eat(boolean emergency) {
        var inv = mc.player.getInventory();
        int food = mc.player.getFoodData().getFoodLevel();
        float hp = mc.player.getHealth();
        boolean want = (food < 20 && (food <= 14 || hp < eatHp.getInt())) || (emergency && hp <= 6);
        if (!want) {
            stopEating();
            return false;
        }
        int slot = -1;
        if (hp <= 6) for (int i = 0; i < 9; i++) if (goldenApple(inv.getItem(i))) { slot = i; break; }
        if (slot == -1 && food < 20) {
            int bestN = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack st = inv.getItem(i);
                if (!goodFood(st) || goldenApple(st)) continue;
                int n = st.get(net.minecraft.core.component.DataComponents.FOOD).nutrition();
                if (n > bestN) { bestN = n; slot = i; }
            }
        }
        if (slot == -1) {
            stopEating();
            return false;
        }
        if (!eating) eatPrevSlot = inv.selected;
        eating = true;
        PlayerControl.forceAttack = false;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        PlayerControl.selectSlot(slot);
        status = goldenApple(inv.getItem(slot)) ? "Eating golden apple!" : "Eating";
        if (mc.screen == null) mc.options.keyUse.setDown(true);
        else if (!mc.player.isUsingItem()) mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
        return true;
    }

    private void stopEating() {
        if (!eating) return;
        eating = false;
        mc.options.keyUse.setDown(false);
        if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        if (eatPrevSlot >= 0) PlayerControl.selectSlot(eatPrevSlot);
        eatPrevSlot = -1;
    }

    // ------------------------------------------------------------------ death -> portal -> back to mining

    /** Returns true while dead / respawning. */
    private boolean handleDeath() {
        boolean deadNow = mc.player.isDeadOrDying() || mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen;
        if (deadNow) {
            if (!dead) {
                dead = true;
                deathTicks = 0;
                deathPos = mc.player.blockPosition();
                deathDim = mc.level.dimension();
                PlayerControl.releaseMovement();
                stopEating();
                if (afterDeath.is("Stop") || (!isEnabled() && gotoTarget != null)) {
                    Chat.info("AutoMiner: you died at " + deathPos.toShortString() + " - stopping.");
                    halt();
                    return true;
                }
                Chat.info("AutoMiner: died at " + deathPos.toShortString() + ". Respawning and heading back.");
            }
            status = "Respawning...";
            if (++deathTicks == 20 || deathTicks % 60 == 0) mc.player.respawn();
            return true;
        }
        if (dead) {
            dead = false;
            if (mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) mc.setScreen(null);
            reset();
            portal = null;
            portalJob = null;
            deathTrips = 0;
            portalFails = 0;
            boolean diedInNether = deathDim == net.minecraft.world.level.Level.NETHER;
            if (diedInNether && mc.level.dimension() != net.minecraft.world.level.Level.NETHER) {
                state = State.FIND_PORTAL; // look for a portal nearby; stops if there isn't one in render distance
            } else {
                // died outside the Nether: don't wander off mining from your spawn point - just stop
                Chat.info("AutoMiner: respawned - stopped (only returns automatically for Nether deaths with a portal nearby).");
                halt();
                return true;
            }
        }
        return false;
    }

    private void findPortal() {
        PlayerControl.releaseMovement();
        if (mc.level.dimension() == net.minecraft.world.level.Level.NETHER) {
            state = deathSpot.get() && deathPos != null ? State.RETURN_DEATH : State.SEARCH;
            return;
        }
        if (portal == null) {
            status = "Looking for a Nether portal...";
            // only what you can actually see: your render distance (capped at 96 blocks)
            int range = Math.min(96, mc.options.getEffectiveRenderDistance() * 16);
            if (portalJob == null) portalJob = new OreScanner.Job(feet(), range, st -> st.is(Blocks.NETHER_PORTAL), 64);
            if (!portalJob.step(mc.level, 6)) return;
            List<BlockPos> found = portalJob.result();
            portalJob = null;
            for (BlockPos p : found) {
                if (!mc.level.getBlockState(p.below()).is(Blocks.NETHER_PORTAL)) { portal = p; break; } // bottom block of the frame
            }
            if (portal == null) {
                Chat.info("AutoMiner: no Nether portal within your render distance - stopped. Walk to a portal and start it again.");
                dev.magma.client.util.Notify.show("No portal nearby - AutoMiner stopped", Theme.WARN);
                halt();
                return;
            }
            Chat.info("AutoMiner: found a portal at " + portal.toShortString() + ", walking there.");
        }
        if (feet().equals(portal) || mc.level.getBlockState(feet()).is(Blocks.NETHER_PORTAL) || feet().distManhattan(portal) <= 3) {
            state = State.ENTER_PORTAL;
            portalTicks = 0;
            return;
        }
        if (cooldown-- > 0) return;
        Pathfinder pf = newPf(effort.getInt() * 2);
        pf.timeLimitNanos = 20_000_000L;
        List<BlockPos> p = pf.findToward(feet(), portal);
        if (p != null && p.size() > 1) {
            startPath(p, null);
            afterPath = State.FIND_PORTAL;
            status = "Walking to portal";
        } else {
            status = "Can't find a safe way to the portal";
            cooldown = 60;
            if (++portalFails > 3) {
                Chat.info("AutoMiner: no safe way to the portal - stopped.");
                halt();
            }
        }
    }

    private void enterPortal() {
        status = "Entering portal";
        if (mc.level.dimension() == net.minecraft.world.level.Level.NETHER) {
            Chat.info("AutoMiner: back in the Nether, resuming.");
            state = deathSpot.get() && deathPos != null ? State.RETURN_DEATH : State.SEARCH;
            return;
        }
        if (mc.level.getBlockState(feet()).is(Blocks.NETHER_PORTAL)) {
            PlayerControl.releaseMovement(); // stand still inside the portal
        } else if (portal != null) {
            walkTo(portal, false);
        }
        if (++portalTicks > 300) { // portal didn't fire: try walking in again
            state = State.FIND_PORTAL;
            cooldown = 0;
        }
    }

    private void returnToDeath() {
        if (deathPos == null || mc.level.dimension() != deathDim || feet().distManhattan(deathPos) <= 4 || deathTrips > 12) {
            deathPos = null;
            state = State.SEARCH;
            return;
        }
        status = "Heading back to death spot";
        Pathfinder pf = newPf(effort.getInt() * 2);
        pf.timeLimitNanos = 20_000_000L;
        List<BlockPos> p = pf.findToward(feet(), deathPos);
        deathTrips++;
        if (p != null && p.size() > 1) {
            startPath(p, null);
            afterPath = State.RETURN_DEATH;
        } else {
            deathPos = null;
            state = State.SEARCH;
        }
    }

    // ------------------------------------------------------------------ combat

    /** Mob-type filter from "Mob Targets". Neutral mobs that are attacking us are always allowed. */
    private boolean allowedTarget(net.minecraft.world.entity.LivingEntity e) {
        boolean aggressive = e instanceof net.minecraft.world.entity.Mob m && m.isAggressive();
        if (e instanceof net.minecraft.world.entity.monster.ZombifiedPiglin) return mobTargets.has("Zombified Piglins") || aggressive;
        if (e instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute) return mobTargets.has("Piglin Brutes") || aggressive;
        if (e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin) return mobTargets.has("Piglins") || aggressive;
        if (e instanceof net.minecraft.world.entity.monster.EnderMan) return mobTargets.has("Endermen") || aggressive;
        if (e instanceof net.minecraft.world.entity.monster.hoglin.Hoglin || e instanceof net.minecraft.world.entity.monster.Zoglin) return mobTargets.has("Hoglins") || aggressive;
        if (e instanceof net.minecraft.world.entity.NeutralMob) return aggressive;
        return e instanceof net.minecraft.world.entity.monster.Enemy && mobTargets.has("Hostiles");
    }

    private boolean isThreat(net.minecraft.world.entity.LivingEntity e) {
        if (!e.isAlive() || e == mc.player || !allowedTarget(e)) return false;
        double r = defendRange.get();
        return e.distanceToSqr(mc.player) <= r * r && mc.player.hasLineOfSight(e);
    }

    private static int durabilityLeft(ItemStack st) {
        return st.isDamageableItem() ? st.getMaxDamage() - st.getDamageValue() : Integer.MAX_VALUE;
    }

    private boolean tooWorn(ItemStack st) {
        return toolSaver.get() && st.isDamageableItem() && durabilityLeft(st) <= minDurability.getInt();
    }

    private static double attackDamage(ItemStack st) {
        double dmg = 1; // fist
        var mods = st.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods != null) {
            for (var entry : mods.modifiers()) {
                if (entry.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) dmg += entry.modifier().amount();
            }
        }
        return dmg;
    }

    /**
     * Weapon to fight with: the chosen sword slot if it's healthy, else the hotbar item that hits hardest
     * (sword, axe, trident, mace, even a pickaxe) - never a nearly broken one when Tool Saver is on.
     */
    private int weaponSlot() {
        var inv = mc.player.getInventory();
        if (!swordSlot.is("Auto")) {
            int fixed = Integer.parseInt(swordSlot.get()) - 1;
            if (!tooWorn(inv.getItem(fixed))) return fixed;
        }
        int best = -1;
        double bestScore = 1.0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || tooWorn(st)) continue;
            double score = attackDamage(st);
            if (st.is(net.minecraft.tags.ItemTags.SWORDS)) score += 0.5; // faster swings + sweep
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best == -1 ? inv.selected : best;
    }

    /** Best healthy pickaxe in the hotbar, or -1. */
    private int healthyPickaxe(BlockState s) {
        var inv = mc.player.getInventory();
        int best = -1;
        float bestSpeed = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.is(net.minecraft.tags.ItemTags.PICKAXES) || tooWorn(st)) continue;
            float sp = st.getDestroySpeed(s);
            if (sp > bestSpeed) { bestSpeed = sp; best = i; }
        }
        return best;
    }

    /** Durability of the pickaxe we'd currently use, for the HUD ("-" if none). */
    private int lastToolSlot = -1;

    public String pickaxeDurability() {
        if (mc.player == null) return "-";
        var inv = mc.player.getInventory();
        // show the tool that is actually being used to mine (it was showing a "best guess" before)
        int slot = lastToolSlot >= 0 && inv.getItem(lastToolSlot).isDamageableItem() ? lastToolSlot
                : pickSlot.is("Auto") ? healthyPickaxe(net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState())
                : Integer.parseInt(pickSlot.get()) - 1;
        if (slot < 0) return "none";
        ItemStack st = inv.getItem(slot);
        return st.isDamageableItem() ? durabilityLeft(st) + "/" + st.getMaxDamage() : "∞";
    }

    /** Returns true while fighting (the miner pauses). */
    private boolean defend() {
        if (threat == null || !isThreat(threat)) {
            threat = null;
            double best = Double.MAX_VALUE;
            double r = defendRange.get();
            for (var e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, mc.player.getBoundingBox().inflate(r))) {
                if (!isThreat(e)) continue;
                double d = e.distanceToSqr(mc.player);
                if (d < best) { best = d; threat = e; }
            }
            if (threat == null) return !rangedMode.is("Ignore") && handleShooter();
        }
        mc.options.keyJump.setDown(false);
        PlayerControl.forceAttack = false;
        String name = threat.getName().getString();

        // creeper about to blow: wall it off with a block and back away instead of trading hits at point blank
        if (threat instanceof net.minecraft.world.entity.monster.Creeper cr && cr.getSwelling(1f) > 0.05f
                && cr.distanceToSqr(mc.player) < 4.5 * 4.5 && creeperGuard()) {
            status = "Creeper! Walling off";
            return true;
        }

        PlayerControl.selectSlot(weaponSlot());
        Vec3 aim = threat.getBoundingBox().getCenter();
        PlayerControl.lookAt(aim, 180f); // snap onto the mob
        status = "Defending: " + name;

        // only swing when the crosshair is really on the mob (vanilla's own reach + line-of-sight check),
        // otherwise it would swing at air from too far away or through a wall
        boolean onMob = mc.crosshairPickEntity == threat
                || (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr && ehr.getEntity() == threat);
        if (onMob) {
            mc.options.keyUp.setDown(false);
            chasePath = null;
            if (mc.player.getAttackStrengthScale(0.5f) >= 0.9f) {
                mc.gameMode.attack(mc.player, threat);
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
        } else {
            // walk up to it on a safe route (no digging), re-planned as it moves
            if (chasePath == null || --chaseRepath <= 0) {
                Pathfinder pf = newPf(4000);
                pf.allowDig = false;
                List<BlockPos> p = pf.findNear(feet(), threat.blockPosition(), 1);
                chasePath = p != null && p.size() > 1 ? p : null;
                chaseIdx = 0;
                chaseRepath = 8;
            }
            if (chasePath != null && chaseIdx < chasePath.size() - 1) {
                BlockPos cur = chasePath.get(chaseIdx), nx = chasePath.get(chaseIdx + 1);
                if (feet().equals(nx) || arrived(nx)) chaseIdx++;
                else walkTo(nx, nx.getY() > cur.getY());
                PlayerControl.lookAt(aim, 180f); // keep eyes on it while closing in
            } else {
                mc.options.keyUp.setDown(false);
            }
        }
        breaking = null; // tool changed; restart any block we were mining
        return true;
    }

    private int creeperPlaceTicks;

    /** Place a block between us and a fusing creeper (feet level, then head level) and step back. */
    private boolean creeperGuard() {
        if (!(threat instanceof net.minecraft.world.entity.monster.Creeper)) return false;
        Vec3 d = threat.position().subtract(mc.player.position());
        Direction toward = Direction.getNearest(d.x, 0, d.z);
        BlockPos wallFeet = feet().relative(toward), wallHead = wallFeet.above();
        Pathfinder pf = pf();
        int slot = scaffoldSlot();
        boolean placed = false;
        if (slot != -1 && placeBlocks.get() && ++creeperPlaceTicks < 60) {
            PlayerControl.selectSlot(slot);
            if (pf.canPlaceInto(wallFeet) && pf.solidFloor(wallFeet.below())) {
                placeAgainst(wallFeet.below(), Direction.UP, 1);
                placed = true;
            } else if (!pf.passable(wallFeet) && pf.canPlaceInto(wallHead)) {
                placeAgainst(wallFeet, Direction.UP, 1);
                placed = true;
            }
        }
        // back away from it if the ground behind is safe
        BlockPos back = feet().relative(toward.getOpposite());
        boolean safeBack = pf.passable(back) && pf.passable(back.above()) && pf.solidFloor(back.below())
                && !LavaUtil.touchesLava(mc.level, back) && !LavaUtil.touchesLava(mc.level, back.below());
        if (safeBack) {
            walkToKeepFacing(back);
        } else {
            mc.options.keyUp.setDown(false);
        }
        if (!placed && !safeBack) {
            creeperPlaceTicks = 0;
            return false; // can't wall or flee: fight it (knock it back) instead
        }
        if (!pf.passable(wallFeet) && !pf.passable(wallHead)) creeperPlaceTicks = 0;
        return true;
    }

    /** Back off a step without turning around (S key style), so the camera stays on the threat. */
    private void walkToKeepFacing(BlockPos n) {
        double dx = n.getX() + 0.5 - mc.player.getX();
        double dz = n.getZ() + 0.5 - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float diff = net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot());
        mc.options.keyUp.setDown(Math.abs(diff) < 45);
        mc.options.keyDown.setDown(Math.abs(diff) > 135);
        mc.options.keyLeft.setDown(diff < -45 && diff > -135);
        mc.options.keyRight.setDown(diff > 45 && diff < 135);
    }

    // ------------------------------------------------------------------ ranged mobs

    private static boolean flying(net.minecraft.world.entity.Entity e) {
        return e instanceof net.minecraft.world.entity.monster.Ghast || e instanceof net.minecraft.world.entity.monster.Blaze
                || e instanceof net.minecraft.world.entity.monster.Phantom;
    }

    private boolean isShooter(net.minecraft.world.entity.LivingEntity e) {
        if (!e.isAlive() || !allowedTarget(e)) return false;
        boolean ranged = e instanceof net.minecraft.world.entity.monster.RangedAttackMob || e instanceof net.minecraft.world.entity.monster.Ghast
                || e instanceof net.minecraft.world.entity.monster.Blaze;
        if (!ranged) return false;
        double r = rangedRange.get();
        return e.distanceToSqr(mc.player) <= r * r && mc.player.hasLineOfSight(e);
    }

    /** True if every step of the path is already open (chasing never digs). */
    private boolean openPath(List<BlockPos> p) {
        Pathfinder pf = pf();
        for (int i = 0; i + 1 < p.size(); i++) {
            for (BlockPos c : cellsToClear(p.get(i), p.get(i + 1))) if (!pf.passable(c)) return false;
        }
        return true;
    }

    /** Deal with a mob shooting at us from out of melee range. Returns true while busy. */
    private boolean handleShooter() {
        if (shooter == null || !isShooter(shooter)) {
            shooter = null;
            chasePath = null;
            double best = Double.MAX_VALUE;
            double r = rangedRange.get();
            for (var e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, mc.player.getBoundingBox().inflate(r))) {
                if (!isShooter(e)) continue;
                double d = e.distanceToSqr(mc.player);
                if (d < best) { best = d; shooter = e; }
            }
            if (shooter == null) return false;
        }
        PlayerControl.forceAttack = false;
        String name = shooter.getName().getString();

        if (rangedMode.is("Chase") && !flying(shooter)) {
            if (chasePath == null || --chaseRepath <= 0) {
                List<BlockPos> p = pf().findToward(feet(), shooter.blockPosition());
                chasePath = p != null && p.size() > 1 && openPath(p) ? p : null;
                chaseIdx = 0;
                chaseRepath = 15;
            }
            if (chasePath != null && chaseIdx < chasePath.size() - 1) {
                BlockPos cur = chasePath.get(chaseIdx), nx = chasePath.get(chaseIdx + 1);
                if (arrived(nx)) chaseIdx++;
                else walkTo(nx, nx.getY() > cur.getY());
                status = "Chasing " + name;
                return true;
            }
        }

        // take cover: back out along the tunnel we came through, or step away from the shooter on safe ground
        status = "Taking cover from " + name;
        if (path != null && idx > 0) {
            BlockPos back = path.get(idx - 1);
            if (arrived(back)) idx--;
            else walkTo(back, false);
            return true;
        }
        Vec3 away = mc.player.position().subtract(shooter.position());
        BlockPos step = feet().relative(Direction.getNearest(away.x, 0, away.z));
        Pathfinder pf = pf();
        boolean safe = pf.passable(step) && pf.passable(step.above()) && pf.solidFloor(step.below())
                && !LavaUtil.touchesLava(mc.level, step) && !LavaUtil.touchesLava(mc.level, step.below());
        if (safe) walkTo(step, false);
        else mc.options.keyUp.setDown(false);
        return true;
    }

    private void startPath(List<BlockPos> p, BlockPos o) {
        cands = null;
        path = new ArrayList<>(p);
        idx = 0;
        ore = o;
        walkStuck = 0;
        lastIdx = -1;
        aimMiss = 0;
        breaking = null;
        state = path.size() > 1 ? State.FOLLOW : (o != null ? State.MINE_ORE : State.SEARCH);
        routeLen = path.size() - 1;
        if (o != null) status = "Route locked: " + routeLen + " steps";
    }

    private void replan(String why) {
        status = why;
        job = null;
        cands = null;
        gotoJob = null;
        PlayerControl.releaseMovement();
        state = State.SEARCH;
        cooldown = 3;
        path = null;
        breaking = null;
        replans++;
    }

    private void follow() {
        if (path == null) { replan("Lost path"); return; }
        if (idx >= path.size() - 1) {
            PlayerControl.releaseMovement();
            state = ore != null ? State.MINE_ORE : afterPath;
            afterPath = State.SEARCH;
            return;
        }
        if (ore != null && !isTarget(mc.level.getBlockState(ore))) { replan("Ore gone"); return; }

        // live lava re-check on the next few nodes
        Pathfinder pf = pf();
        for (int i = idx + 1; i < Math.min(path.size(), idx + 4); i++) {
            BlockPos n = path.get(i);
            if (pf.cellCost(n) == Double.MAX_VALUE || pf.cellCost(n.above()) == Double.MAX_VALUE) {
                replan("Lava moved - replanning");
                return;
            }
        }

        // If we got knocked off the path (fell, pushed), re-sync to the nearest node or replan.
        BlockPos f = feet();
        if (!f.equals(path.get(idx)) && !f.equals(path.get(idx + 1))) {
            int found = -1;
            for (int i = idx; i < Math.min(path.size(), idx + 6); i++) if (path.get(i).equals(f)) found = i;
            if (found >= 0) idx = found;
            else if (mc.player.onGround() && f.distManhattan(path.get(idx)) > 2) { replan("Off path - replanning"); return; }
        }

        // stop walking as soon as any wanted ore is mineable from where we stand (checked every few ticks)
        if (mc.player.onGround() && afterPath == State.SEARCH && (gotoTarget == null || gotoMine)) {
            BlockPos here = oreInReach();
            if (here != null) {
                PlayerControl.releaseMovement();
                path = List.of(feet());
                idx = 0;
                ore = here;
                state = State.MINE_ORE;
                status = "Mining in reach";
                return;
            }
        }

        BlockPos cur = path.get(idx);
        BlockPos nx = path.get(idx + 1);

        // 1) clear the blocks in the way (top to bottom)
        for (BlockPos c : cellsToClear(cur, nx)) {
            if (pf.passable(c)) continue;
            if (!pf.canBreakSafely(c)) {
                blacklist.put(c.asLong(), 400);
                if (ore != null) blacklist.put(ore.asLong(), 400);
                replan("Unsafe block - rerouting");
                return;
            }
            status = "Tunnelling";
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            lastIdx = idx;
            walkStuck = 0; // mining is progress, not being stuck
            mine(c);
            return;
        }

        // the blocks we just dug aren't confirmed by the server yet (lag): don't walk/jump into them, or it
        // keeps jumping into a block that pops back. Wait a moment instead.
        for (BlockPos c : cellsToClear(cur, nx)) {
            if (PlayerControl.unconfirmed(c)) {
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                status = "Waiting for server...";
                lastIdx = idx;
                walkStuck = 0;
                return;
            }
        }

        // 2) move into the next cell
        status = ore != null ? "Walking to ore (" + (path.size() - 1 - idx) + ")" : "Approaching / exploring";
        if (arrived(nx)) {
            idx++;
            mc.options.keyShift.setDown(false);
            return;
        }
        // already inside the next cell while gliding through a straight run? count it and keep going
        if (feet().equals(nx) && idx + 2 < path.size() && straightOpen(idx + 1)) {
            idx++;
            return;
        }
        boolean horiz = cur.getX() != nx.getX() || cur.getZ() != nx.getZ();
        if (!horiz && nx.getY() > cur.getY()) {
            lastIdx = idx;
            walkStuck = 0;
            pillar(cur);
            return;
        }
        boolean bridging = horiz && nx.getY() == cur.getY() && !pf.solidFloor(nx.below());
        if (bridging && !bridge(cur, nx)) {
            lastIdx = idx;
            walkStuck = 0;
            return;
        }
        Vec3 pos = mc.player.position();
        if (idx != lastIdx) { lastIdx = idx; walkStuck = 0; stuckRounds = 0; recenter = 0; stepJumps = 0; stepPause = 0; }
        else if (pos.distanceToSqr(lastPos) < 0.0009) walkStuck++;
        lastPos = pos;
        if (walkStuck > 30) {
            // probably caught on a tunnel corner: re-centre on the current node, twice at most, then re-plan
            walkStuck = 0;
            recenter = 15;
            if (++stuckRounds > 2) {
                stuckRounds = 0;
                replan("Stuck - replanning");
                return;
            }
        }
        if (recenter > 0) {
            recenter--;
            walkTo(cur, false);
            return;
        }
        // natural walking: head straight for the furthest route point we can walk to in a line (cuts corners,
        // walks diagonally through open caves) instead of hitting every block centre and turning 90 degrees
        BlockPos aimNode = nx;
        if (!bridging && nx.getY() == cur.getY()) {
            for (int j = idx + 2; j < Math.min(path.size(), idx + 8); j++) {
                if (!flatOpenStep(j - 1, j) || !clearLine(mc.player.position(), path.get(j))) break;
                aimNode = path.get(j);
            }
        }
        if (aimNode != nx) {
            // count route points we've effectively passed while cutting the corner
            for (int j = path.indexOf(aimNode); j > idx; j--) {
                BlockPos pj = path.get(j);
                double ddx = mc.player.getX() - (pj.getX() + 0.5), ddz = mc.player.getZ() - (pj.getZ() + 0.5);
                if (ddx * ddx + ddz * ddz < 0.6 * 0.6 && feet().getY() == pj.getY()) { idx = j; return; }
            }
        }
        boolean stepUp = nx.getY() > cur.getY();
        if (stepUp && mc.player.onGround() && mc.options.keyJump.isDown()) stepJumps++;
        if (stepUp && stepJumps > 8) {
            // jumped several times without getting up: the ceiling is probably still there server-side.
            // pause the jumping so the world can resync; the dig check above re-mines it if it came back.
            mc.options.keyJump.setDown(false);
            if (++stepPause > 15) { stepPause = 0; stepJumps = 0; }
            status = "Re-checking step";
            return;
        }
        walkTo(aimNode, stepUp);
        // run on long, open, safe stretches (Goto, or Fast mode)
        // running with hysteresis: start after the way ahead looked clear for a few ticks, stop only for a real reason
        // (before it flipped run/walk every tick, which looked like a seizure)
        boolean wantRun = (gotoTarget != null || fast()) && path.indexOf(aimNode) - idx >= 3;
        boolean mustWalk = bridging || stepUp || mc.player.isInWater() || mc.player.getFoodData().getFoodLevel() <= 6
                || path.size() - 1 - idx < 3 || !mc.options.keyUp.isDown();
        if (mustWalk) sprintScore = 0;
        else if (wantRun) sprintScore = Math.min(sprintScore + 1, 12);
        else sprintScore = Math.max(sprintScore - 1, 0);
        boolean running = !mustWalk && (mc.player.isSprinting() ? sprintScore > 0 : sprintScore >= 4);
        mc.options.keySprint.setDown(running);
        mc.player.setSprinting(running);
        if (bridging || (idx > 0 && !pf.solidFloor(cur.below()))) mc.options.keyShift.setDown(true);
    }

    /** Route step a -> b is a flat, open step on solid ground that needs no digging or placing. */
    private boolean flatOpenStep(int a, int b) {
        BlockPos pa = path.get(a), pb = path.get(b);
        if (pa.getY() != pb.getY()) return false;
        Pathfinder pf = pf();
        return pf.solidFloor(pb.below()) && pf.cellCost(pb) == 0 && pf.cellCost(pb.above()) == 0;
    }

    /** Can the player (0.6 wide) walk in a straight line from here to the node without leaving safe, open ground? */
    private boolean clearLine(Vec3 from, BlockPos to) {
        double tx = to.getX() + 0.5, tz = to.getZ() + 0.5;
        double dx = tx - from.x, dz = tz - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return true;
        int y = to.getY();
        Pathfinder pf = pf();
        for (double t = 0; t <= len; t += 0.25) {
            double x = from.x + dx * t / len, z = from.z + dz * t / len;
            for (double ox : new double[]{-0.31, 0.31}) {
                for (double oz : new double[]{-0.31, 0.31}) {
                    BlockPos c = BlockPos.containing(x + ox, y + 0.1, z + oz);
                    if (pf.cellCost(c) != 0 || pf.cellCost(c.above()) != 0 || !pf.solidFloor(c.below())) return false;
                }
            }
        }
        return true;
    }

    /** Path step i -> i+1 continues in the same flat direction as (i-1 -> i) and needs no digging/placing. */
    private boolean straightOpen(int i) {
        if (path == null || i < 1 || i + 1 >= path.size()) return false;
        BlockPos a = path.get(i - 1), b = path.get(i), c = path.get(i + 1);
        if (a.getY() != b.getY() || b.getY() != c.getY()) return false;
        if (b.getX() - a.getX() != c.getX() - b.getX() || b.getZ() - a.getZ() != c.getZ() - b.getZ()) return false;
        Pathfinder pf = pf();
        return pf.passable(c) && pf.passable(c.above()) && pf.solidFloor(c.below()) && pf.cellCost(c) == 0 && pf.cellCost(c.above()) == 0;
    }

    private List<BlockPos> cellsToClear(BlockPos cur, BlockPos nx) {
        List<BlockPos> list = new ArrayList<>();
        boolean horizontal = cur.getX() != nx.getX() || cur.getZ() != nx.getZ();
        if (horizontal) {
            if (nx.getY() > cur.getY()) list.add(cur.above(2));
            int top = Math.max(cur.getY(), nx.getY()) + 1;
            for (int y = top; y >= nx.getY(); y--) list.add(new BlockPos(nx.getX(), y, nx.getZ()));
        } else if (nx.getY() < cur.getY()) {
            list.add(nx);
        } else if (nx.getY() > cur.getY()) {
            list.add(cur.above(2)); // head room for pillaring
        }
        return list;
    }

    private boolean arrived(BlockPos n) {
        double dx = mc.player.getX() - (n.getX() + 0.5);
        double dz = mc.player.getZ() - (n.getZ() + 0.5);
        return dx * dx + dz * dz < 0.3 * 0.3 && feet().getY() == n.getY() && mc.player.onGround();
    }

    private void walkTo(BlockPos n, boolean jump) {
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        double dx = n.getX() + 0.5 - mc.player.getX();
        double dz = n.getZ() + 0.5 - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        if (dist > 0.05) PlayerControl.rotate(yaw, human() ? 10f : 15f, Math.min(rot(), fast() ? 40f : 24f)); // gentle turns
        // only walk once roughly facing the node, otherwise we scrape tunnel corners and stall
        float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
        mc.options.keyUp.setDown(dist > 0.1 && err < (human() ? 30f : fast() ? 50f : 40f));
        mc.options.keyShift.setDown(mc.player.onGround() && SafeWalk.hurtFloor(mc.player.getOnPos())); // sneak across magma
        // jump only when we're actually pushing against the step, never on the spot
        boolean wet = mc.player.isInWater();
        // in water you can't "jump" off the ground: swim up (hold jump) against a ledge so it can climb out
        mc.options.keyJump.setDown((jump && mc.player.onGround() && mc.player.horizontalCollision && dist < 1.2)
                || (wet && (jump || mc.player.horizontalCollision)));
    }

    /** Aim at the block's exposed face and hold attack; LavaGuard still vets every hit. */
    private void mine(BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        // pick the tool, and never run a pickaxe into the ground
        var inv = mc.player.getInventory();
        int slot;
        boolean pickBlock = s.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
        // your chosen pickaxe slot is only for pickaxe blocks; wood gets the axe, dirt/gravel the shovel, etc.
        if (!pickSlot.is("Auto") && pickBlock) slot = Integer.parseInt(pickSlot.get()) - 1;
        else if (autoTool.get()) slot = PlayerControl.bestToolSlot(s, toolSaver.get() ? minDurability.getInt() : -1);
        else slot = inv.selected;
        if (toolSaver.get()) {
            ItemStack tool = inv.getItem(slot);
            boolean needsPick = s.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
            if (tooWorn(tool) || (needsPick && !tool.is(net.minecraft.tags.ItemTags.PICKAXES))) {
                int alt = healthyPickaxe(s);
                if (alt == -1 && needsPick) {
                    Chat.info("AutoMiner stopped: pickaxe is almost broken (" + durabilityLeft(tool) + " left) and there's no other healthy pickaxe in your hotbar.");
                    mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.ITEM_BREAK, 1f));
                    dev.magma.client.util.Notify.show("AutoMiner stopped - pickaxe almost broken", Theme.DANGER);
                    halt();
                    return;
                }
                if (alt != -1) slot = alt;
            }
        }
        PlayerControl.selectSlot(slot);
        lastToolSlot = slot;

        if (!pos.equals(breaking)) {
            if (human() && breaking != null && mc.level.getBlockState(breaking).isAir()) {
                humanDelay = rng.nextInt(3);
            }
            jitter = human() ? new Vec3((rng.nextDouble() - 0.5) * 0.18, (rng.nextDouble() - 0.5) * 0.18, (rng.nextDouble() - 0.5) * 0.18) : Vec3.ZERO;
            breaking = pos;
            breakTicks = 0;
            aimMiss = 0;
        }
        if (++breakTicks > 600) {
            blacklist.put(pos.asLong(), 600);
            replan("Block too slow - rerouting");
            return;
        }

        // Crosshair-first: when mining ore, if the crosshair is already resting on ANY wanted, safe ore, take that one.
        // Without this, two ores next to each other made it swing left/right forever (aim at A -> hits B -> aim at B -> hits A).
        if (state == State.MINE_ORE && mc.hitResult instanceof BlockHitResult ph && ph.getType() == HitResult.Type.BLOCK) {
            BlockPos under = ph.getBlockPos();
            if (!under.equals(pos) && isTarget(mc.level.getBlockState(under)) && !under.equals(feet().below())
                    && pf().canBreakSafely(under) && LavaUtil.breakDanger(mc.level, under, mc.player, strict.get()) == null) {
                if (ore != null && isTarget(mc.level.getBlockState(ore))) vein.add(ore); // come back to it next
                ore = under;
                pos = under;
                breaking = under;
                breakTicks = 0;
                aimMiss = 0;
            }
        }

        // aim point is chosen ONCE per block, so it can't flip between faces from tick to tick
        if (!pos.equals(aimFor)) {
            aimFor = pos;
            aimCache = PlayerControl.aimPoint(mc.level, pos, mc.player.getEyePosition()).add(jitter);
        }
        Vec3 aim = aimCache;
        // already on the block? don't touch the camera: re-aiming every tick made it twitch/spin and could reset break progress
        boolean onIt = mc.hitResult instanceof BlockHitResult b0 && b0.getType() == HitResult.Type.BLOCK && b0.getBlockPos().equals(pos);
        boolean aimed = onIt || PlayerControl.lookAt(aim, rot());
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            if (aimed && ++aimMiss > 40) cantReach(pos);
            return;
        }
        BlockPos hp = bhr.getBlockPos();
        BlockPos target = pos;
        if (!hp.equals(pos)) {
            // Something is in the way. If it's a safe, nearby, non-floor block, just dig through it.
            Pathfinder pf = pf();
            boolean nearby = mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(hp)) <= reach() * reach();
            boolean isFloor = hp.equals(feet().below());
            if (aimed && nearby && !isFloor && pf.canBreakSafely(hp)) {
                target = hp; // dig the blocker; the camera stays put so this can't oscillate
            } else {
                if (aimed && ++aimMiss > 30) cantReach(pos);
                return;
            }
        }
        aimMiss = 0;
        if (LavaUtil.breakDanger(mc.level, target, mc.player, strict.get()) != null) {
            blacklist.put(target.asLong(), 600);
            if (ore != null) blacklist.put(ore.asLong(), 600);
            replan("Lava detected - aborting");
            return;
        }
        if (mc.screen != null) {
            // vanilla doesn't process the attack key while a screen (menu/chat) is open, so drive mining directly.
            // LavaGuard still vets this through the LeftClickBlock event.
            Direction face = hit instanceof BlockHitResult b2 && b2.getBlockPos().equals(target) ? b2.getDirection() : Direction.UP;
            mc.gameMode.continueDestroyBlock(target, face);
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        } else {
            PlayerControl.forceAttack = true;
        }
    }

    private void mineOre() {
        if (ore == null) { state = State.SEARCH; return; }
        if (!isTarget(mc.level.getBlockState(ore))) {
            onOreMined(ore);
            state = State.COLLECT;
            wait = fast() ? 1 : human() ? 4 : 2;
            replans = 0;
            return;
        }
        status = "Mining ore";
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mine(ore);
    }

    private ItemEntityRef nearestDrop() {
        double r = collectRadius.get();
        net.minecraft.world.entity.item.ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (var it : mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, mc.player.getBoundingBox().inflate(r, 3, r))) {
            if (!it.isAlive() || ignoredItems.contains(it.getId())) continue;
            BlockPos ip = it.blockPosition();
            if (LavaUtil.isLava(mc.level, ip) || LavaUtil.touchesLava(mc.level, ip) || it.isInLava()) continue; // never chase loot into lava
            if (!canFit(it.getItem())) continue;                                  // truly no room for it
            if (ModuleManager.get(InvCleaner.class).atLimit(it.getItem())) continue; // junk we'd just throw away again
            double d = it.distanceToSqr(mc.player);
            if (d < bd) { bd = d; best = it; }
        }
        return best == null ? null : new ItemEntityRef(best, Math.sqrt(bd));
    }

    /** Room for this item: an empty slot, or a partial stack of the same thing. */
    private boolean canFit(ItemStack st) {
        var inv = mc.player.getInventory();
        return inv.getFreeSlot() != -1 || inv.getSlotWithRemainingSpace(st) != -1;
    }

    private record ItemEntityRef(net.minecraft.world.entity.item.ItemEntity item, double dist) {}

    /**
     * After an ore: (1) keep mining everything reachable from RIGHT HERE without taking a step,
     * (2) only when nothing is left in reach, pick up all the loot in one go,
     * (3) then continue the vein / find the next one.
     */
    private void collect() {
        PlayerControl.forceAttack = false;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        if (--wait > 0) { status = "Mining vein"; return; } // let the broken block update first

        // 1) more ore in reach? mine it from where we stand
        if (gotoTarget == null || gotoMine) {
            BlockPos here = oreInReach();
            if (here != null) {
                startPath(List.of(feet()), here);
                status = "Mining vein (in reach)";
                return;
            }
        }

        // 2) nothing left in reach: collect the loot (only if there's room for it)
        status = "Collecting";
        if (collectDrops.get() && collectTries < 40) {
            ItemEntityRef ref = nearestDrop();
            if (ref != null) {
                BlockPos ip = ref.item().blockPosition();
                BlockPos f = feet();
                if (ref.dist() < 1.3 || (f.getX() == ip.getX() && f.getZ() == ip.getZ())) {
                    // standing on it: wait for the pickup delay to run out
                    status = "Picking up loot";
                    if (++collectWait > 25) { ignoredItems.add(ref.item().getId()); collectWait = 0; }
                    return;
                }
                collectWait = 0;
                Pathfinder pf = pf();
                List<BlockPos> p = pf.findNear(f, ip, 0);
                if (p == null) p = pf.findNear(f, ip, 1);
                if (p != null && p.size() > 1) {
                    collectTries++;
                    startPath(p, null);
                    afterPath = State.COLLECT;
                    wait = 0;
                    status = "Picking up loot";
                    return;
                }
                ignoredItems.add(ref.item().getId()); // unreachable safely - leave it
                return;
            }
        }

        // 3) rest of the vein (needs a step or two), then the next vein
        PlayerControl.releaseMovement();
        collectTries = 0;
        if ((fast() || veinMine.get()) && nextVeinOre()) return;
        state = State.SEARCH;
        cooldown = 0;
        hasExploreYaw = false;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void onRenderWorld(RenderLevelStageEvent event) {
        if (!showPath.get()) return;
        List<BlockPos> p = path;
        BlockPos o = ore;
        int from = idx;
        WorldRender.draw(event, b -> {
            if (p != null) {
                for (int i = Math.max(0, from); i < p.size() - 1; i++) {
                    BlockPos a = p.get(i), c = p.get(i + 1);
                    int col = Theme.accent(i * 0.05f);
                    b.line(a.getX() + 0.5, a.getY() + 0.1, a.getZ() + 0.5, c.getX() + 0.5, c.getY() + 0.1, c.getZ() + 0.5, col);
                }
                if (from + 1 < p.size()) b.block(p.get(from + 1), ColorUtil.withAlpha(Theme.accent(), 200), ColorUtil.withAlpha(Theme.accent(), 30));
            }
            if (o != null) b.block(o, 0xFF4ADE80, 0x404ADE80);
            for (BlockPos u : unsafeOres) {
                if (u.distSqr(mc.player.blockPosition()) < 48 * 48) b.block(u, 0xC0FF4D4D, 0x20FF4D4D);
            }
        });
    }
}
