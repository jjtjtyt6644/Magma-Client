package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.ButtonSetting;
import dev.magma.client.setting.ModeSetting;
import dev.magma.client.setting.TextSetting;
import dev.magma.client.util.Chat;
import dev.magma.client.util.Notify;
import dev.magma.client.util.PlayerControl;
import dev.magma.client.util.Schematic;
import dev.magma.client.util.Theme;
import dev.magma.client.util.WorldRender;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a schematic for you (.schem / .litematic / .nbt from .minecraft/schematics): layer by layer from the
 * bottom, walking and pillaring into reach, putting up scaffolding under floating blocks and taking it down at the end.
 * Blocks are faced the right way by trying each look direction until the placement matches the schematic.
 */
public class Builder extends Module {
    public final TextSetting file = add(new TextSetting("Schematic", "File name in .minecraft/schematics (type .build list to see them).", "", null, 64));
    public final ModeSetting rotation = add(new ModeSetting("Rotation", "Turn the build around.", "0", "0", "90", "180", "270"));
    public final BoolSetting clearExtra = add(new BoolSetting("Clear Extra Blocks", "Also mine blocks standing where the schematic has air.", false));
    public final BoolSetting airPlace = add(new BoolSetting("Air Place", "Place floating blocks straight into the air (no scaffolding). Works in singleplayer; some servers block it.", true));
    public final BoolSetting removeScaffold = add(new BoolSetting("Remove Scaffold", "Take the scaffolding and pillars down when finished.", true));
    public final BoolSetting preview = add(new BoolSetting("Preview", "Outline the blocks still to place.", true));
    /** world | file | x,y,z | quarter turns | building - so the schematic survives leaving the world. Not shown in the menu. */
    public final TextSetting saved = add(new TextSetting("Saved", "", "", null, 600).visibleWhen(() -> false));
    public final ButtonSetting loadBtn = add(new ButtonSetting("Load", "Show the schematic in front of you so you can place it (hold a stick to move it).", "Load here", () -> load(null)));
    public final ButtonSetting buildBtn = add(new ButtonSetting("Build", "Start building the loaded schematic where it's shown.", "Start building", () -> startBuilding()));
    public final ButtonSetting editorBtn = add(new ButtonSetting("Image Converter", "Open the website that turns a picture into a schematic you can edit.", "Open editor", () -> dev.magma.client.util.EditorServer.open()));
    public final ButtonSetting openFolder = add(new ButtonSetting("Schematics", "Open the folder to drop schematic files into.", "Open folder", Builder::openFolder));

    /** Properties the game sets by itself (connections, power, ...). They don't need to match. */
    private static final Set<String> AUTO_PROPS = Set.of("waterlogged", "powered", "shape", "north", "south", "east", "west",
            "up", "down", "distance", "persistent", "occupied", "triggered", "power", "lit", "open", "has_book", "has_record",
            "attached", "disarmed", "in_wall", "moisture", "age", "stage", "note", "instrument", "enabled", "conditional",
            "extended", "short", "locked", "snowy", "bottom", "hinge", "honey_level", "candles", "pickles", "eggs", "hatch",
            "layers", "bites", "charges", "level", "berries", "unstable", "drag", "tip", "thickness", "vertical_direction",
            "sculk_sensor_phase", "can_summon", "shrieking", "bloom", "cracked", "flower_amount", "segment_amount", "orientation",
            "inverted", "crafting", "ominous", "trial_spawner_state", "vault_state", "has_bottle_0", "has_bottle_1", "has_bottle_2",
            "slot_0_occupied", "slot_1_occupied", "slot_2_occupied", "slot_3_occupied", "slot_4_occupied", "slot_5_occupied");

    private enum Phase { BUILD, CLEANUP }

    private record Work(BlockPos pos, BlockState target, boolean breakIt) {}

    private Schematic base, schem;
    private int rot; // quarter turns clockwise
    private boolean building;
    private long lastStickAction;
    private final List<BlockPos> ghosts = new ArrayList<>();
    private String loadedName;
    private BlockPos origin;
    private Phase phase = Phase.BUILD;
    private final List<Work> todo = new ArrayList<>();
    private final Set<BlockPos> scaffold = new HashSet<>();
    private final Map<BlockPos, Integer> deferred = new HashMap<>();
    private final Map<BlockPos, Integer> fails = new HashMap<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private int tick, cooldown, totalPlace, toPlace;
    private BlockPos moveGoal, breaking;
    private int moveStarted;
    private long startedAt;
    private String status = "Idle";

