package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;

public class AutoWalk extends Module {
    @Override public boolean persistEnabled() { return false; }

    public final BoolSetting sprint = add(new BoolSetting("Sprint", "Sprint while walking.", false));

    public AutoWalk() {
        super("AutoWalk", "Holds the forward key for you.", Category.AUTOMATION, -1);
    }

    @Override
    public void onTick() {
        if (mc.screen != null) return;
        mc.options.keyUp.setDown(true);
        if (sprint.get()) mc.player.setSprinting(true);
    }

    @Override protected void onDisable() { mc.options.keyUp.setDown(false); }
}
