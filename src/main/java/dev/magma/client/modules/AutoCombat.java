package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.PlayerControl;
import dev.magma.client.util.WorldRender;
import net.minecraft.client.player.Input;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Hit-and-run fighting: steps in when your attack is fully charged, hits at the very edge of reach, then backs off
 * out of the enemy's reach while it recharges - circling around them the whole time so they struggle to hit back.
 */
public class AutoCombat extends Module {
    public final BoolSetting players = add(new BoolSetting("Players", "Fight other players.", true));
    public final BoolSetting mobs = add(new BoolSetting("Monsters", "Fight hostile mobs.", true));
    public final NumberSetting range = add(new NumberSetting("Find Range", "How far away to pick a target.", 8, 3, 16, 0.5));
    public final NumberSetting hitDistance = add(new NumberSetting("Hit Distance", "How close to step in to hit (vanilla reach is 3).", 2.9, 2.0, 3.0, 0.1));
    public final NumberSetting backOff = add(new NumberSetting("Back Off To", "Distance to keep while your attack recharges.", 4.2, 3.2, 6.0, 0.1));
    public final BoolSetting strafe = add(new BoolSetting("Circle", "Strafe around the target so it's harder to hit you.", true));
    public final BoolSetting move = add(new BoolSetting("Move For Me", "Walk in and out by itself (off = only aim and hit).", true));

    private LivingEntity target;
    private int strafeDir = 1, strafeTicks;

    public AutoCombat() {
        super("AutoCombat", "Fights for you: hits when your attack is fully charged, then backs out of reach and circles so they can't hit back.", Category.COMBAT, -1);
        NeoForge.EVENT_BUS.addListener(MovementInputUpdateEvent.class, this::onInput);
        advanced(range, backOff, move);
    }

    @Override public boolean persistEnabled() { return false; }

    @Override public String getInfo() { return target == null ? null : target.getName().getString(); }

    private boolean valid(Entity e) {
        if (!(e instanceof LivingEntity l) || e == mc.player || !l.isAlive() || l.isInvisible()) return false;
        if (e instanceof Player p) return players.get() && !p.isSpectator() && !p.isCreative();
        return mobs.get() && e instanceof Enemy;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.screen != null) { target = null; return; }
        // keep the current target while it's still fair game, otherwise pick the closest one we can see
        if (target == null || !valid(target) || target.distanceTo(mc.player) > range.get() + 2 || !mc.player.hasLineOfSight(target)) {
            target = null;
            double best = range.get();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!valid(e) || !mc.player.hasLineOfSight(e)) continue;
                double d = e.distanceTo(mc.player);
                if (d < best) { best = d; target = (LivingEntity) e; }
            }
        }
        if (target == null) return;
        Vec3 aim = target.getBoundingBox().getCenter();
        PlayerControl.lookAt(aim, 45f);
        double reach = mc.player.entityInteractionRange();
        double d = mc.player.getEyePosition().distanceTo(target.getBoundingBox().getCenter()) - target.getBbWidth() / 2;
        boolean charged = mc.player.getAttackStrengthScale(0.5f) >= 0.95f;
        if (charged && d <= reach && mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh && eh.getEntity() == target) {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        if (++strafeTicks > 30 || mc.player.horizontalCollision) { strafeTicks = 0; if (mc.player.horizontalCollision || Math.random() < 0.35) strafeDir = -strafeDir; }
    }

    /** Walk in when charged, back off while recharging, circle around. */
    private void onInput(MovementInputUpdateEvent e) {
        if (!isEnabled() || !move.get() || target == null || e.getEntity() != mc.player) return;
        Input in = e.getInput();
        double d = mc.player.distanceTo(target);
        boolean charged = mc.player.getAttackStrengthScale(0.5f) >= 0.9f;
        double want = charged ? hitDistance.get() : backOff.get();
        float fwd = d > want + 0.25 ? 1f : d < want - 0.25 ? -1f : 0f;
        in.forwardImpulse = fwd;
        in.up = fwd > 0;
        in.down = fwd < 0;
        if (strafe.get()) {
            in.leftImpulse = strafeDir;
            in.left = strafeDir > 0;
            in.right = strafeDir < 0;
        }
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    @Override
    public void onRenderLast(RenderLevelStageEvent event) {
        LivingEntity t = target;
        if (t == null) return;
        WorldRender.draw(event, b -> b.box(t.getBoundingBox(), 0xFFFF5252, ColorUtil.withAlpha(0xFFFF5252, 35)));
    }
}
