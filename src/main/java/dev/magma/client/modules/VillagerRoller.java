package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.ModeSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.util.Chat;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.PlayerControl;
import dev.magma.client.util.Theme;
import dev.magma.client.util.WorldRender;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Librarian trade roller: break lectern -> villager loses job -> place lectern -> check trades -> repeat
 * until the wanted enchanted book shows up at an acceptable price.
 */
public class VillagerRoller extends Module {
    @Override public java.util.List<Class<? extends dev.magma.client.module.Module>> addons() { return java.util.List.of(AutoTool.class); }

    @Override public boolean persistEnabled() { return false; }

    private static final Map<String, ResourceKey<Enchantment>> ENCHANTS = new LinkedHashMap<>();

    static {
        ENCHANTS.put("Mending", Enchantments.MENDING);
        ENCHANTS.put("Unbreaking", Enchantments.UNBREAKING);
        ENCHANTS.put("Efficiency", Enchantments.EFFICIENCY);
        ENCHANTS.put("Fortune", Enchantments.FORTUNE);
        ENCHANTS.put("Silk Touch", Enchantments.SILK_TOUCH);
        ENCHANTS.put("Protection", Enchantments.PROTECTION);
        ENCHANTS.put("Sharpness", Enchantments.SHARPNESS);
        ENCHANTS.put("Looting", Enchantments.LOOTING);
        ENCHANTS.put("Feather Falling", Enchantments.FEATHER_FALLING);
        ENCHANTS.put("Power", Enchantments.POWER);
        ENCHANTS.put("Infinity", Enchantments.INFINITY);
        ENCHANTS.put("Respiration", Enchantments.RESPIRATION);
        ENCHANTS.put("Depth Strider", Enchantments.DEPTH_STRIDER);
        ENCHANTS.put("Aqua Affinity", Enchantments.AQUA_AFFINITY);
        ENCHANTS.put("Frost Walker", Enchantments.FROST_WALKER);
        ENCHANTS.put("Thorns", Enchantments.THORNS);
        ENCHANTS.put("Fire Aspect", Enchantments.FIRE_ASPECT);
        ENCHANTS.put("Sweeping Edge", Enchantments.SWEEPING_EDGE);
        ENCHANTS.put("Knockback", Enchantments.KNOCKBACK);
        ENCHANTS.put("Smite", Enchantments.SMITE);
        ENCHANTS.put("Fire Protection", Enchantments.FIRE_PROTECTION);
        ENCHANTS.put("Blast Protection", Enchantments.BLAST_PROTECTION);
        ENCHANTS.put("Projectile Protection", Enchantments.PROJECTILE_PROTECTION);
        ENCHANTS.put("Punch", Enchantments.PUNCH);
        ENCHANTS.put("Flame", Enchantments.FLAME);
        ENCHANTS.put("Lure", Enchantments.LURE);
        ENCHANTS.put("Luck of the Sea", Enchantments.LUCK_OF_THE_SEA);
        ENCHANTS.put("Loyalty", Enchantments.LOYALTY);
        ENCHANTS.put("Impaling", Enchantments.IMPALING);
        ENCHANTS.put("Riptide", Enchantments.RIPTIDE);
        ENCHANTS.put("Channeling", Enchantments.CHANNELING);
        ENCHANTS.put("Multishot", Enchantments.MULTISHOT);
        ENCHANTS.put("Quick Charge", Enchantments.QUICK_CHARGE);
        ENCHANTS.put("Piercing", Enchantments.PIERCING);
    }

    private static String[] enchantOptions() {
        List<String> l = new java.util.ArrayList<>(ENCHANTS.keySet());
        l.add("Any Max Level");
        return l.toArray(new String[0]);
    }

    public final ModeSetting enchant = add(new ModeSetting("Enchantment", "The book you want the librarian to sell.", "Mending", enchantOptions()));
    public final BoolSetting maxLevel = add(new BoolSetting("Max Level Only", "Only accept the highest level (e.g. Unbreaking III).", true));
    public final NumberSetting maxPrice = add(new NumberSetting("Max Price", "Most emeralds you're willing to pay.", 24, 5, 64, 1));
    public final NumberSetting maxAttempts = add(new NumberSetting("Max Attempts", "Give up after this many rerolls.", 400, 10, 2000, 10));
    public final BoolSetting sound = add(new BoolSetting("Success Sound", "Play a sound when the trade is found.", true));

