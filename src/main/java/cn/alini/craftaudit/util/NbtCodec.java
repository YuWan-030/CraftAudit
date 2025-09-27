package cn.alini.craftaudit.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Set;

public final class NbtCodec {
    private static final Set<String> ALLOW_PREFIXES = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper",
            "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace",
            "minecraft:shulker_box", "minecraft:sign", "minecraft:hanging_sign",
            "minecraft:skull", "minecraft:spawner", "minecraft:jukebox"
    );
    private static final int MAX_COMPRESSED_NBT_BYTES = 16 * 1024; // 16KB

    private NbtCodec() {}

    public static String stateToString(BlockState state) {
        return String.valueOf(state); // e.g. minecraft:oak_sign[facing=north]
    }

    public static String tryDumpBeNbtBase64(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;

        var typeKey = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
        if (typeKey == null) return null;
        String id = typeKey.toString();
        if (!isAllowed(id)) return null;

        try {
            CompoundTag tag = be.saveWithFullMetadata();
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            byte[] bytes = baos.toByteArray();
            if (bytes.length > MAX_COMPRESSED_NBT_BYTES) return null;
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isAllowed(String id) {
        for (String p : ALLOW_PREFIXES) if (id.startsWith(p)) return true;
        return false;
    }

    public static String buildDataJson(String stateStr, String base64NbtOrNull) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"state\":\"").append(escape(stateStr)).append("\"");
        if (base64NbtOrNull != null) sb.append(",\"nbt\":\"").append(base64NbtOrNull).append("\"");
        sb.append("}");
        return sb.toString();
    }

    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String extractJsonStringField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}