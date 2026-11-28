package dev.magma.client.util;

import dev.magma.client.config.ConfigManager;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.modules.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Pop-up help:
 * - the first time you switch a feature on, a short "how to use it" card (once per feature, remembered);
 * - when a feature is on but seems stuck for a while, a card saying what's wrong and what to do.
 * Both can be turned off with Client > Tips.
 */
public final class Hints {
    private Hints() {}

    /** First-time "how to use" text for each feature. */
    private static final Map<String, String> HOW = new HashMap<>();
    static {
        HOW.put("AutoMiner", "Pick ores in its settings, then it finds them (like X-Ray), digs safely to them and mines them. Move yourself and it pauses. Press K to start/stop.");
        HOW.put("Goto", "Type X Y Z in its settings (or .goto x y z in chat), then switch it on. It walks, bridges and digs there safely. 'Use' buttons fill in your Base or last death.");
        HOW.put("Base", "Press Set here where your base is. Switch Base on any time to walk home safely.");
        HOW.put("AutoFind", "Pick a Structure (Village, Fortress...), switch it on: it scans what's loaded, explores outward if needed and walks you there.");
        HOW.put("Builder", "Switching it on opens Builder Studio in your browser - make a build there and press Send to Minecraft. The step-by-step guide is in Builder's settings.");
        HOW.put("VeinMiner", "Mine one ore or log and the rest of the vein breaks too. Works with any tool that can mine it.");
        HOW.put("Xray", "Shows ores through walls. Mode: ESP outlines them, Classic hides everything else. Pick which ores and the range in settings. Press X to toggle.");
        HOW.put("X-Ray", HOW.get("Xray"));
        HOW.put("PlayerESP", "Draws boxes around other players through walls, coloured by distance. Useful on servers.");
        HOW.put("Fullbright", "Lets you see in the dark (like night vision, only on your screen).");
        HOW.put("LavaGuard", "Warns you about lava nearby and stops you breaking a block that would let lava out. Leave it on - it's your safety net.");
        HOW.put("SafeWalk", "Stops you walking off edges, into lava or fire - automatically, no key to hold.");
        HOW.put("NoFall", "Takes away fall damage.");
        HOW.put("AutoEat", "Eats food from your hotbar or inventory when you get hungry or hurt.");
        HOW.put("AutoTool", "Switches to the best tool for the block you start mining.");
        HOW.put("AutoWalk", "Keeps walking forward for you. Press a movement key to take over.");
        HOW.put("FastBreak", "Mines blocks faster. Turn the speed down in settings if a server complains.");
        HOW.put("FastPlace", "Places blocks faster when you hold right-click.");
        HOW.put("Scaffold", "Places blocks under your feet as you walk, so you can bridge over gaps. Keep blocks in your hotbar.");
        HOW.put("Fly", "Fly like in creative. Space to go up, sneak to go down.");
        HOW.put("Freecam", "Your camera floats free while your player stays put. Switch it off to snap back.");
        HOW.put("InvCleaner", "Throws away junk items you pick in its settings so your inventory doesn't fill up.");
        HOW.put("AutoCombat", "Fights the nearest enemy: hits when your attack is charged, then backs out of reach and circles. Pick players and/or monsters in settings.");
        HOW.put("VillagerRoller", "Stand next to a villager with its job block (lectern, composter...) in your inventory. Pick the books or items you want - it re-places the block until the trade shows up.");
        HOW.put("HUD", "The on-screen overlay: watermark, active features, coordinates. Pick what shows in its settings.");
    }

    private static final Map<String, Boolean> wasOn = new HashMap<>();
    private static final Map<String, Integer> stuckFor = new HashMap<>();
    private static final Map<String, Long> lastHint = new HashMap<>();
    private static final Map<String, String> lastStatus = new HashMap<>();
    private static final Map<String, Integer> sameStatusFor = new HashMap<>();
    private static final java.util.Set<String> shownHere = new java.util.HashSet<>();
    private static Object lastLevel;
    private static BlockPos lastPos;
    private static int stillFor;
    private static int tick, inWorld;