    private enum State { BREAK, WAIT_UNEMPLOYED, PLACE, WAIT_LIBRARIAN, OPEN, WAIT_TRADES }

    private State state;
    private BlockPos lectern;
    private Villager villager;
    private int timer, attempts;
    private String status = "Idle";
    private final Deque<String> history = new ArrayDeque<>();

    public VillagerRoller() {
        super("VillagerRoller", "Rerolls a librarian's trades (break/replace lectern) until it sells the book you want. Look at the lectern and enable.", Category.AUTOMATION, -1);
        advanced(maxAttempts, sound);
    }

    @Override public String getInfo() { return status; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Deque<String> getHistory() { return history; }

    @Override
    protected void onEnable() {
        attempts = 0;
        history.clear();
        lectern = null;
        villager = null;
        if (mc.player == null || mc.level == null) { setEnabled(false); return; }

        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
                && mc.level.getBlockState(bhr.getBlockPos()).is(Blocks.LECTERN)) {
            lectern = bhr.getBlockPos();
        } else {
            BlockPos p = mc.player.blockPosition();
            double best = Double.MAX_VALUE;
            for (BlockPos b : BlockPos.betweenClosed(p.offset(-4, -2, -4), p.offset(4, 2, 4))) {
                if (mc.level.getBlockState(b).is(Blocks.LECTERN) && b.distSqr(p) < best) {
                    best = b.distSqr(p);
                    lectern = b.immutable();
                }
            }
        }
        if (lectern == null) {
            Chat.info("VillagerRoller: look at (or stand next to) the lectern first.");
            setEnabled(false);
            return;
        }
        List<Villager> vs = mc.level.getEntitiesOfClass(Villager.class, new AABB(lectern).inflate(8));
        double best = Double.MAX_VALUE;
        for (Villager v : vs) {
            if (v.isBaby()) continue;
            double d = v.distanceToSqr(Vec3.atCenterOf(lectern));
            if (d < best) { best = d; villager = v; }
        }
        if (villager == null) {
            Chat.info("VillagerRoller: no adult villager near the lectern.");
            setEnabled(false);
            return;
        }
        if (findHotbar(Items.LECTERN) == -1 && !mc.player.getMainHandItem().is(Items.LECTERN)) {
            Chat.info("VillagerRoller: put a spare lectern in your hotbar (it gets picked back up each roll).");
        }
        Chat.info("Rolling for " + enchant.get() + (maxLevel.get() ? " (max level)" : "") + " <= " + maxPrice.getInt() + " emeralds.");
        state = profession() == VillagerProfession.LIBRARIAN ? State.OPEN : State.BREAK;
        timer = 0;
    }

    @Override
    protected void onDisable() {
        PlayerControl.releaseMovement();
        status = "Idle";
    }

    private VillagerProfession profession() {
        return villager.getVillagerData().getProfession();
    }

