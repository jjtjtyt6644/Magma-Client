package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.util.PlayerControl;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class AutoTool extends Module {
    @Override public java.util.List<Class<? extends dev.magma.client.module.Module>> addons() { return java.util.List.of(FastBreak.class); }

    public final BoolSetting saveTools = add(new BoolSetting("Save Tools", "Never use a tool with under 8 durability left.", true));
    public final BoolSetting switchBack = add(new BoolSetting("Switch Back", "Return to your previous slot when you stop mining.", true));

    private int previous = -1;

    public AutoTool() {
        super("AutoTool", "Switches to the right tool for the block you're mining: pickaxe, axe, shovel, hoe or shears.", Category.AUTOMATION, -1);
        advanced(switchBack);
    }

    @Override
    public void onTick() {
        // AutoMiner / Goto pick their own tools (same smart logic) - don't fight them
        if (dev.magma.client.module.ModuleManager.get(AutoMiner.class).engineRunning()) return;
        boolean mining = (mc.options.keyAttack.isDown() || PlayerControl.forceAttack) && mc.hitResult instanceof BlockHitResult bhr
                && bhr.getType() == HitResult.Type.BLOCK;
        if (mining) {
            var state = mc.level.getBlockState(((BlockHitResult) mc.hitResult).getBlockPos());
            int slot = PlayerControl.bestToolSlot(state, saveTools.get());
            if (slot != mc.player.getInventory().selected) {
                if (previous == -1) previous = mc.player.getInventory().selected;
                PlayerControl.selectSlot(slot);
            }
        } else if (previous != -1) {
            if (switchBack.get()) PlayerControl.selectSlot(previous);
            previous = -1;
        }
    }
}
