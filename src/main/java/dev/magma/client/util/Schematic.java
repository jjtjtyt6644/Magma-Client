package dev.magma.client.util;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A loaded schematic: block states at positions relative to its lowest corner (0,0,0).
 * Reads Sponge/WorldEdit .schem (v2 + v3), Litematica .litematic and vanilla structure .nbt files.
 */
public final class Schematic {
    public final String name;
    /** Every position inside the schematic's box, including air (air = "should be empty"). */
    public final Map<BlockPos, BlockState> blocks;
    public final int sizeX, sizeY, sizeZ;
    /** Blocks the game doesn't know (e.g. from a mod you don't have); they're skipped. */
    public final int unknown;

    private Schematic(String name, Map<BlockPos, BlockState> blocks, int unknown) {
        this.name = name;
        int mx = 0, my = 0, mz = 0;
        for (BlockPos p : blocks.keySet()) {
            mx = Math.max(mx, p.getX() + 1);
            my = Math.max(my, p.getY() + 1);
            mz = Math.max(mz, p.getZ() + 1);
        }
        this.blocks = blocks;
        this.sizeX = mx;
        this.sizeY = my;
        this.sizeZ = mz;
        this.unknown = unknown;
    }

    public int solidCount() {
        int n = 0;
        for (BlockState s : blocks.values()) if (!s.isAir()) n++;
        return n;
    }