    private int findHotbar(net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    @Override
    public void onTick() {
        if (villager == null || !villager.isAlive() || villager.distanceToSqr(mc.player) > 64) {
            Chat.info("VillagerRoller: lost the villager.");
            setEnabled(false);
            return;
        }
        if (attempts >= maxAttempts.getInt()) {
            Chat.info("VillagerRoller: gave up after " + attempts + " attempts.");
            setEnabled(false);
            return;
        }
        timer++;
        switch (state) {
            case BREAK -> {
                if (mc.screen != null) { mc.player.closeContainer(); return; }
                status = "Breaking lectern (#" + (attempts + 1) + ")";
                if (!mc.level.getBlockState(lectern).is(Blocks.LECTERN)) {
                    state = State.WAIT_UNEMPLOYED;
                    timer = 0;
                    return;
                }
                PlayerControl.selectSlot(PlayerControl.bestToolSlot(mc.level.getBlockState(lectern), true));
                PlayerControl.lookAt(PlayerControl.aimPoint(mc.level, lectern, mc.player.getEyePosition()), 60f);
                if (mc.hitResult instanceof BlockHitResult bhr && bhr.getBlockPos().equals(lectern)) PlayerControl.forceAttack = true;
            }
            case WAIT_UNEMPLOYED -> {
                status = "Waiting for villager to lose job";
                if (profession() == VillagerProfession.NONE) {
                    state = State.PLACE;
                    timer = 0;
                } else if (timer > 200) {
                    Chat.info("VillagerRoller: villager kept its job - it was probably traded with already (locked).");
                    setEnabled(false);
                }
            }
            case PLACE -> {
                status = "Placing lectern";
                if (mc.level.getBlockState(lectern).is(Blocks.LECTERN)) {
                    state = State.WAIT_LIBRARIAN;
                    timer = 0;
                    return;
                }
                if (timer % 6 != 1) return;
                int slot = findHotbar(Items.LECTERN);
                if (slot == -1) {
                    if (timer > 60) {
                        Chat.info("VillagerRoller: no lectern in hotbar.");
                        setEnabled(false);
                    }
                    return; // wait for the dropped lectern to be picked up
                }
                PlayerControl.selectSlot(slot);
                BlockPos below = lectern.below();
                Vec3 top = Vec3.atCenterOf(below).add(0, 0.5, 0);
                PlayerControl.lookAt(top, 90f);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(top, Direction.UP, below, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            case WAIT_LIBRARIAN -> {
                status = "Waiting for librarian";
                if (profession() == VillagerProfession.LIBRARIAN) {
                    state = State.OPEN;
                    timer = 0;
                } else if (timer > 300) {
                    state = State.BREAK; // try again
                    timer = 0;
                }
            }
            case OPEN -> {
                status = "Checking trades";
                if (timer % 10 != 1) return;
                PlayerControl.lookAt(villager.getEyePosition(), 90f);
                mc.gameMode.interact(mc.player, villager, InteractionHand.MAIN_HAND);
                state = State.WAIT_TRADES;
                timer = 0;
            }
            case WAIT_TRADES -> {
                if (mc.screen instanceof MerchantScreen ms && !ms.getMenu().getOffers().isEmpty()) {
                    evaluate(ms);
                } else if (timer > 60) {
                    state = State.OPEN;
                    timer = 0;
                }
            }
        }
    }

    private void evaluate(MerchantScreen ms) {
        ResourceKey<Enchantment> want = ENCHANTS.get(enchant.get());
        String seen = "no book";
        for (MerchantOffer offer : ms.getMenu().getOffers()) {
            ItemStack result = offer.getResult();
            if (!result.is(Items.ENCHANTED_BOOK)) continue;
            ItemEnchantments ench = result.get(DataComponents.STORED_ENCHANTMENTS);
            if (ench == null) continue;
            int price = offer.getCostA().getCount();
            for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
                Holder<Enchantment> h = e.getKey();
                int lvl = e.getIntValue();
                seen = Enchantment.getFullname(h, lvl).getString() + " @" + price;
                boolean typeOk = want == null || h.is(want);
                boolean levelOk = !maxLevel.get() || lvl >= h.value().getMaxLevel();
                if (typeOk && levelOk && price <= maxPrice.getInt()) {
                    pushHistory("✔ " + seen);
                    Chat.info("Found " + seen + " emeralds after " + (attempts + 1) + " rolls! Trade it now so the villager locks.");
                    if (sound.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f));
                    status = "Found!";
                    setEnabled(false);
                    return;
                }
            }
        }
        pushHistory(seen);
        attempts++;
        mc.player.closeContainer();
        state = State.BREAK;
        timer = 0;
    }

    private void pushHistory(String s) {
        history.addFirst(s);
        while (history.size() > 6) history.removeLast();
    }

    @Override
    public void onRenderWorld(RenderLevelStageEvent event) {
        BlockPos l = lectern;
        Villager v = villager;
        WorldRender.draw(event, b -> {
            if (l != null) b.block(l, ColorUtil.withAlpha(Theme.accent(), 220), ColorUtil.withAlpha(Theme.accent(), 35));
            if (v != null) b.box(v.getBoundingBox(), 0xFF4ADE80, 0x204ADE80);
        });
    }
}
