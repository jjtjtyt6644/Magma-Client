package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.ButtonSetting;
import dev.magma.client.setting.TextSetting;
import dev.magma.client.util.Chat;
import net.minecraft.core.BlockPos;

/**
 * Travel to X Y Z automatically with the miner's safety: lava avoidance, fall protection, block placing,
 * eating and auto-defend. Also: chat ".goto x y z" / ".goto stop".
 */
public class Goto extends Module {
    @Override public java.util.List<Class<? extends dev.magma.client.module.Module>> addons() { return java.util.List.of(SafeWalk.class, AutoEat.class, Fullbright.class); }

    @Override public boolean persistEnabled() { return false; }

    private static final String NUM = "0123456789-~";
    public final TextSetting x = add(new TextSetting("X", "Target X (~ = your current X, ~10 = 10 more).", "~", NUM, 9));
    public final TextSetting y = add(new TextSetting("Y", "Target Y (~ = same height).", "~", NUM, 9));
    public final TextSetting z = add(new TextSetting("Z", "Target Z.", "~", NUM, 9));
    public final dev.magma.client.setting.ModeSetting coordsFor = add(new dev.magma.client.setting.ModeSetting("Coordinates Are For",
            "Typed Overworld coords while in the Nether (or the other way round)? It converts them for you (x8 / ÷8).", "This World", "This World", "Overworld", "Nether"));
    public final ButtonSetting base = add(new ButtonSetting("Base", "Fill X/Y/Z with your Base (set in the Base module).", "Use", this::useBase));
    public final ButtonSetting lastDeath = add(new ButtonSetting("Last Death", "Fill X/Y/Z with where you last died (in this dimension).", "Use", this::useLastDeath));
    public final ButtonSetting here = add(new ButtonSetting("Current Position", "Fill X/Y/Z with where you're standing.", "Use", this::useHere));
    public final BoolSetting dig = add(new BoolSetting("Dig Through", "Allow tunnelling (off = only walk, climb and bridge).", true));
    public final BoolSetting exactY = add(new BoolSetting("Exact Height", "Must reach the exact Y. Off = arrive at that X/Z on the nearest safe ground (recommended).", false));
    public final BoolSetting mineOnWay = add(new BoolSetting("Mine Ores On Way", "Stop for wanted ores (AutoMiner's ore list) along the route.", false));

    public Goto() {
        super("Goto", "Walk to coordinates safely (lava + fall protection, bridging, pillaring). Chat: .goto x y z", Category.WORLD, -1);
        advanced(exactY, mineOnWay, coordsFor);
    }

    @Override public String getInfo() {
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        BlockPos t = am.getGotoTarget();
        if (t == null || mc.player == null) return null;
        return (int) Math.sqrt(mc.player.blockPosition().distSqr(t)) + "m · " + am.getStatus().replace("Goto: ", "");
    }

    /** Last place the player died (set by ClientEvents). */
    public static BlockPos deathPos;
    public static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> deathDim;

    private void useBase() {
        BlockPos t = ModuleManager.get(Base.class).targetFromHere();
        if (t == null) {
            dev.magma.client.util.Notify.show("No base set yet", "Stand at your base and type .base set (or use Set here in the Base module).", dev.magma.client.util.Theme.WARN);
            return;
        }
        x.set(String.valueOf(t.getX()));
        y.set(String.valueOf(t.getY()));
        z.set(String.valueOf(t.getZ()));
        coordsFor.set("This World");
    }

    private void useLastDeath() {
        if (deathPos == null || mc.level == null || mc.level.dimension() != deathDim) {
            dev.magma.client.util.Notify.show("No death point here", "Your last death was in another dimension - go there first, then use Last Death.", dev.magma.client.util.Theme.WARN);
            return;
        }
        x.set(String.valueOf(deathPos.getX()));
        y.set(String.valueOf(deathPos.getY()));
        z.set(String.valueOf(deathPos.getZ()));
        coordsFor.set("This World");
    }

    private void useHere() {
        if (mc.player == null) return;
        BlockPos p = mc.player.blockPosition();
        x.set(String.valueOf(p.getX()));
        y.set(String.valueOf(p.getY()));
        z.set(String.valueOf(p.getZ()));
    }

