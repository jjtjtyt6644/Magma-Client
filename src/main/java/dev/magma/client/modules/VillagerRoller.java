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

    public final dev.magma.client.setting.MultiSetting enchants = add(new dev.magma.client.setting.MultiSetting("Books Wanted",
            "Librarians: stop when any of these books shows up (pick as many as you like).", java.util.List.of(enchantOptions()), "Mending"));
    public final BoolSetting maxLevel = add(new BoolSetting("Max Level Only", "Only accept the highest level (e.g. Unbreaking III).", true));
    public final NumberSetting maxPrice = add(new NumberSetting("Max Price", "Most emeralds you're willing to pay.", 24, 1, 64, 1));
    public final dev.magma.client.setting.TextSetting items = add(new dev.magma.client.setting.TextSetting("Items Wanted",
            "Any job: stop when a trade has one of these (comma separated, e.g. golden carrot, bell, diamond chestplate).", "", null, 200));
    public final NumberSetting maxAttempts = add(new NumberSetting("Max Attempts", "Give up after this many rerolls.", 400, 10, 2000, 10));
    public final BoolSetting sound = add(new BoolSetting("Success Sound", "Play a sound when the trade is found.", true));

    private enum State { BREAK, WAIT_UNEMPLOYED, PLACE, WAIT_LIBRARIAN, OPEN, WAIT_TRADES }

    private State state;
    private BlockPos lectern;       // the villager's job block (lectern, composter, smoker...)
    private net.minecraft.world.level.block.Block jobBlock;
    private VillagerProfession jobProfession;
    private Villager villager;
    private int timer, attempts;
    private String status = "Idle";
    private final Deque<String> history = new ArrayDeque<>();

    public VillagerRoller() {
        super("VillagerRoller", "Rerolls a villager's trades (breaks and replaces its job block) until it offers what you want: any books for librarians, any items for other jobs. Look at the job block and enable.", Category.AUTOMATION, -1);
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
                && professionFor(mc.level.getBlockState(bhr.getBlockPos())) != null) {
            lectern = bhr.getBlockPos();
        } else {
            BlockPos p = mc.player.blockPosition();
            double best = Double.MAX_VALUE;
            for (BlockPos b : BlockPos.betweenClosed(p.offset(-4, -2, -4), p.offset(4, 2, 4))) {
                if (professionFor(mc.level.getBlockState(b)) != null && b.distSqr(p) < best) {
                    best = b.distSqr(p);
                    lectern = b.immutable();
                }
            }
        }
        if (lectern == null) {
            Chat.info("VillagerRoller: look at (or stand next to) the villager's job block first (lectern, composter, smoker...).");
            setEnabled(false);
            return;
        }
        jobBlock = mc.level.getBlockState(lectern).getBlock();
        jobProfession = professionFor(mc.level.getBlockState(lectern));
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
        boolean wantsBooks = jobProfession == VillagerProfession.LIBRARIAN && !enchants.get().isEmpty();
        if (!wantsBooks && itemTokens().isEmpty()) {
            Chat.info("VillagerRoller: tell it what you want first - type items in \"Items Wanted\" (e.g. golden carrot, bell)"
                    + (jobProfession == VillagerProfession.LIBRARIAN ? " or tick some books." : "."));
            setEnabled(false);
            return;
        }
        Chat.info("VillagerRoller: rolling a " + jobName() + " for " + wantedText() + " (max " + maxPrice.getInt() + " emeralds).");
        state = profession() == jobProfession ? State.OPEN : State.BREAK;
        timer = 0;
    }

    @Override
    protected void onDisable() {
        PlayerControl.releaseMovement();
        status = "Idle";
    }

    /** The job a villager gets from this block (lectern -> librarian, composter -> farmer...), or null. */
    private static VillagerProfession professionFor(net.minecraft.world.level.block.state.BlockState st) {
        var poi = net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(st);
        if (poi.isEmpty()) return null;
        for (VillagerProfession p : net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION) {
            if (p != VillagerProfession.NONE && p.heldJobSite().test(poi.get())) return p;
        }
        return null;
    }

    private String jobName() {
        return jobProfession == null ? "villager" : net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(jobProfession).getPath();
    }

    private List<String> itemTokens() {
        List<String> out = new java.util.ArrayList<>();
        for (String t : items.get().split(",")) if (!t.isBlank()) out.add(t.trim().toLowerCase().replace(' ', '_'));
        return out;
    }

    private String wantedText() {
        List<String> parts = new java.util.ArrayList<>();
        if (jobProfession == VillagerProfession.LIBRARIAN && !enchants.get().isEmpty())
            parts.add(String.join(" / ", enchants.get()) + (maxLevel.get() ? " (max level)" : ""));
        if (!itemTokens().isEmpty()) parts.add(items.get());
        return parts.isEmpty() ? "anything" : String.join(" or ", parts);
    }

    private VillagerProfession profession() {
        return villager.getVillagerData().getProfession();
    }

    /** Hotbar slot holding this item; pulls it in from the rest of the inventory if needed. -1 if you have none. */
    private int findHotbar(net.minecraft.world.item.Item item) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(item)) return i;
        for (int i = 9; i < 36; i++) {
            if (!inv.getItem(i).is(item)) continue;
            int hb = inv.selected;
            for (int k = 0; k < 9; k++) if (inv.getItem(k).isEmpty()) { hb = k; break; }
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, i, hb,
                    net.minecraft.world.inventory.ClickType.SWAP, mc.player);
            return hb;
        }
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
                status = "Breaking " + jobBlock.getName().getString() + " (#" + (attempts + 1) + ")";
                if (!mc.level.getBlockState(lectern).is(jobBlock)) {
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
                status = "Placing " + jobBlock.getName().getString();
                if (mc.level.getBlockState(lectern).is(jobBlock)) {
                    state = State.WAIT_LIBRARIAN;
                    timer = 0;
                    return;
                }
                if (timer % 6 != 1) return;
                int slot = findHotbar(jobBlock.asItem());
                if (slot == -1) {
                    if (timer > 60) {
                        Chat.info("VillagerRoller: no " + jobBlock.getName().getString() + " in your hotbar.");
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
                status = "Waiting for " + jobName();
                if (profession() == jobProfession) {
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
        List<String> tokens = itemTokens();
        boolean anyBook = enchants.get().contains("Any Max Level");
        String seen = null;
        for (MerchantOffer offer : ms.getMenu().getOffers()) {
            ItemStack result = offer.getResult();
            int price = offer.getCostA().is(Items.EMERALD) ? offer.getCostA().getCount()
                    : offer.getCostB().is(Items.EMERALD) ? offer.getCostB().getCount() : 0;
            // books (librarians)
            ItemEnchantments ench = result.is(Items.ENCHANTED_BOOK) ? result.get(DataComponents.STORED_ENCHANTMENTS) : null;
            if (ench != null) {
                for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
                    Holder<Enchantment> h = e.getKey();
                    int lvl = e.getIntValue();
                    String name = Enchantment.getFullname(h, lvl).getString() + " @" + price;
                    if (seen == null) seen = name;
                    boolean typeOk = anyBook ? lvl >= h.value().getMaxLevel() : wantsEnchant(h);
                    boolean levelOk = !maxLevel.get() || lvl >= h.value().getMaxLevel();
                    if (typeOk && levelOk && price <= maxPrice.getInt()) { found(name); return; }
                }
            }
            // anything else, by name: what they sell, or what they buy from you
            for (ItemStack st : new ItemStack[]{result, offer.getCostA()}) {
                if (st.isEmpty() || st.is(Items.EMERALD) || tokens.isEmpty()) continue;
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
                String nm = st.getHoverName().getString().toLowerCase().replace(' ', '_');
                for (String t : tokens) {
                    if ((id.contains(t) || nm.contains(t)) && (st == offer.getCostA() || price <= maxPrice.getInt())) {
                        found(st.getHoverName().getString() + (price > 0 ? " @" + price : ""));
                        return;
                    }
                }
            }
            if (seen == null && !result.is(Items.EMERALD)) seen = result.getHoverName().getString() + (price > 0 ? " @" + price : "");
        }
        pushHistory(seen == null ? "nothing useful" : seen);
        attempts++;
        mc.player.closeContainer();
        state = State.BREAK;
        timer = 0;
    }

    private boolean wantsEnchant(Holder<Enchantment> h) {
        for (String n : enchants.get()) {
            ResourceKey<Enchantment> k = ENCHANTS.get(n);
            if (k != null && h.is(k)) return true;
        }
        return false;
    }

    private void found(String what) {
        pushHistory("✔ " + what);
        Chat.info("Found " + what + " after " + (attempts + 1) + " rolls! Trade it now so the villager keeps it.");
        if (sound.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f));
        status = "Found!";
        setEnabled(false);
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