    /** Turned around the vertical axis, then shifted back so the lowest corner is (0,0,0) again. */
    public Schematic rotated(Rotation rot) {
        if (rot == Rotation.NONE) return this;
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        List<BlockPos> turned = new ArrayList<>();
        for (BlockPos p : blocks.keySet()) {
            BlockPos r = p.rotate(rot);
            turned.add(r);
            minX = Math.min(minX, r.getX());
            minZ = Math.min(minZ, r.getZ());
        }
        int i = 0;
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockPos r = turned.get(i++);
            out.put(new BlockPos(r.getX() - minX, r.getY(), r.getZ() - minZ), e.getValue().rotate(rot));
        }
        return new Schematic(name, out, unknown);
    }

    // ------------------------------------------------------------------ loading

    public static Schematic load(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        String fname = file.getFileName().toString();
        String name = fname.contains(".") ? fname.substring(0, fname.lastIndexOf('.')) : fname;
        String lower = fname.toLowerCase();
        int[] unknown = {0};
        Map<BlockPos, BlockState> blocks;
        if (root.contains("Regions", Tag.TAG_COMPOUND)) blocks = litematic(root, unknown);
        else if (root.contains("palette", Tag.TAG_LIST) || root.contains("palettes", Tag.TAG_LIST)) blocks = structure(root, unknown);
        else if (root.contains("Schematic", Tag.TAG_COMPOUND)) blocks = sponge(root.getCompound("Schematic"), unknown);
        else if (root.contains("Palette", Tag.TAG_COMPOUND) || root.contains("Blocks", Tag.TAG_COMPOUND)) blocks = sponge(root, unknown);
        else if (root.contains("Blocks", Tag.TAG_BYTE_ARRAY) || lower.endsWith(".schematic"))
            throw new IOException("old MCEdit .schematic (number IDs) isn't supported - re-save it as .schem in WorldEdit/Litematica");
        else throw new IOException("unknown schematic format");
        if (blocks.isEmpty()) throw new IOException("the schematic is empty");
        return new Schematic(name, blocks, unknown[0]);
    }

    private static HolderLookup<Block> lookup() {
        return BuiltInRegistries.BLOCK.asLookup();
    }

    private static BlockState parseState(String s, int[] unknown) {
        try {
            return BlockStateParser.parseForBlock(lookup(), s, false).blockState();
        } catch (Exception e) {
            unknown[0]++;
            return null;
        }
    }

    private static BlockState readState(CompoundTag tag, int[] unknown) {
        BlockState s = NbtUtils.readBlockState(lookup(), tag);
        // readBlockState falls back to air for unknown names
        if (s.isAir() && tag.contains("Name") && !tag.getString("Name").endsWith("air")) {
            unknown[0]++;
            return null;
        }
        return s;
    }

    /** Sponge schematic v2 (Palette/BlockData at the top) or v3 (inside "Blocks"). */
    private static Map<BlockPos, BlockState> sponge(CompoundTag t, int[] unknown) throws IOException {
        int w = t.getShort("Width") & 0xFFFF, h = t.getShort("Height") & 0xFFFF, l = t.getShort("Length") & 0xFFFF;
        CompoundTag paletteTag;
        byte[] data;
        if (t.contains("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag b = t.getCompound("Blocks");
            paletteTag = b.getCompound("Palette");
            data = b.getByteArray("Data");
        } else {
            paletteTag = t.getCompound("Palette");
            data = t.getByteArray("BlockData");
        }
        Map<Integer, BlockState> palette = new java.util.HashMap<>();
        for (String key : paletteTag.getAllKeys()) palette.put(paletteTag.getInt(key), parseState(key, unknown));
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        int i = 0, index = 0;
        while (i < data.length && index < w * h * l) {
            int value = 0, shift = 0, b;
            do { // varint
                if (i >= data.length) throw new IOException("broken block data");
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            int y = index / (w * l), rem = index % (w * l), z = rem / w, x = rem % w;
            BlockState s = palette.get(value);
            if (s != null) out.put(new BlockPos(x, y, z), s);
            index++;
        }
        return out;
    }

    /** Vanilla structure block file. */
    private static Map<BlockPos, BlockState> structure(CompoundTag t, int[] unknown) {
        ListTag pal = t.contains("palette", Tag.TAG_LIST) ? t.getList("palette", Tag.TAG_COMPOUND)
                : t.getList("palettes", Tag.TAG_LIST).getList(0);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < pal.size(); i++) palette.add(readState(pal.getCompound(i), unknown));
        ListTag size = t.getList("size", Tag.TAG_INT);
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        // structure files leave out air they didn't save; treat the whole box as "air unless listed"
        for (int x = 0; x < size.getInt(0); x++)
            for (int y = 0; y < size.getInt(1); y++)
                for (int z = 0; z < size.getInt(2); z++) out.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        ListTag list = t.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            int st = b.getInt("state");
            BlockState s = st >= 0 && st < palette.size() ? palette.get(st) : null;
            BlockPos p = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
            if (s == null) out.remove(p);
            else if (s.is(Blocks.STRUCTURE_VOID)) out.remove(p); // "don't care" cells
            else out.put(p, s);
        }
        return out;
    }

    /** Litematica: one or more regions with bit-packed palette indices. */
    private static Map<BlockPos, BlockState> litematic(CompoundTag root, int[] unknown) {
        CompoundTag regions = root.getCompound("Regions");
        Map<BlockPos, BlockState> raw = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (String key : regions.getAllKeys()) {
            CompoundTag r = regions.getCompound(key);
            CompoundTag pos = r.getCompound("Position"), size = r.getCompound("Size");
            int sx = size.getInt("x"), sy = size.getInt("y"), sz = size.getInt("z");
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            int ox = pos.getInt("x") + (sx < 0 ? sx + 1 : 0);
            int oy = pos.getInt("y") + (sy < 0 ? sy + 1 : 0);
            int oz = pos.getInt("z") + (sz < 0 ? sz + 1 : 0);
            ListTag pal = r.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < pal.size(); i++) palette.add(readState(pal.getCompound(i), unknown));
            long[] arr = r.getLongArray("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long mask = (1L << bits) - 1;
            for (int y = 0; y < ay; y++)
                for (int z = 0; z < az; z++)
                    for (int x = 0; x < ax; x++) {
                        long idx = ((long) y * az + z) * ax + x;
                        long start = idx * bits;
                        int si = (int) (start >> 6), ei = (int) ((start + bits - 1) >> 6), off = (int) (start & 63);
                        if (ei >= arr.length) continue;
                        long v = si == ei ? (arr[si] >>> off) & mask : ((arr[si] >>> off) | (arr[ei] << (64 - off))) & mask;
                        BlockState s = v < palette.size() ? palette.get((int) v) : null;
                        if (s == null) continue;
                        BlockPos p = new BlockPos(ox + x, oy + y, oz + z);
                        raw.put(p, s);
                        minX = Math.min(minX, p.getX());
                        minY = Math.min(minY, p.getY());
                        minZ = Math.min(minZ, p.getZ());
                    }
        }
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : raw.entrySet())
            out.put(e.getKey().offset(-minX, -minY, -minZ), e.getValue());
        return out;
    }

    /** Where schematics live: .minecraft/schematics (same folder Litematica uses). */
    public static Path folder() {
        Path dir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("schematics");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir;
    }

    public static List<Path> list() {
        List<Path> out = new ArrayList<>();
        List<Path> dirs = List.of(folder(),
                net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config/worldedit/schematics"));
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue;
            try (var s = Files.walk(d, 3)) {
                s.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".schem") || n.endsWith(".litematic") || n.endsWith(".nbt") || n.endsWith(".schematic");
                }).forEach(out::add);
            } catch (IOException ignored) {}
        }
        return out;
    }

    /** Find a schematic by (part of) its file name. */
    public static Path find(String query) {
        Path direct = Path.of(query);
        if (direct.isAbsolute() && Files.isRegularFile(direct)) return direct;
        String q = query.toLowerCase();
        Path partial = null;
        for (Path p : list()) {
            String n = p.getFileName().toString().toLowerCase();
            String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
            if (n.equals(q) || base.equals(q)) return p;
            if (partial == null && n.contains(q)) partial = p;
        }
        return partial;
    }
}
