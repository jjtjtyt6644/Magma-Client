package dev.magma.client.util;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Named ore groups shared by AutoMiner (and anything else that wants "which ores?"). */
public final class OreTypes {
    private OreTypes() {}

    public static final List<String> VANILLA = List.of(
            "Quartz", "Nether Gold", "Ancient Debris", "Gilded Blackstone",
            "Diamond", "Emerald", "Gold", "Iron", "Redstone", "Lapis", "Copper", "Coal", "Amethyst");

    /** Modded ore groups found in the block registry, e.g. "Zinc (create)" -> {zinc_ore, deepslate_zinc_ore}. */
    public static final java.util.Map<String, java.util.Set<net.minecraft.world.level.block.Block>> MODDED = new java.util.TreeMap<>();
    private static final java.util.Set<net.minecraft.world.level.block.Block> MODDED_ALL = new java.util.HashSet<>();

    static {
        for (var e : net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet()) {
            var id = e.getKey().location();
            if (id.getNamespace().equals("minecraft")) continue;
            String path = id.getPath();
            boolean ore = path.endsWith("_ore") || path.startsWith("ore_") || path.contains("_ore_");
            if (!ore || path.contains("block_of") || path.startsWith("raw_") || path.endsWith("_ore_block")) continue;
            String base = path.replace("_ore", "").replace("ore_", "");
            for (String pre : new String[]{"deepslate_", "nether_", "end_", "stone_", "netherrack_", "blackstone_", "basalt_", "granite_", "diorite_", "andesite_", "tuff_", "dense_", "poor_", "rich_"}) {
                if (base.startsWith(pre)) base = base.substring(pre.length());
            }
            if (base.isEmpty()) continue;
            String nice = Character.toUpperCase(base.charAt(0)) + base.substring(1).replace('_', ' ') + " (" + id.getNamespace() + ")";
            MODDED.computeIfAbsent(nice, k -> new java.util.HashSet<>()).add(e.getValue());
            MODDED_ALL.add(e.getValue());
        }
    }

    /** Every ore type you can pick: vanilla first, then whatever ores your mods add. */
    public static final List<String> ALL;

    static {
        List<String> l = new java.util.ArrayList<>(VANILLA);
        if (!MODDED.isEmpty()) l.add("All Modded");
        l.addAll(MODDED.keySet());
        ALL = List.copyOf(l);
    }

    /** A modded ore (by name, or tagged c:ores by its mod). */
    private static final java.util.Map<net.minecraft.world.level.block.Block, Boolean> MODDED_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isModdedOre(BlockState s) {
        var b = s.getBlock();
        if (MODDED_ALL.contains(b)) return true;
        return MODDED_CACHE.computeIfAbsent(b, k -> {
            var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(k);
            return !key.getNamespace().equals("minecraft") && s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
        });
    }

    /** Tags arrive when you join a world: forget cached answers so newly-tagged ores are picked up. */
    public static void clearCache() { MODDED_CACHE.clear(); }

    public static boolean matches(String type, BlockState s) {
        return switch (type) {
            case "Quartz" -> s.is(Blocks.NETHER_QUARTZ_ORE);
            case "Nether Gold" -> s.is(Blocks.NETHER_GOLD_ORE);
            case "Ancient Debris" -> s.is(Blocks.ANCIENT_DEBRIS);
            case "Gilded Blackstone" -> s.is(Blocks.GILDED_BLACKSTONE);
            case "Diamond" -> s.is(BlockTags.DIAMOND_ORES);
            case "Emerald" -> s.is(BlockTags.EMERALD_ORES);
            case "Gold" -> s.is(BlockTags.GOLD_ORES) && !s.is(Blocks.NETHER_GOLD_ORE);
            case "Iron" -> s.is(BlockTags.IRON_ORES);
            case "Redstone" -> s.is(BlockTags.REDSTONE_ORES);
            case "Lapis" -> s.is(BlockTags.LAPIS_ORES);
            case "Copper" -> s.is(BlockTags.COPPER_ORES);
            case "Coal" -> s.is(BlockTags.COAL_ORES);
            case "Amethyst" -> s.is(Blocks.BUDDING_AMETHYST);
            case "All Modded" -> isModdedOre(s);
            default -> {
                var set = MODDED.get(type);
                yield set != null && set.contains(s.getBlock());
            }
        };
    }
}