    /** Parses "123", "-40", "~", "~-5". */
    static Integer parse(String s, int current) {
        s = s.trim();
        try {
            if (s.startsWith("~")) return current + (s.length() == 1 ? 0 : Integer.parseInt(s.substring(1)));
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        BlockPos p = mc.player.blockPosition();
        Integer tx = parse(x.get(), p.getX()), ty = parse(y.get(), p.getY()), tz = parse(z.get(), p.getZ());
        if (tx == null || ty == null || tz == null) {
            Chat.info("Goto: enter valid numbers for X, Y and Z first.");
            setEnabled(false);
            return;
        }
        // convert Overworld <-> Nether coordinates if asked (Nether = Overworld / 8)
        boolean inNether = mc.level.dimension() == net.minecraft.world.level.Level.NETHER;
        if (coordsFor.is("Overworld") && inNether) { tx = Math.floorDiv(tx, 8); tz = Math.floorDiv(tz, 8); }
        else if (coordsFor.is("Nether") && !inNether) { tx = tx * 8; tz = tz * 8; }
        BlockPos target = new BlockPos(tx, ty, tz);
        double dist = Math.sqrt(p.distSqr(target));
        Chat.info("Goto: " + target.toShortString() + " (" + (int) dist + " blocks away). Type .goto stop to cancel.");
        ModuleManager.get(AutoMiner.class).startGoto(target, dig.get(), mineOnWay.get(), exactY.get());
    }

    /** Goto drives the movement engine itself when AutoMiner isn't mining. */
    @Override
    public void onTick() {
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        am.driveIfIdle();
    }

    @Override
    public void onRenderWorld(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (!am.isEnabled() && am.getGotoTarget() != null) am.onRenderWorld(event);
        drawBeam(event, am.getGotoTarget());
    }

    /** A tall beam marking where you're heading. */
    static void drawBeam(net.neoforged.neoforge.client.event.RenderLevelStageEvent event, BlockPos t) {
        if (t == null) return;
        dev.magma.client.util.WorldRender.draw(event, b -> {
            int c = dev.magma.client.util.Theme.accent();
            b.box(new net.minecraft.world.phys.AABB(t.getX() + 0.3, t.getY() - 1, t.getZ() + 0.3, t.getX() + 0.7, t.getY() + 60, t.getZ() + 0.7),
                    dev.magma.client.util.ColorUtil.withAlpha(c, 170), dev.magma.client.util.ColorUtil.withAlpha(c, 45));
        });
    }

    @Override
    protected void onDisable() {
        ModuleManager.get(AutoMiner.class).cancelGoto();
    }

    /** Chat command handler. Returns true if the message was a Magma command. */
    public static boolean handleCommand(String msg) {
        if (!msg.startsWith(".goto") && !msg.startsWith(".magma") && !msg.startsWith(".base")) return false;
        Goto g = ModuleManager.get(Goto.class);
        String[] a = msg.trim().split("\s+");
        if (a[0].equals(".base")) {
            Base b = ModuleManager.get(Base.class);
            if (a.length > 1 && a[1].equalsIgnoreCase("set")) b.setHere();
            else { if (b.isEnabled()) b.setEnabled(false); b.setEnabled(true); }
            return true;
        }
        if (a.length > 1 && a[1].equalsIgnoreCase("base")) {
            g.useBase();
            if (g.x.get().isEmpty()) return true;
            if (g.isEnabled()) g.setEnabled(false);
            g.setEnabled(true);
            return true;
        }
        if (a[0].equals(".magma") || a.length == 1) {
            Chat.info("Commands: .goto x y z | .goto x z | .goto base | .goto stop | .base | .base set");
            return true;
        }
        if (a[1].equalsIgnoreCase("stop") || a[1].equalsIgnoreCase("cancel")) {
            g.setEnabled(false);
            return true;
        }
        if (a.length == 3) { // x z
            g.x.set(a[1]); g.y.set("~"); g.z.set(a[2]);
        } else if (a.length >= 4) {
            g.x.set(a[1]); g.y.set(a[2]); g.z.set(a[3]);
        } else {
            Chat.info("Usage: .goto x y z  (or .goto x z, .goto stop)");
            return true;
        }
        if (g.isEnabled()) g.setEnabled(false);
        g.setEnabled(true);
        return true;
    }
}
