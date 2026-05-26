package com.servercore.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EconomyManager {

    private final DatabaseManager databaseManager;
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();

    public EconomyManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 异步从数据库加载玩家数据到缓存。
     */
    public void loadPlayer(UUID uuid) {
        databaseManager.loadPlayerBalance(uuid).thenAccept(balance -> {
            balances.put(uuid, balance);
        });
    }

    /**
     * 卸载玩家并异步保存到数据库。
     */
    public void saveAndUnloadPlayer(UUID uuid) {
        Long balance = balances.remove(uuid);
        if (balance != null) {
            databaseManager.savePlayerBalanceAsync(uuid, balance);
        }
    }

    /**
     * 将所有在线玩家数据同步保存到数据库（主要用于关服时）。
     */
    public void saveAllSync() {
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            databaseManager.savePlayerBalanceSync(entry.getKey(), entry.getValue());
        }
    }

    public long getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    public void setBalance(UUID uuid, long amount) {
        if (amount < 0) amount = 0;
        balances.put(uuid, amount);
    }

    public boolean hasBalance(UUID uuid, long amount) {
        return getBalance(uuid) >= amount;
    }

    public void addBalance(UUID uuid, long amount) {
        if (amount <= 0) return;
        balances.compute(uuid, (k, currentBalance) -> (currentBalance == null ? 0L : currentBalance) + amount);
    }

    public boolean removeBalance(UUID uuid, long amount) {
        if (amount <= 0) return true;
        
        // 保证原子性扣除
        boolean[] success = new boolean[]{false};
        balances.computeIfPresent(uuid, (k, currentBalance) -> {
            if (currentBalance >= amount) {
                success[0] = true;
                return currentBalance - amount;
            }
            return currentBalance;
        });
        
        return success[0];
    }
}
