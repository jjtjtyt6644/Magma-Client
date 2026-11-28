package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.LavaUtil;
import dev.magma.client.util.Notify;
import dev.magma.client.util.Theme;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Stops your movement before you walk into lava/fire or off a drop taller than you allow.
 * AutoMiner always uses it, even when this module is off.
 */
public class SafeWalk extends Module {
    public final BoolSetting lava = add(new BoolSetting("Lava & Fire", "Never walk into lava, fire, or onto a spot with lava underneath.", true));
    public final BoolSetting edges = add(new BoolSetting("Fall Guard", "Stop at the edge of drops taller than Max Fall.", true));
    public final NumberSetting maxFall = add(new NumberSetting("Max Fall", "Tallest drop you may walk off (3 = no fall damage).", 3, 1, 20, 1).visibleWhen(() -> edges.get()));

    private long lastToast;

    public SafeWalk() {
        super("SafeWalk", "Stops you walking into lava/fire or off big drops. AutoMiner always uses this.", Category.SAFETY, -1);
        advanced(maxFall);
        NeoForge.EVENT_BUS.addListener(MovementInputUpdateEvent.class, this::onInput);
    }

    private boolean active() {
        return ModuleManager.ready() && (isEnabled() || ModuleManager.get(AutoMiner.class).engineRunning());
    }

    /** Max fall AutoMiner should plan with. */
    public int fallLimit() {
        return isEnabled() && edges.get() ? Math.min(3, maxFall.getInt()) : 3;
    }

    private void onInput(MovementInputUpdateEvent e) {
        if (!active() || e.getEntity() != mc.player || mc.level == null) return;
        Input in = e.getInput();
        if (in.forwardImpulse == 0 && in.leftImpulse == 0) return;
        if (mc.player.getAbilities().flying || mc.player.isInWater() || mc.player.isPassenger()) return;

        double yaw = Math.toRadians(mc.player.getYRot());
        double dx = in.leftImpulse * Math.cos(yaw) - in.forwardImpulse * Math.sin(yaw);
        double dz = in.forwardImpulse * Math.cos(yaw) + in.leftImpulse * Math.sin(yaw);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return;
        dx /= len;
        dz /= len;

        boolean autoMiner = !isEnabled(); // only here because AutoMiner is running
        String why = null;
        for (double dist : new double[]{0.45, 0.8}) {
            why = danger(mc.player.position().add(dx * dist, 0, dz * dist), autoMiner);
            if (why != null) break;
        }
        if (why == null) return;
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = false;
        if (!autoMiner && System.currentTimeMillis() - lastToast > 1500) {
            lastToast = System.currentTimeMillis();
            Notify.show("SafeWalk stopped you: " + why, "Turn SafeWalk off (or raise Max Fall) if you really want to go there.", Theme.WARN);
        }
    }

    private String danger(Vec3 probe, boolean autoMiner) {
        BlockPos feet = BlockPos.containing(mc.player.getX(), mc.player.getY() + 0.1, mc.player.getZ());
        BlockPos c = BlockPos.containing(probe.x, mc.player.getY() + 0.1, probe.z);
        if (c.equals(feet)) return null;
        boolean checkLava = lava.get() || autoMiner;
        if (checkLava && (hazard(c) || hazard(c.above()))) return "lava ahead";
        if (trap(c)) return "hazard ahead";
        // AutoMiner/Goto route steps (bridges, planned drops) are already vetted by the planner
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        if (am.engineRunning() && am.isPlannedStep(c)) return null;
        if (!mc.level.getBlockState(c).getCollisionShape(mc.level, c).isEmpty()) return null; // a wall/step, not a drop
        if (!mc.player.onGround()) return null;
        int limit = autoMiner ? 3 : maxFall.getInt();
        BlockPos b = c.below();
        int air = 0;
        for (int i = 0; i < 32; i++) {
            if (checkLava && LavaUtil.isLava(mc.level, b)) return "lava below";
            if (!mc.level.getBlockState(b).getCollisionShape(mc.level, b).isEmpty()) break;
            air++;
            b = b.below();
        }
        boolean scaffold = !autoMiner && ModuleManager.get(Scaffold.class).isEnabled(); // Scaffold fills the gap: walking off is fine
        if ((edges.get() && !scaffold || autoMiner) && air > limit) return "drop of " + air + (air >= 32 ? "+" : "") + " blocks";
        if (air == 0 && checkLava && hurtFloor(c.below())) return "magma/campfire floor";
        return null;
    }

    /** Blocks you shouldn't walk into: they hurt, slow or trap you. */
    private boolean trap(BlockPos p) {
        BlockState s = mc.level.getBlockState(p);
        return s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.COBWEB) || s.is(Blocks.CACTUS);
    }

    /** Floors that damage you when you stand on them. */
    public static boolean hurtFloor(BlockPos p) {
        BlockState s = net.minecraft.client.Minecraft.getInstance().level.getBlockState(p);
        return s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.CACTUS);
    }

    private boolean hazard(BlockPos p) {
        BlockState s = mc.level.getBlockState(p);
        return LavaUtil.isLava(mc.level, p) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE);
    }
}
