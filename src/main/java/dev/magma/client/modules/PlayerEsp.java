package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.WorldRender;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** See other players through walls: a box around each, coloured by distance, with optional lines to them. */
public class PlayerEsp extends Module {
    public final NumberSetting range = add(new NumberSetting("Range", "How far away to show players.", 128, 16, 512, 16));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw a line from you to each player.", true));
    public final BoolSetting fill = add(new BoolSetting("Fill", "Tint the inside of the boxes.", true));

    public PlayerEsp() {
        super("PlayerESP", "See other players through walls (boxes and lines, red when close).", Category.RENDER, -1);
    }

    @Override
    public String getInfo() {
        if (mc.level == null) return null;
        int n = 0;
        for (Player p : mc.level.players()) if (p != mc.player && p.distanceTo(mc.player) <= range.get()) n++;
        return n == 0 ? null : String.valueOf(n);
    }

    @Override
    public void onRenderLast(RenderLevelStageEvent event) {
        if (mc.level == null || mc.player == null) return;
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 cam = event.getCamera().getPosition();
        Vec3 look = Vec3.directionFromRotation(event.getCamera().getXRot(), event.getCamera().getYRot());
        Vec3 from = cam.add(look.scale(0.6));
        double r = range.get();
        WorldRender.draw(event, b -> {
            for (Player p : mc.level.players()) {
                if (p == mc.player || p.isSpectator()) continue;
                double d = p.distanceTo(mc.player);
                if (d > r) continue;
                // red when close, orange mid-range, green far away
                int c = d < 16 ? 0xFFFF5252 : d < 48 ? 0xFFFFB74D : 0xFF4ADE80;
                Vec3 at = p.getPosition(pt);
                var bb = p.getBoundingBox().move(at.subtract(p.position()));
                b.box(bb, c, fill.get() ? ColorUtil.withAlpha(c, 40) : 0);
                if (tracers.get()) b.line(from.x, from.y, from.z, at.x, at.y + p.getBbHeight() / 2, at.z, ColorUtil.withAlpha(c, 170));
            }
        });
    }
}
