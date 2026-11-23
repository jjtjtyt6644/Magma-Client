package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

public class Fullbright extends Module {
    private static final int DURATION = 1_000_000;

    public Fullbright() {
        super("Fullbright", "See in the dark (client-side night vision). Auto-used by Classic X-Ray.", Category.RENDER, -1);
    }

    public static void apply() {
        if (mc.player == null) return;
        MobEffectInstance cur = mc.player.getEffect(MobEffects.NIGHT_VISION);
        if (cur == null || cur.getDuration() < 400) {
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, DURATION, 0, false, false, false));
        }
    }

    public static void remove() {
        if (mc.player == null) return;
        MobEffectInstance cur = mc.player.getEffect(MobEffects.NIGHT_VISION);
        if (cur != null && cur.getDuration() > 20_000) mc.player.removeEffect(MobEffects.NIGHT_VISION);
    }

    @Override public void onTick() { apply(); }

    @Override
    protected void onDisable() {
        Xray x = dev.magma.client.module.ModuleManager.get(Xray.class);
        if (!(x.isEnabled() && x.isClassic())) remove();
    }
}
