package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.NumberSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoEat extends Module {
    public final NumberSetting hunger = add(new NumberSetting("Hunger", "Eat when the food bar drops to this (20 = full).", 14, 1, 19, 1));

    private int previous = -1;
    private boolean eating;

    public AutoEat() {
        super("AutoEat", "Eats food from your hotbar when you get hungry (skips bad food).", Category.AUTOMATION, -1);
    }

    @Override public boolean showInArrayList() { return true; }
    @Override public String getInfo() { return eating ? "eating" : null; }

    private static boolean goodFood(ItemStack s) {
        if (s.isEmpty() || s.get(DataComponents.FOOD) == null) return false;
        return !(s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO) || s.is(Items.PUFFERFISH)
                || s.is(Items.CHORUS_FRUIT) || s.is(Items.SUSPICIOUS_STEW) || s.is(Items.ENCHANTED_GOLDEN_APPLE));
    }

    @Override
    public void onTick() {
        int food = mc.player.getFoodData().getFoodLevel();
        if (!eating) {
            if (food > hunger.getInt() || mc.screen != null) return;
            var inv = mc.player.getInventory();
            int slot = -1;
            int bestNutrition = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack s = inv.getItem(i);
                if (!goodFood(s)) continue;
                int n = s.get(DataComponents.FOOD).nutrition();
                if (n > bestNutrition) { bestNutrition = n; slot = i; }
            }
            if (slot == -1) return;
            previous = inv.selected;
            inv.selected = slot;
            eating = true;
        }
        if (eating) {
            ItemStack held = mc.player.getMainHandItem();
            if (food >= 20 || !goodFood(held) || mc.screen != null) {
                stop();
                return;
            }
            mc.options.keyUse.setDown(true);
            if (food > hunger.getInt() + 4) stop();
        }
    }

    private void stop() {
        mc.options.keyUse.setDown(false);
        if (previous != -1) mc.player.getInventory().selected = previous;
        previous = -1;
        eating = false;
    }

    @Override protected void onDisable() { if (eating) stop(); }
}
