package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.NumberSetting;

/** Creative-style flying in any game mode. Space/Shift go up/down, sprint doubles the speed. */
public class Fly extends Module {
    private static final float VANILLA = 0.05f;
    public final NumberSetting speed = add(new NumberSetting("Speed", "Flying speed (1 = vanilla creative flight).", 2, 0.5, 10, 0.5));

    public Fly() {
        super("Fly", "Fly like in creative, at the speed you pick. No fall damage when you land.", Category.MOVEMENT, -1);
    }

    @Override
    public boolean persistEnabled() { return false; }

    @Override
    public String getInfo() { return speed.get() + "x"; }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        var ab = mc.player.getAbilities();
        ab.mayfly = true;
        ab.flying = true;
        ab.setFlyingSpeed((float) (VANILLA * speed.get()));
        mc.player.fallDistance = 0;
    }

    @Override
    protected void onDisable() {
        NoFall.protectUntil = System.currentTimeMillis() + 6000; // you'll fall after switching it off: land softly
        if (mc.player == null) return;
        var ab = mc.player.getAbilities();
        ab.setFlyingSpeed(VANILLA);
        boolean canFly = mc.gameMode != null && (mc.gameMode.getPlayerMode().isCreative()
                || mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SPECTATOR);
        if (!canFly) {
            ab.mayfly = false;
            ab.flying = false;
        }
    }
}
