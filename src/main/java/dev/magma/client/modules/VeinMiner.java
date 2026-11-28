package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.PlayerControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Break one ore or log and it carries on mining the rest of the vein / tree that it can reach. */
public class VeinMiner extends Module {
    public final BoolSetting ores = add(new BoolSetting("Ores", "Mine the whole ore vein.", true));
    public final BoolSetting logs = add(new BoolSetting("Trees", "Chop the whole tree (TreeCapitator).", true));
    public final BoolSetting instant = add(new BoolSetting("Instant", "Singleplayer: the whole vein breaks at once and drops where you mined (servers: mines it block by block).", true));
    public final NumberSetting max = add(new NumberSetting("Max Blocks", "Most blocks it mines in one go.", 64, 4, 256, 4));

    private BlockPos watching;
    private Block watchBlock;
    private int watchTicks;
    private final List<BlockPos> queue = new ArrayList<>();
    private Block veinBlock;
    private BlockPos breaking;
    private boolean selfClick; // our own startDestroyBlock also fires the click event: don't treat it as yours

    public VeinMiner() {
        super("VeinMiner", "Mine one ore or log and it mines the rest of the vein or tree in reach (VeinMiner / TreeCapitator).", Category.WORLD, -1);
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.LeftClickBlock.class, this::onClick);
    }

    @Override public String getInfo() { return queue.isEmpty() ? null : queue.size() + " left"; }

    private boolean wanted(BlockState s) {
        if (logs.get() && s.is(BlockTags.LOGS)) return true;
        return ores.get() && s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
    }

    private void onClick(PlayerInteractEvent.LeftClickBlock e) {
        if (!isEnabled() || !e.getLevel().isClientSide() || mc.level == null) return;
        if (selfClick) return; // that's us
        BlockState s = mc.level.getBlockState(e.getPos());
        if (!queue.isEmpty() && s.is(veinBlock)) return; // still the same vein (you're just holding the button): keep going
        queue.clear(); // you started mining something else yourself: forget the old vein
        if (wanted(s)) { watching = e.getPos().immutable(); watchBlock = s.getBlock(); watchTicks = 0; }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) return;
        // the block you were mining just broke: queue up the rest of the vein
        if (watching != null) {
            if (++watchTicks > 200) watching = null;
            else if (mc.level.getBlockState(watching).isAir()) {
                collect(watching, watchBlock);
                if (instant.get() && mc.getSingleplayerServer() != null && !queue.isEmpty()) breakAllNow(watching);
                watching = null;
            }
        }
        if (queue.isEmpty()) return;
        if (mc.screen != null) return;
        // next block that's still there and within reach
        double reach = mc.player.blockInteractionRange();
        Vec3 eye = mc.player.getEyePosition();
        queue.removeIf(p -> !mc.level.getBlockState(p).is(veinBlock) || eye.distanceToSqr(Vec3.atCenterOf(p)) > reach * reach);
        if (queue.isEmpty()) { stopBreaking(); return; }
        queue.sort(java.util.Comparator.comparingDouble(p -> eye.distanceToSqr(Vec3.atCenterOf(p))));
        BlockPos p = queue.get(0);
        BlockState s = mc.level.getBlockState(p);
        int slot = PlayerControl.bestToolSlot(s, true);
        if (slot >= 0) PlayerControl.selectSlot(slot);
        PlayerControl.lookAt(Vec3.atCenterOf(p), 45f);
        Direction face = Direction.getNearest(eye.subtract(Vec3.atCenterOf(p)));
        if (!p.equals(breaking)) {
            if (breaking != null) mc.gameMode.stopDestroyBlock();
            breaking = p;
            selfClick = true;
            try { mc.gameMode.startDestroyBlock(p, face); } finally { selfClick = false; }
        } else {
            selfClick = true;
            try { mc.gameMode.continueDestroyBlock(p, face); } finally { selfClick = false; }
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Singleplayer: break the whole vein right now on the game's own server, like the VeinMiner mod - real drops,
     * tool durability and XP - then pull the drops to the block you mined.
     */
    private void breakAllNow(BlockPos origin) {
        var server = mc.getSingleplayerServer();
        java.util.UUID id = mc.player.getUUID();
        List<BlockPos> blocks = new ArrayList<>(queue);
        Block block = veinBlock;
        queue.clear();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            var level = sp.serverLevel();
            int done = 0;
            for (BlockPos p : blocks) {
                if (!level.getBlockState(p).is(block)) continue;
                var tool = sp.getMainHandItem();
                if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= 1) break; // don't break your tool
                if (sp.gameMode.destroyBlock(p)) done++;
            }
            // everything it dropped lands where you mined the first block
            var box = new net.minecraft.world.phys.AABB(origin).inflate(12);
            Vec3 at = Vec3.atCenterOf(origin);
            for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
                if (item.getAge() < 5) { item.setPos(at.x, at.y, at.z); item.setDeltaMovement(Vec3.ZERO); }
            }
            for (var orb : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box)) orb.setPos(at.x, at.y, at.z);
            int n = done;
            if (n > 0) mc.execute(() -> dev.magma.client.util.Notify.show("VeinMiner: " + (n + 1) + " blocks at once", "The whole vein dropped where you mined. Your tool took the wear for every block.", dev.magma.client.util.Theme.OK));
        });
    }

    private void stopBreaking() {
        if (breaking != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        breaking = null;
    }

    /** Every touching block of the same kind (diagonals count, like real veins and branches). */
    private void collect(BlockPos start, Block block) {
        queue.clear();
        veinBlock = block;
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty() && queue.size() < max.getInt()) {
            BlockPos c = q.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = c.offset(dx, dy, dz);
                if (!seen.add(n) || !mc.level.getBlockState(n).is(block)) continue;
                queue.add(n);
                q.add(n);
            }
        }
    }

    @Override
    protected void onDisable() {
        queue.clear();
        watching = null;
        stopBreaking();
    }
}
