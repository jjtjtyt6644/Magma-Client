package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.MultiSetting;
import dev.magma.client.setting.NumberSetting;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps junk under control: at most N stacks of each junk type you tick, extra stacks get thrown out,
 * and when the inventory is full it throws junk (keeping 1 stack for bridging) so there's room for ores.
 * AutoMiner also won't pick up junk you're already at the limit for.
 */
public class InvCleaner extends Module {
    private static final Map<Item, String> GROUP = new HashMap<>();

    private static void g(String group, Item... items) {
        for (Item i : items) GROUP.put(i, group);
    }

    static {
        g("Netherrack", Items.NETHERRACK);
        g("Cobblestone & Stone", Items.COBBLESTONE, Items.MOSSY_COBBLESTONE, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.CALCITE);
        g("Deepslate & Tuff", Items.COBBLED_DEEPSLATE, Items.DEEPSLATE, Items.TUFF);
        g("Blackstone & Basalt", Items.BLACKSTONE, Items.BASALT, Items.SMOOTH_BASALT, Items.MAGMA_BLOCK);
        g("Dirt & Grass", Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.PODZOL, Items.MUD, Items.MYCELIUM);
        g("Gravel & Flint", Items.GRAVEL, Items.FLINT);
        g("Sand & Sandstone", Items.SAND, Items.RED_SAND, Items.SANDSTONE, Items.RED_SANDSTONE);
        g("Soul Sand & Soil", Items.SOUL_SAND, Items.SOUL_SOIL);
        g("Nether Bricks", Items.NETHER_BRICK, Items.NETHER_BRICKS);
        g("Mob Junk", Items.ROTTEN_FLESH, Items.BONE, Items.STRING, Items.SPIDER_EYE, Items.POISONOUS_POTATO);
    }

    public final MultiSetting junk = add(new MultiSetting("Junk Items", "Which items count as junk to limit.",
            List.of("Netherrack", "Cobblestone & Stone", "Deepslate & Tuff", "Blackstone & Basalt", "Dirt & Grass",
                    "Gravel & Flint", "Sand & Sandstone", "Soul Sand & Soil", "Nether Bricks", "Mob Junk"),
            "Netherrack", "Cobblestone & Stone", "Deepslate & Tuff", "Blackstone & Basalt", "Dirt & Grass", "Gravel & Flint", "Soul Sand & Soil"));
    public final NumberSetting maxStacks = add(new NumberSetting("Max Stacks Each", "Keep at most this many stacks of each junk type; extra stacks are thrown out.", 2, 1, 27, 1));
    public final BoolSetting makeRoom = add(new BoolSetting("Make Room For Ores", "When the inventory is full, throw junk (always keeps 1 stack for bridging).", true));
    public final BoolSetting onlyMining = add(new BoolSetting("Only While Mining", "Only clean while AutoMiner / Goto / AutoFind is running.", false));

    private int cooldown;

    public InvCleaner() {
        super("InvCleaner", "Limits junk stacks (netherrack, cobble, gravel...) and throws extras out so there's room for ores.", Category.AUTOMATION, -1);
        advanced(makeRoom, onlyMining);
    }

    private String groupOf(ItemStack st) {
        if (st.isEmpty()) return null;
        String g = GROUP.get(st.getItem());
        return g != null && junk.has(g) ? g : null;
    }

    private Map<String, Integer> stackCounts() {
        Map<String, Integer> m = new HashMap<>();
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            String g = groupOf(inv.getItem(i));
            if (g != null) m.merge(g, 1, Integer::sum);
        }
        return m;
    }

    /** AutoMiner asks this before walking to an item: is it junk we're already at the limit for? */
    public boolean atLimit(ItemStack st) {
        if (!isEnabled() || mc.player == null) return false;
        String g = groupOf(st);
        if (g == null) return false;
        int stacks = stackCounts().getOrDefault(g, 0);
        if (stacks < maxStacks.getInt()) return false;
        // at the stack limit: only worth picking up if it tops up a partial stack
        return mc.player.getInventory().getSlotWithRemainingSpace(st) == -1;
    }

    /** Could we free a slot right now by throwing junk? */
    public boolean canMakeRoom() {
        if (!isEnabled() || !makeRoom.get()) return false;
        for (int c : stackCounts().values()) if (c >= 2) return true;
        return false;
    }

    @Override
    public void onTick() {
        if (onlyMining.get() && !ModuleManager.get(AutoMiner.class).engineRunning()) return;
        if (mc.screen != null && !(mc.screen instanceof InventoryScreen)) return; // never fiddle while a chest/furnace is open
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        if (cooldown > 0) { cooldown--; return; }

        Map<String, Integer> counts = stackCounts();
        // 1) over the per-type limit
        for (var e : counts.entrySet()) {
            if (e.getValue() > maxStacks.getInt() && throwSmallest(e.getKey())) return;
        }
        // 2) inventory full: throw junk down to 1 stack per type so ores fit
        if (makeRoom.get() && mc.player.getInventory().getFreeSlot() == -1) {
            String worst = null;
            int most = 1;
            for (var e : counts.entrySet()) if (e.getValue() > most) { most = e.getValue(); worst = e.getKey(); }
            if (worst != null) throwSmallest(worst);
        }
    }

    /** Throws the smallest stack of that junk group (main inventory before hotbar). */
    private boolean throwSmallest(String group) {
        Inventory inv = mc.player.getInventory();
        int slot = -1, smallest = Integer.MAX_VALUE;
        for (int pass = 0; pass < 2 && slot == -1; pass++) {
            for (int i = pass == 0 ? 9 : 0; i < (pass == 0 ? 36 : 9); i++) {
                ItemStack st = inv.getItem(i);
                if (!group.equals(groupOf(st))) continue;
                if (st.getCount() < smallest) { smallest = st.getCount(); slot = i; }
            }
        }
        if (slot == -1) return false;
        int menuSlot = slot < 9 ? 36 + slot : slot; // player inventory menu layout
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, menuSlot, 1, ClickType.THROW, mc.player);
        cooldown = 4;
        return true;
    }
}