    public Builder() {
        super("Builder", "Builds a schematic for you: scaffolds, places every block facing the right way, then cleans up.", Category.WORLD, -1);
        advanced(clearExtra, removeScaffold, preview);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onStickClick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onStickScroll);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onArrowKey);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onAnyTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onLeave);
    }

    @Override public boolean persistEnabled() { return false; }

    @Override
    public String getInfo() {
        if (schem == null) return null;
        if (!building) return schem.name + " preview";
        int pct = totalPlace == 0 ? 100 : (int) (100.0 * (totalPlace - toPlace) / totalPlace);
        return schem.name + " " + pct + "%";
    }

    public String status() { return status; }

    // ------------------------------------------------------------------ commands / start

    /** .build name [rotation] | .build start | .build stop | .build clear | .build here | .build rotate | .build list | .build folder */
    public static boolean handleCommand(String msg) {
        if (!msg.startsWith(".build")) return false;
        Builder b = ModuleManager.get(Builder.class);
        String[] a = msg.trim().split("\\s+");
        if (a.length == 1) {
            Chat.info("Builder: .build <file> loads it | .build editor (image to schematic) | .build start | .build stop | .build clear | .build here | .build rotate | .build list | .build folder");
            Chat.info("Put schematics (.schem, .litematic, .nbt) in " + Schematic.folder());
            return true;
        }
        switch (a[1].toLowerCase()) {
            case "start", "go" -> b.startBuilding();
            case "stop", "pause" -> b.stopBuilding(true);
            case "clear", "unload", "cancel" -> b.setEnabled(false);
            case "here" -> { if (b.schem != null) b.moveTo(b.inFront()); }
            case "rotate" -> b.rotate();
            case "list" -> {
                List<Path> l = Schematic.list();
                if (l.isEmpty()) Chat.info("No schematics yet. Drop .schem/.litematic/.nbt files into " + Schematic.folder());
                else for (Path p : l) Chat.info(" - " + p.getFileName());
            }
            case "folder" -> openFolder();
            case "editor", "convert", "web" -> dev.magma.client.util.EditorServer.open();
            default -> {
                if (a.length > 2 && List.of("0", "90", "180", "270").contains(a[2])) b.rotation.set(a[2]);
                b.load(msg.trim().substring(7).replaceFirst("\\s+(0|90|180|270)$", "").trim());
            }
        }
        return true;
    }

    private static void openFolder() {
        net.minecraft.Util.getPlatform().openFile(Schematic.folder().toFile());
    }

    /** Load a schematic and show it in front of you (nothing is built until you start it). */
    public void load(String name) {
        if (mc.player == null) return;
        if (name != null && !name.isBlank()) file.set(name);
        if (file.get().isBlank()) {
            Chat.info("Builder: type a schematic name first (.build list shows them).");
            return;
        }
        Path p = Schematic.find(file.get().trim());
        if (p == null) {
            Chat.info("Builder: no schematic called \"" + file.get() + "\" in " + Schematic.folder());
            return;
        }
        try {
            base = Schematic.load(p);
            loadedName = p.getFileName().toString();
        } catch (Exception e) {
            Chat.info("Builder: couldn't read " + p.getFileName() + " - " + e.getMessage());
            return;
        }
        if (building) stopBuilding(false);
        rot = switch (rotation.get()) { case "90" -> 1; case "180" -> 2; case "270" -> 3; default -> 0; };
        schem = base.rotated(rotationOf(rot));
        origin = null;
        moveTo(inFront());
        if (!isEnabled()) setEnabled(true);
        Chat.info("Builder: loaded " + schem.name + " (" + schem.sizeX + "x" + schem.sizeY + "x" + schem.sizeZ + ", "
                + schem.solidCount() + " blocks)" + (schem.unknown > 0 ? " - " + schem.unknown + " unknown blocks skipped" : "") + ".");
        Chat.info("Hold a stick: right-click = move here, left-click = rotate, arrow keys (or Shift+scroll) = nudge, PgUp/PgDn = up/down. Then .build start");
        Notify.show("Schematic loaded - place it with a stick", Theme.accent());
    }

    private static Rotation rotationOf(int quarter) {
        return switch (quarter & 3) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** The spot 3 blocks in front of you, at your feet's level. */
    private BlockPos inFront() {
        return mc.player.blockPosition().relative(mc.player.getDirection(), 3 + Math.max(schem.sizeX, schem.sizeZ) / 2);
    }

    /** Put the build's bottom-centre on this spot. */
    private void moveTo(BlockPos spot) {
        origin = spot.offset(-schem.sizeX / 2, 0, -schem.sizeZ / 2);
        afterMove();
    }

    private void nudge(Direction d, int n) {
        if (origin == null) return;
        origin = origin.relative(d, n);
        afterMove();
    }

    /** Turn 90 degrees clockwise around its centre. */
    private void rotate() {
        if (schem == null || building) return;
        BlockPos centre = origin.offset(schem.sizeX / 2, 0, schem.sizeZ / 2);
        rot = (rot + 1) & 3;
        schem = base.rotated(rotationOf(rot));
        rotation.set(String.valueOf(rot * 90));
        moveTo(centre);
    }

    private void afterMove() {
        saveState();
        todo.clear();
        ghosts.clear();
        if (mc.level != null) rescan();
    }

    /** Begin building the loaded schematic where it's shown. */
    public void startBuilding() {
        if (schem == null || origin == null) {
            Chat.info("Builder: load a schematic first (.build <name>).");
            return;
        }
        if (building) return;
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (am.isEnabled()) am.setEnabled(false);
        if (!isEnabled()) setEnabled(true);
        building = true;
        phase = Phase.BUILD;
        todo.clear();
        deferred.clear();
        fails.clear();
        missing.clear();
        scaffold.clear();
        am.placedLog = scaffold;
        tick = 0;
        cooldown = 0;
        moveGoal = null;
        breaking = null;
        startedAt = System.currentTimeMillis();
        rescan();
        totalPlace = toPlace;
        ensureJunk();
        saveState();
        Chat.info("Builder: building " + schem.name + " at " + origin.toShortString() + ". .build stop pauses, .build clear removes it.");
        reportMissing();
    }

    /** Stop building but keep the schematic shown (so you can move it or resume). */
    public void stopBuilding(boolean say) {
        if (!building) return;
        building = false;
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (am.placedLog == scaffold) am.placedLog = null;
        if (moveGoal != null && am.getGotoTarget() != null && !am.isEnabled()) am.cancelGoto();
        moveGoal = null;
        if (breaking != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        breaking = null;
        PlayerControl.releaseMovement();
        status = "Preview";
        if (!leaving) saveState(); // leaving the world isn't "stopping": keep "was building" so it carries on next time
        if (say) Chat.info("Builder: paused. .build start to continue, .build clear to remove it.");
    }

    @Override
    protected void onEnable() {
        if (schem == null) mc.execute(() -> {
            // switched on from the menu with nothing loaded: load the typed file
            setEnabled(false);
            load(null);
        });
    }

    @Override
    protected void onDisable() {
        boolean keep = leaving; // leaving the world: keep it saved for next time
        stopBuilding(false);
        if (!keep) { saved.set(""); dev.magma.client.config.ConfigManager.save(); }
        schem = null;
        base = null;
        origin = null;
        todo.clear();
        ghosts.clear();
        status = "Idle";
    }

    // ------------------------------------------------------------------ remembering it across sessions

    private boolean leaving;
    private String restoredFor;

    /** Which world this is: the save name in singleplayer, the address on a server, plus the dimension. */
    private String lastWorldKey = "";

    private String worldKey() {
        if (mc.level == null) return lastWorldKey;
        String w = mc.getSingleplayerServer() != null ? "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName()
                : mc.getCurrentServer() != null ? "mp:" + mc.getCurrentServer().ip : null;
        if (w == null) return lastWorldKey; // shutting down: use what we knew while playing
        return lastWorldKey = w + "@" + mc.level.dimension().location();
    }

    private void saveState() {
        if (schem == null || origin == null || loadedName == null || mc.level == null) return;
        saved.set(worldKey() + "|" + loadedName + "|" + origin.getX() + "," + origin.getY() + "," + origin.getZ() + "|" + rot + "|" + building);
        dev.magma.client.config.ConfigManager.save();
    }

    /** Back in a world with a saved schematic: show it again exactly where it was. */
    private void onAnyTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        if (mc.player != null && mc.player.tickCount % 100 == 1) worldKey();
        if (mc.player == null || mc.level == null || isEnabled() || mc.player.tickCount < 40 || mc.player.tickCount % 20 != 0) return;
        String sv = saved.get();
        if (sv.isBlank()) return;
        String[] f = sv.split("\\|");
        String wk = worldKey();
        if (f.length < 5 || !f[0].equals(wk) || wk.equals(restoredFor)) return;
        restoredFor = wk;
        try {
            Path p = Schematic.find(f[1]);
            if (p == null) return;
            base = Schematic.load(p);
            loadedName = p.getFileName().toString();
            rot = Integer.parseInt(f[3]) & 3;
            schem = base.rotated(rotationOf(rot));
            String[] xyz = f[2].split(",");
            origin = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            setEnabled(true);
            afterMove();
            boolean wasBuilding = Boolean.parseBoolean(f[4]);
            Chat.info("Builder: " + schem.name + " is still here at " + origin.toShortString()
                    + (wasBuilding ? " - continuing the build (.build stop to pause)." : ". .build start to build it, .build clear to remove it."));
            if (wasBuilding) startBuilding();
        } catch (Exception ex) {
            Chat.info("Builder: couldn't bring back " + f[1] + " - " + ex.getMessage());
        }
    }

    /** Leaving the world: put it away, but keep it saved. */
    private void onLeave(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) {
        restoredFor = null;
        if (!isEnabled()) return;
        saveState();
        leaving = true;
        try { setEnabled(false); } finally { leaving = false; }
    }

    // ------------------------------------------------------------------ stick controls

    private boolean holdingStick() {
        return mc.player != null && mc.player.getMainHandItem().is(Items.STICK);
    }

    private void onStickClick(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered e) {
        if (!isEnabled() || schem == null || building || !holdingStick() || mc.screen != null) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        long now = System.currentTimeMillis();
        if (now - lastStickAction < 200) return; // held button: once per click
        lastStickAction = now;
        if (e.isAttack()) {
            rotate();
            Notify.show("Rotated " + rot * 90 + "°", Theme.accent());
        } else if (e.isUseItem()) {
            if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                moveTo(bh.getBlockPos().relative(bh.getDirection()));
            } else {
                // looking at nothing: drop it a few blocks ahead of where you look
                Vec3 at = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(8));
                moveTo(BlockPos.containing(at));
            }
        }
    }

    /** Holding the stick: arrow keys slide the build (up = away from you), Page Up/Down raise or lower it. */
    private void onArrowKey(net.neoforged.neoforge.client.event.InputEvent.Key e) {
        if (!isEnabled() || schem == null || building || !holdingStick() || mc.screen != null) return;
        if (e.getAction() == org.lwjgl.glfw.GLFW.GLFW_RELEASE) return;
        Direction f = mc.player.getDirection();
        Direction d = switch (e.getKey()) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> f;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> f.getOpposite();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> f.getCounterClockWise();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> f.getClockWise();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> Direction.UP;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> Direction.DOWN;
            default -> null;
        };
        if (d != null) nudge(d, 1);
    }

    private void onStickScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent e) {
        if (!isEnabled() || schem == null || building || !holdingStick() || mc.screen != null) return;
        // Shift (or Ctrl, like Litematica) + scroll; check the real keys so it works whatever sneak is bound to
        if (!mc.options.keyShift.isDown() && !net.minecraft.client.gui.screens.Screen.hasShiftDown()
                && !net.minecraft.client.gui.screens.Screen.hasControlDown()) return;
        e.setCanceled(true);
        int n = e.getScrollDeltaY() > 0 ? 1 : -1;
        float pitch = mc.player.getXRot();
        Direction d = pitch < -50 ? Direction.UP : pitch > 50 ? Direction.DOWN : mc.player.getDirection();
        nudge(d, n);
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || schem == null) return;
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (am.isEnabled()) {
            Chat.info("Builder stopped: AutoMiner was turned on.");
            setEnabled(false);
            return;
        }
        if (!building) { // preview: just keep the ghost up to date
            if (++tick % 20 == 0 || todo.isEmpty() && ghosts.isEmpty()) rescan();
            status = "Preview";
            return;
        }
        if (tick % 20 == 0) ensureJunk();
        am.driveIfIdle(); // walks/pillars us into reach
        tick++;
        deferred.entrySet().removeIf(e -> e.getValue() <= tick);
        if (cooldown > 0) { cooldown--; return; }
        if (tick % 10 == 0 || todo.isEmpty()) rescan();

        if (phase == Phase.CLEANUP) {
            cleanup();
            return;
        }
        // 1) everything we can reach right now, several per tick (lowest first so nothing ends up floating)
        if (placeInReach()) return;
        // 2) the only blocks left within reach are where our body is: get clear of them right away (this was the slow 99%)
        if (clearOfMyBlocks()) return;
        Work w = pick();
        if (w == null) {
            if (!deferred.isEmpty()) { status = "Waiting (" + deferred.size() + " spots to retry)"; return; }
            boolean anyLeft = todo.stream().anyMatch(x -> !x.breakIt());
            if (anyLeft && !missing.isEmpty()) {
                status = "Out of materials";
            }
            phase = Phase.CLEANUP;
            return;
        }
        act(w);
    }

    private void act(Work w) {
        BlockPos pos = w.pos();
        if (!inReach(pos)) {
            moveNear(pos);
            return;
        }
        stopMoving();
        if (w.breakIt()) {
            status = "Clearing " + pos.toShortString();
            breakBlock(pos);
            return;
        }
        if (breaking != null) { mc.gameMode.stopDestroyBlock(); breaking = null; }
        if (inTheWay((pos))) {
            stepAside(); // we're standing where it goes: move out of the way
            return;
        }
        if (!supported(pos, w.target())) {
            status = "Scaffolding";
            if (!placeScaffoldFor(pos)) defer(pos, 100);
            return;
        }
        status = "Placing " + name(w.target());
        if (place(pos, w.target())) todo.remove(w);
        else {
            if (fails.merge(pos, 1, Integer::sum) >= 4) {
                fails.remove(pos);
                defer(pos, 60);
            }
        }
    }

    /** Place every block in reach this tick (up to a few). Returns true if it placed anything. */
    private boolean placeInReach() {
        if (todo.stream().anyMatch(w -> w.breakIt() && !deferred.containsKey(w.pos()))) return false; // clear blockers first
        Vec3 eye = mc.player.getEyePosition();
        List<Work> near = new ArrayList<>();
        for (Work w : todo) {
            if (w.breakIt() || deferred.containsKey(w.pos()) || !inReach(w.pos())) continue;
            if (!hasItem(w.target().getBlock().asItem()) || !supported(w.pos(), w.target())) continue;
            if (inTheWay((w.pos()))) continue;
            near.add(w);
        }
        if (near.isEmpty()) return false;
        near.sort(java.util.Comparator.<Work>comparingInt(w -> w.pos().getY()).thenComparingDouble(w -> eye.distanceToSqr(Vec3.atCenterOf(w.pos()))));
        int budget = mc.player.getAbilities().instabuild ? 6 : 3;
        int done = 0;
        if (breaking != null) { mc.gameMode.stopDestroyBlock(); breaking = null; }
        for (Work w : near) {
            if (done >= budget) break;
            if (place(w.pos(), w.target())) {
                todo.remove(w); // done - don't try this spot again before the next rescan
                done++;
            } else if (fails.merge(w.pos(), 1, Integer::sum) >= 4) {
                fails.remove(w.pos());
                defer(w.pos(), 60);
            }
        }
        if (done == 0) return false;
        stopMoving();
        mc.options.keyJump.setDown(false);
        status = "Placing (" + toPlaceLeft() + " left)";
        cooldown = mc.player.getAbilities().instabuild ? 0 : 1;
        return true;
    }

    /**
     * Would our body block this spot? Checks where we are AND where we were last tick: the server only learns our new
     * position after the click, so placing the moment we step clear gets refused.
     */
    private boolean inTheWay(BlockPos pos) {
        AABB spot = new AABB(pos);
        AABB now = mc.player.getBoundingBox().inflate(0.03); // a hair stricter than the server, never looser
        AABB before = now.move(mc.player.xo - mc.player.getX(), mc.player.yo - mc.player.getY(), mc.player.zo - mc.player.getZ());
        return now.intersects(spot) || before.intersects(spot);
    }

    /**
     * Blocks we could place right now except that we're standing in them (usually the last few at the end):
     * fly up above them in creative, or jump so they go in underneath us (pillaring) in survival.
     */
    private boolean clearOfMyBlocks() {
        int top = Integer.MIN_VALUE;
        for (Work w : todo) {
            if (w.breakIt() || deferred.containsKey(w.pos()) || !inReach(w.pos()) || !inTheWay(w.pos())) continue;
            if (!hasItem(w.target().getBlock().asItem())) continue;
            top = Math.max(top, w.pos().getY());
        }
        if (top == Integer.MIN_VALUE) return false;
        stopMovingGoto();
        status = "Getting out of the way";
        double want = top + 1.15; // feet just above the highest one
        if (mc.player.getAbilities().mayfly) {
            mc.player.getAbilities().flying = true;
            double dy = want - mc.player.getY();
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x * 0.5, dy > 0 ? Math.max(0.2, Math.min(0.6, dy * 0.6)) : 0,
                    mc.player.getDeltaMovement().z * 0.5);
        } else {
            mc.options.keyJump.setDown(true); // jump: they get placed under us at the top of the jump
        }
        return true;
    }

    /** Cancel a walk without touching jump/forward (used while hopping out of the way). */
    private void stopMovingGoto() {
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (moveGoal != null && am.getGotoTarget() != null) am.cancelGoto();
        moveGoal = null;
    }

    private int toPlaceLeft() {
        int n = 0;
        for (Work w : todo) if (!w.breakIt()) n++;
        return n;
    }

    /** Standing where a block goes: move to the nearest free spot that isn't part of the build. */
    private void stepAside() {
        Set<BlockPos> targets = new HashSet<>();
        for (Work w : todo) if (!w.breakIt()) targets.add(w.pos());
        BlockPos feet = mc.player.blockPosition(), best = null;
        double bd = Double.MAX_VALUE;
        boolean flying = mc.player.getAbilities().mayfly && mc.player.getAbilities().instabuild;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= (flying ? 3 : 1); dy++) {
            BlockPos c = feet.offset(dx, dy, dz);
            if (targets.contains(c) || targets.contains(c.above())) continue;
            if (!mc.level.getBlockState(c).getCollisionShape(mc.level, c).isEmpty()
                    || !mc.level.getBlockState(c.above()).getCollisionShape(mc.level, c.above()).isEmpty()) continue;
            if (!flying && mc.level.getBlockState(c.below()).getCollisionShape(mc.level, c.below()).isEmpty()) continue;
            double d = dx * dx + dz * dz + dy * dy * 1.5;
            if (d > 0 && d < bd) { bd = d; best = c; }
        }
        status = "Stepping out of the way";
        // the block goes where our feet are and there's headroom: just jump - it gets placed under us mid-air
        boolean feetTarget = targets.contains(feet), headroom = mc.level.getBlockState(feet.above(2)).getCollisionShape(mc.level, feet.above(2)).isEmpty();
        if (feetTarget && headroom && !flying) { mc.options.keyJump.setDown(true); return; }
        if (best == null) { flyOrJump(); return; }
        Vec3 goal = Vec3.atBottomCenterOf(best).add(0, 0.25, 0), d = goal.subtract(mc.player.position());
        if (flying) {
            mc.player.getAbilities().flying = true;
            // aim a bit above the spot and hover once there: never creep up on it and end up a hair inside the block
            if (d.length() < 0.1) mc.player.setDeltaMovement(Vec3.ZERO);
            else mc.player.setDeltaMovement(d.normalize().scale(Math.max(0.12, Math.min(0.6, d.length() * 0.5))));
        } else {
            // a short hop to the side: steer straight at it
            float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f;
            PlayerControl.rotate(yaw, mc.player.getXRot(), 30f);
            mc.options.keyUp.setDown(d.horizontalDistance() > 0.25);
            mc.options.keyJump.setDown(best.getY() > feet.getY());
        }
    }

    /** Nowhere free nearby: go up (fly in creative, otherwise jump and let the next block go underneath). */
    private void flyOrJump() {
        if (mc.player.getAbilities().mayfly) {
            mc.player.getAbilities().flying = true;
            mc.player.setDeltaMovement(0, 0.5, 0);
        } else mc.options.keyJump.setDown(true);
    }

    /** Rebuild the list of what still needs doing. */
    private final List<BlockPos> ghostTmp = new ArrayList<>();

    /** Inside the build, completely covered by other full blocks: never seen, so don't draw it. */
    private boolean hidden(BlockPos rel) {
        for (Direction d : Direction.values()) {
            BlockState n = schem.blocks.get(rel.relative(d));
            if (n == null || !n.canOcclude()) return false;
        }
        return true;
    }

    private void rescan() {
        ghostTmp.clear();
        todo.clear();
        missing.clear();
        toPlace = 0;
        Map<Item, Integer> need = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : schem.blocks.entrySet()) {
            BlockPos pos = origin.offset(e.getKey());
            if (!mc.level.isLoaded(pos)) continue;
            BlockState target = e.getValue(), cur = mc.level.getBlockState(pos);
            if (target.isAir()) {
                if (clearExtra.get() && !cur.isAir() && !cur.canBeReplaced() && !scaffold.contains(pos) && cur.getFluidState().isEmpty())
                    todo.add(new Work(pos, target, true));
                continue;
            }
            if (skip(target) || matches(cur, target)) continue;
            if (!cur.isAir() && !cur.canBeReplaced()) {
                todo.add(new Work(pos, target, true)); // wrong block in the way
            }
            todo.add(new Work(pos, target, false));
            if (ghosts.size() < 20000 && !hidden(e.getKey())) ghostTmp.add(pos);
            toPlace++;
            need.merge(target.getBlock().asItem(), 1, Integer::sum);
        }
        Vec3 eye = mc.player.getEyePosition();
        ghostTmp.sort(java.util.Comparator.comparingDouble(q -> eye.distanceToSqr(q.getX() + 0.5, q.getY() + 0.5, q.getZ() + 0.5)));
        ghosts.clear();
        ghosts.addAll(ghostTmp.subList(0, Math.min(ghostTmp.size(), 6000)));
        if (!mc.player.getAbilities().instabuild) {
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                int have = count(e.getKey());
                if (have < e.getValue()) missing.put(e.getKey(), e.getValue() - have);
            }
        }
    }

    /** Next job: clear blockers first, then the lowest layer, nearest first. */
    private Work pick() {
        Vec3 eye = mc.player.getEyePosition();
        Work best = null;
        double bd = Double.MAX_VALUE;
        for (Work w : todo) {
            if (!w.breakIt() || deferred.containsKey(w.pos())) continue;
            double d = eye.distanceToSqr(Vec3.atCenterOf(w.pos()));
            if (d < bd) { bd = d; best = w; }
        }
        if (best != null) return best;
        // placing: layer by layer from the bottom; a layer we have no blocks for doesn't hold up the rest
        java.util.TreeMap<Integer, List<Work>> layers = new java.util.TreeMap<>();
        for (Work w : todo) {
            if (w.breakIt() || deferred.containsKey(w.pos())) continue;
            if (!hasItem(w.target().getBlock().asItem())) continue;
            layers.computeIfAbsent(w.pos().getY(), k -> new ArrayList<>()).add(w);
        }
        for (List<Work> layer : layers.values()) {
            Work nearest = null, nearestSupported = null;
            double nd = Double.MAX_VALUE, sd = Double.MAX_VALUE;
            for (Work w : layer) {
                double d = eye.distanceToSqr(Vec3.atCenterOf(w.pos()));
                if (d < nd) { nd = d; nearest = w; }
                if (d < sd && supported(w.pos(), w.target())) { sd = d; nearestSupported = w; }
            }
            return nearestSupported != null ? nearestSupported : nearest;
        }
        return null;
    }

    private void cleanup() {
        if (!removeScaffold.get()) { finish(); return; }
        List<BlockPos> left = new ArrayList<>();
        for (BlockPos p : scaffold) {
            BlockState s = mc.level.getBlockState(p);
            if (s.isAir() || !s.getFluidState().isEmpty() || deferred.containsKey(p)) continue;
            BlockState want = schem.blocks.get(p.subtract(origin));
            if (want != null && !want.isAir()) continue; // turned out to be part of the build
            left.add(p);
        }
        if (left.isEmpty()) { finish(); return; }
        status = "Removing scaffold (" + left.size() + " left)";
        // 1) standing on our own pillar: dig straight down through it
        BlockPos under = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.2, mc.player.getZ());
        if (mc.player.onGround() && left.contains(under)) {
            stopMoving();
            breakBlock(under);
            return;
        }
        // 2) the highest one we can reach (top-down, so nothing is left floating)
        BlockPos best = null;
        for (BlockPos p : left) if (inReach(p) && (best == null || p.getY() > best.getY())) best = p;
        if (best != null) {
            stopMoving();
            breakBlock(best);
            return;
        }
        // 3) go to the nearest pillar top
        Vec3 eye = mc.player.getEyePosition();
        BlockPos top = null;
        for (BlockPos p : left) {
            if (left.contains(p.above())) continue;
            if (top == null || eye.distanceToSqr(Vec3.atCenterOf(p)) < eye.distanceToSqr(Vec3.atCenterOf(top))) top = p;
        }
        moveNear(top != null ? top : left.get(0));
    }

    private void finish() {
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        rescan();
        if (toPlace == 0) {
            Chat.info("Builder: finished " + schem.name + " in " + secs / 60 + "m " + secs % 60 + "s.");
            Notify.show("Build finished", Theme.OK);
        } else {
            Chat.info("Builder: stopped with " + toPlace + " blocks left.");
            reportMissing();
            Notify.show("Build stopped - " + toPlace + " left", Theme.WARN);
        }
        setEnabled(false);
    }

    private void reportMissing() {
        if (missing.isEmpty()) return;
        StringBuilder sb = new StringBuilder("Builder: missing materials - ");
        int n = 0;
        for (Map.Entry<Item, Integer> e : missing.entrySet()) {
            if (n++ > 0) sb.append(", ");
            if (n > 8) { sb.append("..."); break; }
            sb.append(e.getValue()).append("x ").append(e.getKey().getDescription().getString());
        }
        Chat.info(sb.toString());
    }

    // ------------------------------------------------------------------ moving

    private boolean inReach(BlockPos pos) {
        double reach = mc.player.blockInteractionRange() - 0.4;
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
    }

    private void moveNear(BlockPos pos) {
        if (mc.player.getAbilities().mayfly && mc.player.getAbilities().instabuild) { flyNear(pos); return; }
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        BlockPos goal = pos.above();
        if (am.getGotoTarget() != null && goal.equals(moveGoal)) {
            status = "Walking to " + pos.toShortString();
            if (tick - moveStarted > 20 * 40) { // 40 s without getting there
                am.cancelGoto();
                defer(pos, 400);
                moveGoal = null;
            }
            return;
        }
        if (goal.equals(moveGoal) && am.getGotoTarget() == null) {
            // the trip ended but we're still out of reach: try other blocks first
            moveGoal = null;
            if (fails.merge(pos, 1, Integer::sum) >= 2) {
                fails.remove(pos);
                defer(pos, 400);
            }
            return;
        }
        am.placedLog = scaffold;
        am.startGotoQuiet(goal, true);
        am.tripLabel = "Building";
        moveGoal = goal;
        moveStarted = tick;
        status = "Walking to " + pos.toShortString();
    }

    /** Creative: just fly to a spot a couple of blocks beside the target (much faster than walking/pillaring). */
    private void flyNear(BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        Vec3 away = new Vec3(mc.player.getX() - c.x, 0, mc.player.getZ() - c.z);
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        Vec3 goal = c.add(away.normalize().scale(2.2)).add(0, -1.0, 0); // eyes level with the block
        Vec3 d = goal.subtract(mc.player.position());
        double dist = d.length();
        mc.player.getAbilities().flying = true;
        status = "Flying to " + pos.toShortString();
        if (dist < 0.3) { mc.player.setDeltaMovement(Vec3.ZERO); return; }
        Vec3 v = d.normalize().scale(Math.min(0.8, dist * 0.45));
        if (mc.player.horizontalCollision) v = v.add(0, 0.4, 0); // bumped into something: go over it
        mc.player.setDeltaMovement(v);
        if (++flyTicks > 20 * 15) { flyTicks = 0; defer(pos, 200); } // couldn't get there: try others first
    }

    private int flyTicks;

    private void stopMoving() {
        flyTicks = 0;
        if (mc.options.keyUp.isDown() || mc.options.keyJump.isDown()) { mc.options.keyUp.setDown(false); mc.options.keyJump.setDown(false); }
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (moveGoal != null && am.getGotoTarget() != null) am.cancelGoto();
        moveGoal = null;
    }

    private void defer(BlockPos pos, int ticks) {
        deferred.put(pos, tick + ticks);
    }

    // ------------------------------------------------------------------ placing

    /** Things we don't place ourselves: air/fluids, the top half of doors/plants, bed heads, unobtainable blocks. */
    private static boolean skip(BlockState s) {
        Block b = s.getBlock();
        if (s.isAir() || b instanceof LiquidBlock || b == Blocks.PISTON_HEAD || b == Blocks.MOVING_PISTON
                || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL || b == Blocks.FIRE || b == Blocks.SOUL_FIRE
                || b == Blocks.BUBBLE_COLUMN) return true;
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        if (b instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) return true;
        return b.asItem() == Items.AIR;
    }

    static boolean matches(BlockState cur, BlockState target) {
        if (cur.getBlock() != target.getBlock()) return false;
        for (Property<?> p : target.getProperties()) {
            if (AUTO_PROPS.contains(p.getName())) continue;
            if (!cur.hasProperty(p) || !cur.getValue(p).equals(target.getValue(p))) return false;
        }
        return true;
    }

    private static String name(BlockState s) {
        return s.getBlock().getName().getString();
    }

    /** Is there a face to click on (and, for sand/gravel, something underneath)? */
    private boolean supported(BlockPos pos, BlockState target) {
        if (target.getBlock() instanceof FallingBlock && clickable(mc.level.getBlockState(pos.below())) == false) return false;
        if (airPlace.get()) return true;
        for (Direction d : Direction.values()) if (clickable(mc.level.getBlockState(pos.relative(d)))) return true;
        return false;
    }

    /** A block we can place against without opening a menu or using it. */
    private static boolean clickable(BlockState s) {
        if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty() && s.getBlock() instanceof LiquidBlock) return false;
        if (s.hasBlockEntity()) return false;
        return !(s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.BUTTONS)
                || s.is(BlockTags.BEDS) || s.is(BlockTags.ANVIL) || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.LEVER)
                || s.is(Blocks.NOTE_BLOCK) || s.is(Blocks.REPEATER) || s.is(Blocks.COMPARATOR) || s.is(Blocks.CAKE)
                || s.is(Blocks.CARTOGRAPHY_TABLE) || s.is(Blocks.LOOM) || s.is(Blocks.STONECUTTER) || s.is(Blocks.GRINDSTONE)
                || s.is(Blocks.SMITHING_TABLE) || s.is(Blocks.FLETCHING_TABLE) || s.is(Blocks.RESPAWN_ANCHOR)
                || s.is(Blocks.DAYLIGHT_DETECTOR) || s.is(Blocks.REDSTONE_WIRE) || s.is(BlockTags.CAULDRONS)
                || s.is(BlockTags.CANDLES) || s.is(BlockTags.FLOWER_POTS));
    }

    private record Plan(BlockPos against, Direction face, Vec3 hit, float yaw, float pitch, boolean exact) {}

    /**
     * Try every face to click and every way to look until the game would place exactly the state we want
     * (stairs, logs, slabs, observers... all depend on where you look and which face you click).
     */
    private Plan plan(BlockPos pos, BlockState target, ItemStack stack) {
        float oy = mc.player.getYRot(), op = mc.player.getXRot();
        Plan fallback = null;
        float[] yaws = {oy, 0, 90, 180, -90};
        float[] pitches = {0, 80, -80};
        try {
            for (Direction d : Direction.values()) {
                BlockPos against = pos.relative(d);
                if (!clickable(mc.level.getBlockState(against))) continue;
                Direction face = d.getOpposite();
                double[] hys = face.getAxis() == Direction.Axis.Y ? new double[]{face == Direction.UP ? 1.0 : 0.0} : new double[]{0.25, 0.75};
                for (double hy : hys) {
                    Vec3 hit = new Vec3(against.getX() + 0.5 + face.getStepX() * 0.5, against.getY() + hy,
                            against.getZ() + 0.5 + face.getStepZ() * 0.5);
                    if (mc.player.getEyePosition().distanceToSqr(hit) > 30) continue;
                    for (float yaw : yaws) for (float pitch : pitches) {
                        mc.player.setYRot(yaw);
                        mc.player.setXRot(pitch);
                        BlockPlaceContext ctx = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, new BlockHitResult(hit, face, against, false));
                        if (!ctx.getClickedPos().equals(pos) || !ctx.canPlace()) continue;
                        BlockState st = target.getBlock().getStateForPlacement(ctx);
                        if (st == null) continue;
                        if (matches(st, target)) return new Plan(against, face, hit, yaw, pitch, true);
                        if (fallback == null) fallback = new Plan(against, face, hit, yaw, pitch, false);
                    }
                }
            }
            // nothing to click on (or no exact match): click the empty spot itself ("air place")
            if (airPlace.get() && mc.level.getBlockState(pos).canBeReplaced()) {
                Plan airFallback = null;
                for (Direction face : Direction.values()) {
                    double[] hys = face.getAxis() == Direction.Axis.Y ? new double[]{face == Direction.UP ? 1.0 : 0.0} : new double[]{0.25, 0.75};
                    for (double hy : hys) {
                        Vec3 hit = new Vec3(pos.getX() + 0.5 + face.getStepX() * 0.5, pos.getY() + hy, pos.getZ() + 0.5 + face.getStepZ() * 0.5);
                        for (float yaw : yaws) for (float pitch : pitches) {
                            mc.player.setYRot(yaw);
                            mc.player.setXRot(pitch);
                            BlockPlaceContext ctx = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, new BlockHitResult(hit, face, pos, false));
                            if (!ctx.getClickedPos().equals(pos) || !ctx.canPlace()) continue;
                            BlockState st = target.getBlock().getStateForPlacement(ctx);
                            if (st == null) continue;
                            if (matches(st, target)) return new Plan(pos, face, hit, yaw, pitch, true);
                            if (airFallback == null) airFallback = new Plan(pos, face, hit, yaw, pitch, false);
                        }
                    }
                }
                if (fallback == null) fallback = airFallback;
            }
        } finally {
            mc.player.setYRot(oy);
            mc.player.setXRot(op);
        }
        return fallback;
    }

    private boolean place(BlockPos pos, BlockState target) {
        Item item = target.getBlock().asItem();
        int slot = takeToHotbar(item);
        if (slot < 0) { defer(pos, 200); return false; }
        PlayerControl.selectSlot(slot);
        ItemStack stack = mc.player.getInventory().getItem(slot);
        Plan p = plan(pos, target, stack);
        if (p == null) return false;
        click(p, slot);
        cooldown = mc.player.getAbilities().instabuild ? 1 : 2;
        return true;
    }

    /** Look the way the plan says (told to the server first), then right-click the face. */
    private void click(Plan p, int slot) {
        mc.player.setYRot(p.yaw());
        mc.player.setXRot(p.pitch());
        mc.player.yRotO = p.yaw();
        mc.player.xRotO = p.pitch();
        if (mc.getConnection() != null)
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(p.yaw(), p.pitch(), mc.player.onGround()));
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(p.hit(), p.face(), p.against(), false));
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Floating block (nothing to click on yet): put a junk block next to it, on a spot the build leaves empty,
     * so there's something to place against. Removed again at the end.
     */
    private boolean placeScaffoldFor(BlockPos pos) {
        // breadth-first through empty, not-part-of-the-build cells until one touches something solid
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        q.add(pos);
        parent.put(pos, pos);
        BlockPos spot = null;
        while (!q.isEmpty() && parent.size() < 400) {
            BlockPos c = q.poll();
            if (!c.equals(pos)) {
                boolean touches = false;
                for (Direction d : Direction.values()) if (clickable(mc.level.getBlockState(c.relative(d)))) { touches = true; break; }
                if (touches) { spot = c; break; }
            }
            if (c.distManhattan(pos) >= 5) continue;
            for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                BlockPos n = c.relative(d);
                if (parent.containsKey(n) || !mc.level.getBlockState(n).canBeReplaced()) continue;
                BlockState want = schem.blocks.get(n.subtract(origin));
                if (want != null && !want.isAir()) continue; // don't block a spot the build needs
                if (mc.player.getBoundingBox().intersects(new AABB(n))) continue;
                parent.put(n, c);
                q.add(n);
            }
        }
        if (spot == null) return false;
        if (!inReach(spot)) { moveNear(spot); return true; }
        int slot = junkSlot();
        if (slot < 0) {
            if (tick % 200 == 1) Chat.info("Builder: need junk blocks (cobblestone, dirt, netherrack...) for scaffolding.");
            return false;
        }
        PlayerControl.selectSlot(slot);
        Plan p = plan(spot, ((BlockItem) mc.player.getInventory().getItem(slot).getItem()).getBlock().defaultBlockState(),
                mc.player.getInventory().getItem(slot));
        if (p == null) return false;
        click(p, slot);
        scaffold.add(spot);
        cooldown = 2;
        return true;
    }

    public boolean isBuilding() { return building && isEnabled(); }

    /** Pillars and scaffolding need junk blocks: creative gets cobblestone for free. */
    private void ensureJunk() {
        for (int i = 0; i < 36; i++) if (AutoMiner.isScaffold(mc.player.getInventory().getItem(i))) return;
        if (mc.player.getAbilities().instabuild) {
            int hb = freeHotbarSlot();
            ItemStack st = new ItemStack(Items.COBBLESTONE, 64);
            mc.player.getInventory().setItem(hb, st);
            mc.gameMode.handleCreativeModeItemAdd(st, 36 + hb);
        } else if (tick % 600 == 0 && schem.sizeY > 3) {
            Chat.info("Builder: carry some junk blocks (cobblestone, dirt, netherrack) so it can pillar up to the higher parts.");
        }
    }

    private int junkSlot() {
        for (int i = 0; i < 9; i++) if (AutoMiner.isScaffold(mc.player.getInventory().getItem(i))) return i;
        for (int i = 9; i < 36; i++) {
            if (AutoMiner.isScaffold(mc.player.getInventory().getItem(i))) {
                int hb = freeHotbarSlot();
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, i, hb, ClickType.SWAP, mc.player);
                return hb;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ breaking

    private void breakBlock(BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        if (s.isAir()) { breaking = null; return; }
        int slot = PlayerControl.bestToolSlot(s, -1);
        if (slot >= 0) PlayerControl.selectSlot(slot);
        Vec3 c = Vec3.atCenterOf(pos);
        PlayerControl.lookAt(c, 40f);
        Direction face = Direction.getNearest(mc.player.getEyePosition().subtract(c));
        if (!pos.equals(breaking)) {
            if (breaking != null) mc.gameMode.stopDestroyBlock();
            mc.gameMode.startDestroyBlock(pos, face);
            breaking = pos;
        } else {
            mc.gameMode.continueDestroyBlock(pos, face);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ------------------------------------------------------------------ inventory

    private int count(Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    private boolean hasItem(Item item) {
        return mc.player.getAbilities().instabuild || count(item) > 0;
    }

    /** Get this item into the hotbar (swap it in from the inventory, or take it from creative). -1 if we have none. */
    private int takeToHotbar(Item item) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(item)) return i;
        int hb = freeHotbarSlot();
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, i, hb, ClickType.SWAP, mc.player);
                return hb;
            }
        }
        if (mc.player.getAbilities().instabuild) {
            ItemStack st = new ItemStack(item, 64);
            inv.setItem(hb, st);
            mc.gameMode.handleCreativeModeItemAdd(st, 36 + hb);
            return hb;
        }
        return -1;
    }

    /** An empty hotbar slot, else one that isn't holding a tool or weapon. */
    private int freeHotbarSlot() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
        for (int i = 8; i >= 0; i--) {
            ItemStack st = inv.getItem(i);
            if (!st.isDamageableItem() && !st.has(net.minecraft.core.component.DataComponents.FOOD)) return i;
        }
        return 8;
    }

    // ------------------------------------------------------------------ preview

    @Override
    public void onRenderLast(RenderLevelStageEvent event) {
        if (schem == null || origin == null) return;
        BlockPos o = origin;
        Vec3 cam = event.getCamera().getPosition();
        // ghost blocks: the real block models, see-through, hidden behind terrain like a placed schematic
        if (preview.get() || !building) {
            List<BlockPos> list = new ArrayList<>(ghosts);
            var dispatcher = mc.getBlockRenderer();
            WorldRender.drawBlocks(event, building ? 0.35f : 0.55f, false, source -> {
                com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
                for (BlockPos p : list) {
                    BlockState st = schem.blocks.get(p.subtract(o));
                    if (st == null || st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
                    ps.pushPose();
                    ps.translate(p.getX() - cam.x, p.getY() - cam.y, p.getZ() - cam.z);
                    dispatcher.renderSingleBlock(st, ps, source, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                            net.neoforged.neoforge.client.model.data.ModelData.EMPTY, WorldRender.ORE);
                    ps.popPose();
                }
            });
        }
        List<Work> list = new ArrayList<>(todo);
        int frame = building ? 0x90FFFFFF : 0xFF4FC3F7;
        WorldRender.draw(event, b -> {
            b.box(new AABB(o.getX(), o.getY(), o.getZ(), o.getX() + schem.sizeX, o.getY() + schem.sizeY, o.getZ() + schem.sizeZ), frame, 0);
            int n = 0;
            for (Work w : list) {
                if (!w.breakIt() || n++ > 1500) continue;
                b.block(w.pos(), 0xFFFF5252, 0x30FF5252); // wrong block in the way
            }
        });
    }

    /** Small panel while a schematic is shown: where it is, how to move it, what's missing. */
    @Override
    public void onRenderHud(net.minecraft.client.gui.GuiGraphics g, float pt) {
        if (schem == null || origin == null) return;
        var font = mc.font;
        List<String> lines = new ArrayList<>();
        lines.add((building ? "Building " : "Schematic ") + schem.name + "  " + schem.sizeX + "x" + schem.sizeY + "x" + schem.sizeZ
                + (building ? "  " + getInfo().replace(schem.name + " ", "") : ""));
        lines.add("At " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + "  ·  " + rot * 90 + "°"
                + (building ? "  ·  " + status : ""));
        if (!building) {
            lines.add(holdingStick() ? "Right-click: move here  ·  Left-click: rotate  ·  Arrows / Shift+scroll: nudge"
                    : "Hold a stick to move or rotate it");
            lines.add("Type .build start to build  ·  .build clear to remove");
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("Missing: ");
            int k = 0;
            for (Map.Entry<Item, Integer> e : missing.entrySet()) {
                if (k++ == 3) { sb.append(" +").append(missing.size() - 3).append(" more"); break; }
                if (k > 1) sb.append(", ");
                sb.append(e.getValue()).append(" ").append(e.getKey().getDescription().getString());
            }
            lines.add(sb.toString());
        }
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        w += 16;
        int h = lines.size() * 11 + 8;
        int x = (g.guiWidth() - w) / 2, y = 26;
        dev.magma.client.util.Render2D.roundedRect(g, x, y, w, h, 5, 0xD80F1117);
        dev.magma.client.util.Render2D.rect(g, x, y + 3, 2, h - 6, Theme.accent());
        for (int i = 0; i < lines.size(); i++) {
            int col = i == 0 ? Theme.TEXT : lines.get(i).startsWith("Missing") ? 0xFFFFB74D : 0xFFAEB6C4;
            g.drawString(font, lines.get(i), x + 9, y + 5 + i * 11, col, false);
        }
    }
}
