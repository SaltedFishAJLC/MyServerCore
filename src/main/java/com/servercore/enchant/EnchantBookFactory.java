package com.servercore.enchant;

import com.servercore.manager.EnchantManager;
import com.servercore.manager.ItemFormatManager;
import com.servercore.manager.PDCManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class EnchantBookFactory {

    private EnchantBookFactory() {
    }

    public static ItemStack createBook(EnchantDefinition definition, int level) {
        if (definition == null) {
            return null;
        }

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            ItemMeta meta = book.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(
                        pdc.KEY_ITEM_ORIGINAL_NAME,
                        PersistentDataType.STRING,
                        definition.display() + " Enchant Book"
                );
                meta.getPersistentDataContainer().set(
                        pdc.KEY_ITEM_RARITY,
                        PersistentDataType.STRING,
                        itemRarity(definition.rarity()).name()
                );
                book.setItemMeta(meta);
            }
        }

        EnchantManager enchantManager = EnchantManager.getInstance();
        if (enchantManager != null) {
            enchantManager.addCustomEnchantRaw(book, definition.id(), level);
        }

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(book, true);
        }
        return book;
    }

    private static ItemFormatManager.Rarity itemRarity(EnchantRarity rarity) {
        return switch (rarity) {
            case COMMON -> ItemFormatManager.Rarity.COMMON;
            case UNCOMMON -> ItemFormatManager.Rarity.UNCOMMON;
            case RARE -> ItemFormatManager.Rarity.RARE;
            case ULTIMATE -> ItemFormatManager.Rarity.LEGENDARY;
        };
    }
}
