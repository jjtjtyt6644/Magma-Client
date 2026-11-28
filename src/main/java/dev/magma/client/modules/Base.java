package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.ButtonSetting;
import dev.magma.client.setting.ModeSetting;
import dev.magma.client.setting.TextSetting;
import dev.magma.client.util.Chat;
import dev.magma.client.util.Notify;
import dev.magma.client.util.Theme;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Your base: one saved location used everywhere (Goto's "Base" button, ".goto base", AutoMiner's
 * "Return To Base When Full"). Switch it on to walk home.
 */
public class Base extends Module {
    private static final String NUM = "0123456789-";
    public final TextSetting x = add(new TextSetting("X", "Base X.", "", NUM, 9));
    public final TextSetting y = add(new TextSetting("Y", "Base Y.", "", NUM, 9));
    public final TextSetting z = add(new TextSetting("Z", "Base Z.", "", NUM, 9));
    public final ModeSetting dimension = add(new ModeSetting("Dimension", "Which world the base is in (set automatically by Set Here).", "Overworld", "Overworld", "Nether", "End"));
    public final ButtonSetting setHere = add(new ButtonSetting("Base Location", "Save where you're standing right now as your base.", "Set here", this::setHere));

    public Base() {
        super("Base", "Your saved base location. Turn on to walk home safely; AutoMiner can return here when full.", Category.WORLD, -1);
    }

    @Override public boolean persistEnabled() { return false; }

    @Override
    public String getInfo() {
        BlockPos b = get();
        return b == null ? "not set" : b.getX() + " " + b.getY() + " " + b.getZ();
    }

    private static String dimName(net.minecraft.resources.ResourceKey<Level> d) {
        return d == Level.NETHER ? "Nether" : d == Level.END ? "End" : "Overworld";
    }

    public void setHere() {
        if (mc.player == null) return;
        BlockPos p = mc.player.blockPosition();
        x.set(String.valueOf(p.getX()));
        y.set(String.valueOf(p.getY()));
        z.set(String.valueOf(p.getZ()));
        dimension.set(dimName(mc.level.dimension()));
        dev.magma.client.config.ConfigManager.save();
        Chat.info("Base set to " + p.toShortString() + " (" + dimension.get() + ").");
        Notify.show("Base saved", "Base is " + p.toShortString() + ". Type .base (or turn on Base) to walk home from anywhere.", Theme.OK);
    }

    /** The base position as typed, or null if not set / invalid. */
    public BlockPos get() {
        try {
            if (x.get().isBlank() || y.get().isBlank() || z.get().isBlank()) return null;
            return new BlockPos(Integer.parseInt(x.get().trim()), Integer.parseInt(y.get().trim()), Integer.parseInt(z.get().trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Where to walk to reach the base from the current dimension. Overworld <-> Nether are converted (x8 / ÷8)
     * so you arrive at the matching spot (use a portal there). Returns null if not set or unreachable (End).
     */
    public BlockPos targetFromHere() {
        BlockPos b = get();
        if (b == null || mc.level == null) return null;
        String here = dimName(mc.level.dimension());
        if (here.equals(dimension.get())) return b;
        if (here.equals("Nether") && dimension.is("Overworld")) return new BlockPos(Math.floorDiv(b.getX(), 8), b.getY(), Math.floorDiv(b.getZ(), 8));
        if (here.equals("Overworld") && dimension.is("Nether")) return new BlockPos(b.getX() * 8, b.getY(), b.getZ() * 8);
        return null;
    }

    /** Message to show when the base is in another dimension. */
    public String dimensionNote() {
        if (mc.level == null) return "";
        String here = dimName(mc.level.dimension());
        return here.equals(dimension.get()) ? "" : " (base is in the " + dimension.get() + " - heading to the matching spot here, take a portal there)";
    }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        BlockPos t = targetFromHere();
        if (t == null) {
            Chat.info(get() == null ? "Base: set your base first (Set here, or type X/Y/Z)." : "Base: can't reach a " + dimension.get() + " base from here.");
            setEnabled(false);
            return;
        }
        Chat.info("Heading home to " + t.toShortString() + dimensionNote() + ".");
        ModuleManager.get(AutoMiner.class).startGoto(t, true, false, false);
        ModuleManager.get(AutoMiner.class).tripLabel = "Going home";
    }

    @Override
    public void onTick() {
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        // drive the movement engine like Goto does (unless AutoMiner is running it)
        am.driveIfIdle();
        if (am.getGotoTarget() == null) setEnabled(false); // arrived or gave up
    }

    @Override
    protected void onDisable() {
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (am.getGotoTarget() != null && !am.isEnabled()) am.cancelGoto();
    }

    @Override
    public void onRenderWorld(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (!am.isEnabled() && !ModuleManager.get(Goto.class).isEnabled() && am.getGotoTarget() != null) am.onRenderWorld(event);
        Goto.drawBeam(event, am.getGotoTarget());
    }
}