    private static boolean on() {
        return ModuleManager.get(ClickGui.class).tips.get();
    }

    /** Called every client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!ModuleManager.ready() || mc.player == null || mc.level == null) return;
        if (mc.level != lastLevel) { lastLevel = mc.level; shownHere.clear(); } // new world / dimension: hints may help again
        ClickGui cg = ModuleManager.get(ClickGui.class);
        // the very first time you're in a world with Magma: open the guide so you know where everything is
        if (!cg.guideShown.get() && ++inWorld > 60) {
            cg.guideShown.set(true);
            ConfigManager.save();
            GuideServer.open(true);
            Notify.show("Welcome to Magma!", "The Magma Guide just opened in your browser - every feature explained. Press Right Shift for the menu; the Guide item there opens it again.", Theme.accent());
        }
        // first time each feature is switched on: how to use it
        for (Module m : ModuleManager.all()) {
            boolean now = m.isEnabled();
            Boolean before = wasOn.put(m.getName(), now);
            if (before == null || before || !now || !on()) continue;
            String how = HOW.get(m.getName());
            String seen = "," + cg.seenGuides.get() + ",";
            if (how == null || seen.contains("," + m.getName() + ",")) continue;
            cg.seenGuides.set(cg.seenGuides.get().isEmpty() ? m.getName() : cg.seenGuides.get() + "," + m.getName());
            ConfigManager.save();
            Notify.show("How to use " + m.getName(), how, Theme.accent());
        }
        if (++tick % 20 != 0) return; // stuck checks once a second
        BlockPos p = mc.player.blockPosition();
        stillFor = p.equals(lastPos) ? stillFor + 1 : 0;
        lastPos = p;
        if (!on()) return;

        AutoMiner am = ModuleManager.get(AutoMiner.class);
        Builder b = ModuleManager.get(Builder.class);
        Xray xr = ModuleManager.get(Xray.class);

        // --- AutoMiner
        check("AutoMiner", am.isEnabled(), 10, () -> am.getStatus().startsWith("Paused (you're moving)")
                ? "It waits while you move yourself. Let go of the movement keys and it carries on." : null);
        check("AutoMiner", am.isEnabled(), 10, () -> am.getStatus().startsWith("Low HP")
                ? "Paused because your health is low. Eat something (AutoEat does it for you) and it carries on." : null);
        check("AutoMiner", am.isEnabled(), 45, () -> am.getStatus().startsWith("Exploring")
                ? "None of your ores nearby. It sees about 32 blocks around. Pick more ores in its settings, or go deeper - diamonds are most common around Y -59." : null);
        check("AutoMiner", am.isEnabled(), 20, () -> am.getStatus().startsWith("Route locked") || am.getStatus().startsWith("Can't find")
                ? "Can't find a safe way right now. Move a few blocks, or turn on Dig Through in Goto settings." : null);
        check("AutoMiner", am.isEnabled(), 40, () -> stillFor >= 40 && same("AutoMiner", am.getStatus(), 40)
                ? "Looks stuck (\"" + am.getStatus() + "\"). Move a few blocks away, or press K twice to restart it." : null);

        // --- Goto / trips
        BlockPos trip = am.getGotoTarget();
        check("Goto", trip != null && !am.isEnabled(), 30, () -> stillFor >= 30
                ? "Not getting closer to " + trip.toShortString() + ". Turn on Dig Through in Goto settings, or walk around the obstacle and it picks up from there." : null);

        // --- Base
        Base base = ModuleManager.get(Base.class);
        check("Base", base.isEnabled(), 2, () -> base.get() == null
                ? "No base saved yet. Stand at your base and press Set here in Base settings, then switch it on to walk home." : null);

        // --- AutoFind
        AutoFind af = ModuleManager.get(AutoFind.class);
        check("AutoFind", af.isEnabled(), 3, () -> {
            String s = af.structure.get();
            boolean netherOnly = s.equals("Fortress") || s.equals("Bastion");
            boolean overworldOnly = s.equals("Village") || s.equals("Mineshaft") || s.equals("Stronghold") || s.equals("Ocean Monument")
                    || s.equals("Ancient City") || s.equals("Trial Chambers");
            if (netherOnly && mc.level.dimension() != Level.NETHER) return s + " only exists in the Nether. Go through a portal first (AutoFind can find a Nether Portal for you).";
            if (overworldOnly && mc.level.dimension() != Level.OVERWORLD) return s + " only exists in the Overworld. Go back through a portal first.";
            return null;
        });
        check("AutoFind", af.isEnabled(), 120, () -> af.getInfo() != null && af.getInfo().startsWith("Exploring")
                ? "Still exploring - structures can be a few hundred blocks away. Keep it running, or change Explore Direction in its settings." : null);

        // --- X-Ray
        check("X-Ray", xr.isEnabled(), 10, () -> xr.counts().isEmpty()
                ? "No ores within range. Raise Range in its settings, go underground, or set Ores to All Ores." : null);

        // --- Builder
        check("Builder", b.isEnabled() && b.schematicName() != null && !b.isBuilding() && !b.waitingForMaterials(), 30,
                () -> "Ready when you are: press Start in Builder settings or type .build start. To move it first, hold a stick and right-click where it should go.");
        check("Builder", b.isBuilding(), 12, () -> b.status().startsWith("Can't get near")
                ? "Some blocks are buried in the ground. Builder clears them from the outside in; if it stays stuck, raise the build (hold a stick + Page Up)." : null);
        check("Builder", b.isBuilding(), 45, () -> same("Builder", b.blocksLeft() + "", 45)
                ? "No progress for a while. Fly or walk closer to the build, or type .build stop then .build start." : null);

        // --- AutoCombat
        AutoCombat ac = ModuleManager.get(AutoCombat.class);
        check("AutoCombat", ac.isEnabled(), 20, () -> ac.getInfo() == null
                ? "No enemy in range. It only fights what it can see within Find Range - check Players / Monsters in its settings." : null);

        // --- PlayerESP
        Module esp = ModuleManager.get(PlayerEsp.class);
        check("PlayerESP", esp.isEnabled(), 15, () -> mc.level.players().size() <= 1
                ? "No other players here. PlayerESP shows people on multiplayer servers." : null);

        // --- VillagerRoller
        VillagerRoller vr = ModuleManager.get(VillagerRoller.class);
        check("VillagerRoller", vr.isEnabled(), 40, () -> same("VillagerRoller", vr.getStatus(), 40)
                ? "Seems stuck (\"" + vr.getStatus() + "\"). Stand within 3 blocks of the villager, keep its job block in your inventory, and make sure the villager has no trades locked in yet." : null);
    }

    /** True if this value hasn't changed for `secs` checks. */
    private static boolean same(String key, String value, int secs) {
        String prev = lastStatus.put(key, value);
        int n = value.equals(prev) ? sameStatusFor.getOrDefault(key, 0) + 1 : 0;
        sameStatusFor.put(key, n);
        return n >= secs;
    }

    /** If `hint` gives text for `secs` seconds in a row while `active`, pop it up (at most once every 2 minutes per feature). */
    private static void check(String module, boolean active, int secs, Supplier<String> hint) {
        String key = module + "#" + secs;
        String text = active ? hint.get() : null;
        if (text == null) { stuckFor.remove(key); return; }
        int n = stuckFor.merge(key, 1, Integer::sum);
        if (n < secs) return;
        long now = System.currentTimeMillis();
        if (now - lastHint.getOrDefault(module, 0L) < 120_000) return;
        if (!shownHere.add(key)) return; // each hint once per world visit - no nagging
        lastHint.put(module, now);
        stuckFor.remove(key);
        Notify.show(module + ": need a hand?", text, Theme.accent());
    }
}
