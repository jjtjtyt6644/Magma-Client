package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bridging made easy: puts a block under your feet whenever there's none, as you walk off edges or jump.
 * Uses the block in your hand, or any solid block from your hotbar (your hand goes back afterwards).
 */
public class Scaffold extends Module {
    public final BoolSetting tower = add(new BoolSetting("Tower", "Hold jump to build straight up under yourself.", true));
    public final BoolSetting ahead = add(new BoolSetting("Look Ahead", "Also fill the gap you're about to walk onto (safer when sprinting).", true));
    public final BoolSetting junkFirst = add(new BoolSetting("Junk First", "Prefer cobblestone/dirt/netherrack over your nice blocks.", true));

    public Scaffold() {
        super("Scaffold", "Places blocks under your feet as you walk or jump - safe, fast bridging and towering.", Category.MOVEMENT, -1);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.screen != null || mc.player.getAbilities().flying) return;
        BlockPos under = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.5, mc.player.getZ());
        if (!tower.get() && mc.options.keyJump.isDown() && !mc.player.onGround()) return;
        if (fill(under)) return;
        if (ahead.get()) {
            Vec3 v = mc.player.getDeltaMovement();
            if (v.horizontalDistanceSqr() > 0.004) {
                BlockPos next = BlockPos.containing(mc.player.getX() + v.x * 3, mc.player.getY() - 0.5, mc.player.getZ() + v.z * 3);
                if (!next.equals(under)) fill(next);
            }
        }
    }

    /** Place a block at this spot if it's empty. */
    private boolean fill(BlockPos p) {
        if (!mc.level.getBlockState(p).canBeReplaced()) return false;
        if (mc.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))) return false;
        int slot = blockSlot();
        if (slot < 0) return false;
        // click the side of a neighbouring block (below first, then around); if none, click the empty spot itself
        BlockPos against = p;
        Direction face = Direction.UP;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos n = p.relative(d);
            if (!mc.level.getBlockState(n).canBeReplaced()) { against = n; face = d.getOpposite(); break; }
        }
        Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        int prev = mc.player.getInventory().selected;
        mc.player.getInventory().selected = slot;
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
        mc.player.getInventory().selected = prev; // your hand goes back (synced on the next action)
        return true;
    }

    private int blockSlot() {
        var inv = mc.player.getInventory();
        ItemStack held = inv.getItem(inv.selected);
        if (!junkFirst.get() && good(held)) return inv.selected;
        for (int i = 0; i < 9; i++) if (AutoMiner.isScaffold(inv.getItem(i))) return i;
        if (good(held)) return inv.selected;
        for (int i = 0; i < 9; i++) if (good(inv.getItem(i))) return i;
        return -1;
    }

    private static boolean good(ItemStack st) {
        if (!(st.getItem() instanceof BlockItem bi)) return false;
        var s = bi.getBlock().defaultBlockState();
        return !(bi.getBlock() instanceof FallingBlock) && s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
}
