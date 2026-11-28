package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.NumberSetting;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public class FastBreak extends Module {
    public final NumberSetting speed = add(new NumberSetting("Speed", "Mining speed multiplier. Above ~1.4x vanilla servers may reject breaks.", 1.3, 1.0, 3.0, 0.05));
    public final BoolSetting noDelay = add(new BoolSetting("No Delay", "Remove the 5-tick pause between blocks.", true));

    public FastBreak() {
        super("FastBreak", "Mine blocks faster and with no cooldown between them.", Category.AUTOMATION, -1);
        advanced(noDelay);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.BreakSpeed.class, this::onBreakSpeed);
    }

    @Override public String getInfo() { return speed.display() + "x"; }

    private void onBreakSpeed(PlayerEvent.BreakSpeed e) {
        if (!isEnabled() || !e.getEntity().level().isClientSide() || e.getEntity() != mc.player) return;
        e.setNewSpeed(e.getNewSpeed() * speed.getFloat());
    }
}
