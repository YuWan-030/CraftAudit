package cn.alini.craftaudit.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * 自动注册表扫描，支持原版+模组物品方块本地化名美化
 */
public class NameLocalization {
    private static final Map<String, String> ITEM_MAP = new HashMap<>();
    private static final Map<String, String> BLOCK_MAP = new HashMap<>();

    public static void scanRegistries() {
        // 扫描所有物品
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            String id = ForgeRegistries.ITEMS.getKey(item).toString(); // 例如 minecraft:diamond
            // 物品本地名
            String name = item.getName(ItemStack.EMPTY).getString(); // FIX: 应为 ItemStack.EMPTY
            ITEM_MAP.put(id, name);
        }
        // 扫描所有方块
        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            String id = ForgeRegistries.BLOCKS.getKey(block).toString();
            String name = block.getName().getString();
            BLOCK_MAP.put(id, name);
        }
    }

    public static String itemName(String id) {
        return ITEM_MAP.getOrDefault(id, id);
    }

    public static String blockName(String id) {
        return BLOCK_MAP.getOrDefault(id, id);
    }

    public static String localize(String id) {
        String v = ITEM_MAP.get(id);
        if (v != null) return v;
        v = BLOCK_MAP.get(id);
        if (v != null) return v;
        return id;
    }
}