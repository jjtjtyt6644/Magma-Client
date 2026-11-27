package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.WorldRender;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Spectator-style camera that leaves your body behind: fly through walls and look around while your character
 * stays exactly where it is. WASD to move, Space/Shift up/down, Sprint for faster.
 */
public class Freecam extends Module {
    public final NumberSetting speed = add(new NumberSetting("Speed", "How fast the camera flies.", 1, 0.2, 5, 0.1));

    private Cam cam;
    private LocalPlayer owner;
    private Input savedInput;
    private boolean savedCull;

    public Freecam() {
        super("Freecam", "Detach the camera and fly around to look at your surroundings; your character stays put.", Category.MOVEMENT, -1);
    }

    @Override
    public boolean persistEnabled() { return false; }

    /** Called by the mouse (via mixin): turn the camera instead of the player. */
    public static boolean turnCamera(double yRot, double xRot) {
        if (!ModuleManager.ready()) return false;
        Freecam f = ModuleManager.get(Freecam.class);
        if (!f.isEnabled() || f.cam == null) return false;
        f.cam.turn(yRot, xRot);
        return true;
    }

    public static boolean active() {
        return ModuleManager.ready() && ModuleManager.get(Freecam.class).isEnabled();
    }

    @Override
    protected void onEnable() {
        if (mc.player == null || mc.level == null) { setEnabled(false); return; }
        owner = mc.player;
        cam = new Cam(mc.level);
        Vec3 eye = mc.player.getEyePosition();
        cam.moveTo(eye.x, eye.y, eye.z, mc.player.getYRot(), mc.player.getXRot());
        cam.setOldPosAndRot();
        mc.setCameraEntity(cam);
        // your character stops dead: its movement input is replaced by an empty one
        savedInput = mc.player.input;
        mc.player.input = new Input();
        savedCull = mc.smartCull;
        mc.smartCull = false; // don't hide chunks when the camera is inside blocks
        mc.gameRenderer.setRenderHand(false); // no floating hand in front of the camera
    }

    @Override
    protected void onDisable() {
        if (owner != null && owner == mc.player && savedInput != null) mc.player.input = savedInput;
        if (mc.player != null) mc.setCameraEntity(mc.player);
        mc.smartCull = savedCull || mc.smartCull;
        mc.gameRenderer.setRenderHand(true);
        cam = null;
        owner = null;
        savedInput = null;
    }

    @Override
    public void onTick() {
        if (cam == null || mc.player == null || mc.player != owner || mc.player.isDeadOrDying()) {
            setEnabled(false); // respawned / changed dimension / died
            return;
        }
        if (mc.getCameraEntity() != cam) mc.setCameraEntity(cam);
        cam.setOldPosAndRot();
        double fwd = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double side = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double up = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);
        double v = speed.get() * (mc.options.keySprint.isDown() ? 2.0 : 1.0);
        double yaw = Math.toRadians(cam.getYRot());
        double dx = (-Math.sin(yaw) * fwd + Math.cos(yaw) * side) * v;
        double dz = (Math.cos(yaw) * fwd + Math.sin(yaw) * side) * v;
        cam.setPos(cam.getX() + dx, cam.getY() + up * v, cam.getZ() + dz);
    }

    /** A box where your character is, so you can find yourself again. */
    @Override
    public void onRenderLast(RenderLevelStageEvent event) {
        if (cam == null || mc.player == null) return;
        WorldRender.draw(event, b -> b.box(mc.player.getBoundingBox(), 0xFF4FC3F7, ColorUtil.withAlpha(0xFF4FC3F7, 40)));
    }

    /** The invisible camera body (never added to the world). */
    private static final class Cam extends Entity {
        Cam(Level level) {
            super(EntityType.MARKER, level);
            noPhysics = true;
        }

        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}
        @Override public boolean isSpectator() { return true; }
    }
}
