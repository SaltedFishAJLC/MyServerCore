package com.servercore.manager;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.servercore.passive.EquipmentSetRegistry;
import com.servercore.passive.PassiveAbilityRegistry;
import com.servercore.passive.PassiveSnapshotService;

public class AccessoryManager {

    private static AccessoryManager instance;
    private final Plugin plugin;

    private final NamespacedKey KEY_ACCESSORIES;
    private final NamespacedKey KEY_TALISMAN_BAG;
    private final NamespacedKey KEY_IMPRINT;

    public AccessoryManager(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_ACCESSORIES = new NamespacedKey(plugin, "accessories_data");
        this.KEY_TALISMAN_BAG = new NamespacedKey(plugin, "talisman_bag_data");
        this.KEY_IMPRINT = new NamespacedKey(plugin, "imprint_data");
        instance = this;
    }

    public static AccessoryManager getInstance() {
        return instance;
    }

    /**
     * 将 ItemStack 数组极速序列化为字节流
     */
    private byte[] serializeItems(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            
            dataOutput.close();
            return outputStream.toByteArray();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to serialize items!");
            e.printStackTrace();
            return new byte[0];
        }
    }

    /**
     * 从字节流反序列化为 ItemStack 数组
     */
    private ItemStack[] deserializeItems(byte[] data) {
        if (data == null || data.length == 0) return new ItemStack[0];
        
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            
            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[length];
            
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            
            dataInput.close();
            return items;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to deserialize items!");
            e.printStackTrace();
            return new ItemStack[0];
        }
    }

    /**
     * 保存 4个饰品槽 的数据到玩家 PDC
     */
    public void saveAccessories(Player player, ItemStack[] items) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(KEY_ACCESSORIES, PersistentDataType.BYTE_ARRAY, serializeItems(items));
    }

    /**
     * 加载 4个饰品槽 的数据
     */
    public ItemStack[] loadAccessories(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(KEY_ACCESSORIES, PersistentDataType.BYTE_ARRAY)) {
            byte[] data = pdc.get(KEY_ACCESSORIES, PersistentDataType.BYTE_ARRAY);
            ItemStack[] items = deserializeItems(data);
            if (items.length == 4) {
                if (ensureInstanceIds(items)) {
                    saveAccessories(player, items);
                }
                return items;
            }
            
            // 补偿或修补数组大小
            ItemStack[] fixed = new ItemStack[4];
            System.arraycopy(items, 0, fixed, 0, Math.min(items.length, 4));
            ensureInstanceIds(fixed);
            saveAccessories(player, fixed);
            return fixed;
        }
        return new ItemStack[4];
    }

    public void saveImprint(Player player, ItemStack item) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (item == null || item.getType().isAir()) {
            pdc.remove(KEY_IMPRINT);
            return;
        }
        ItemStack stored = item.clone();
        stored.setAmount(1);
        ensureItemInstanceId(stored);
        pdc.set(KEY_IMPRINT, PersistentDataType.BYTE_ARRAY, serializeItems(new ItemStack[]{stored}));
    }

    public ItemStack loadImprint(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        byte[] data = pdc.get(KEY_IMPRINT, PersistentDataType.BYTE_ARRAY);
        ItemStack[] items = deserializeItems(data);
        if (items.length == 0 || items[0] == null || items[0].getType().isAir()) {
            return null;
        }
        ItemStack item = items[0];
        item.setAmount(1);
        PDCManager pdcManager = PDCManager.getInstance();
        String before = pdcManager == null || item.getItemMeta() == null ? null
                : item.getItemMeta().getPersistentDataContainer()
                .get(pdcManager.KEY_ITEM_INSTANCE_ID, PersistentDataType.STRING);
        ensureItemInstanceId(item);
        if (before == null || before.isBlank()) {
            saveImprint(player, item);
        }
        return item;
    }

    /**
     * 保存 护符包 数据到玩家 PDC
     */
    public void saveTalismanBag(Player player, ItemStack[] items) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(KEY_TALISMAN_BAG, PersistentDataType.BYTE_ARRAY, serializeItems(items));
    }

    /**
     * 加载 护符包 数据
     * @param expectedSize 护符包容量 (方便后续动态扩容)
     */
    public ItemStack[] loadTalismanBag(Player player, int expectedSize) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(KEY_TALISMAN_BAG, PersistentDataType.BYTE_ARRAY)) {
            byte[] data = pdc.get(KEY_TALISMAN_BAG, PersistentDataType.BYTE_ARRAY);
            ItemStack[] items = deserializeItems(data);
            
            if (items.length == expectedSize) {
                if (ensureInstanceIds(items)) {
                    saveTalismanBag(player, items);
                }
                return items;
            }
            
            // 动态扩容或缩减适配
            ItemStack[] fixed = new ItemStack[expectedSize];
            System.arraycopy(items, 0, fixed, 0, Math.min(items.length, expectedSize));
            ensureInstanceIds(fixed);
            saveTalismanBag(player, fixed);
            return fixed;
        }
        return new ItemStack[expectedSize];
    }

    /**
     * Returns the subset of talismans whose stats and passive abilities are active.
     * One registered item id may appear only once, and one talisman family contributes
     * only its highest-rarity/highest-priority member.
     */
    public ItemStack[] loadActiveTalismans(Player player) {
        return resolveTalismans(player).activeItems().toArray(ItemStack[]::new);
    }

    public TalismanResolution resolveTalismans(Player player) {
        ItemStack[] contents = loadTalismanBag(player, 54);
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        if (registry == null) {
            return new TalismanResolution(List.of(), Map.of());
        }

        Map<String, TalismanCandidate> byFamily = new LinkedHashMap<>();
        Map<Integer, String> suppressed = new LinkedHashMap<>();
        Map<String, Integer> firstItemSlot = new LinkedHashMap<>();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            CustomItemRegistry.CustomItemDefinition definition = registry.getDefinition(registry.getItemId(item));
            if (definition == null || !definition.accessoryType().equalsIgnoreCase("TALISMAN")) {
                suppressed.put(slot, "不是已注册的护符");
                continue;
            }

            String itemId = definition.id();
            Integer duplicateSlot = firstItemSlot.putIfAbsent(itemId, slot);
            if (duplicateSlot != null) {
                suppressed.put(slot, "相同护符已存在于第 " + (duplicateSlot + 1) + " 格");
                continue;
            }

            String family = definition.talismanFamily().isBlank() ? "item:" + itemId : definition.talismanFamily();
            TalismanCandidate candidate = new TalismanCandidate(slot, item, definition);
            TalismanCandidate existing = byFamily.get(family);
            if (existing == null) {
                byFamily.put(family, candidate);
                continue;
            }

            int comparison = compareTalisman(candidate, existing);
            if (comparison > 0) {
                suppressed.put(existing.slot(), "被同系列更高稀有度或优先级护符压制");
                byFamily.put(family, candidate);
            } else {
                suppressed.put(slot, "被同系列更高稀有度或优先级护符压制");
            }
        }

        List<TalismanCandidate> ordered = new ArrayList<>(byFamily.values());
        ordered.sort(java.util.Comparator.comparingInt(TalismanCandidate::slot));
        return new TalismanResolution(
                ordered.stream().map(TalismanCandidate::item).toList(),
                Map.copyOf(suppressed)
        );
    }

    public boolean isRegisteredTalisman(ItemStack item) {
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        if (registry == null || item == null || item.getType().isAir()) {
            return false;
        }
        CustomItemRegistry.CustomItemDefinition definition = registry.getDefinition(registry.getItemId(item));
        return definition != null && definition.accessoryType().equalsIgnoreCase("TALISMAN");
    }

    public boolean isImprintEligible(ItemStack item) {
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        if (registry == null || item == null || item.getType().isAir()) {
            return false;
        }
        CustomItemRegistry.CustomItemDefinition definition = registry.getDefinition(registry.getItemId(item));
        if (definition == null) {
            return false;
        }
        boolean declared = definition.imprintEligible() || definition.accessoryType().equalsIgnoreCase("IMPRINT");
        if (!declared) {
            return false;
        }
        PassiveAbilityRegistry passiveRegistry = PassiveAbilityRegistry.getInstance();
        boolean hasPassive = definition.abilities().stream()
                .filter(ability -> ability.trigger().equals("PASSIVE"))
                .map(ability -> passiveRegistry == null ? null : passiveRegistry.get(ability.id()))
                .anyMatch(passive -> passive != null
                        && PassiveSnapshotService.isHandlerImplemented(passive.handler()));
        EquipmentSetRegistry setRegistry = EquipmentSetRegistry.getInstance();
        boolean validSetPiece = !definition.setId().isBlank()
                && !definition.setPieceId().isBlank()
                && setRegistry != null
                && setRegistry.get(definition.setId()) != null;
        return hasPassive || validSetPiece;
    }

    public String ensureItemInstanceId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null || item.getItemMeta() == null) {
            return "";
        }
        var meta = item.getItemMeta();
        String existing = meta.getPersistentDataContainer().get(pdc.KEY_ITEM_INSTANCE_ID, PersistentDataType.STRING);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(pdc.KEY_ITEM_INSTANCE_ID, PersistentDataType.STRING, created);
        item.setItemMeta(meta);
        return created;
    }

    private boolean ensureInstanceIds(ItemStack[] items) {
        boolean changed = false;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null || items == null) {
            return false;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getItemMeta() == null) {
                continue;
            }
            String before = item.getItemMeta().getPersistentDataContainer()
                    .get(pdc.KEY_ITEM_INSTANCE_ID, PersistentDataType.STRING);
            ensureItemInstanceId(item);
            changed |= before == null || before.isBlank();
        }
        return changed;
    }

    private int compareTalisman(TalismanCandidate left, TalismanCandidate right) {
        int rarity = Integer.compare(left.definition().rarity().ordinal(), right.definition().rarity().ordinal());
        if (rarity != 0) {
            return rarity;
        }
        int priority = Integer.compare(left.definition().talismanPriority(), right.definition().talismanPriority());
        if (priority != 0) {
            return priority;
        }
        return -left.definition().id().toLowerCase(Locale.ROOT)
                .compareTo(right.definition().id().toLowerCase(Locale.ROOT));
    }

    public record TalismanResolution(List<ItemStack> activeItems, Map<Integer, String> suppressedSlots) {
    }

    private record TalismanCandidate(
            int slot,
            ItemStack item,
            CustomItemRegistry.CustomItemDefinition definition
    ) {
    }
}
