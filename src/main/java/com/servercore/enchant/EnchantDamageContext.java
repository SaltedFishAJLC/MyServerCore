package com.servercore.enchant;

public final class EnchantDamageContext {

    private static final ThreadLocal<Boolean> SECONDARY_DAMAGE = ThreadLocal.withInitial(() -> false);

    private EnchantDamageContext() {
    }

    public static boolean isSecondaryDamage() {
        return SECONDARY_DAMAGE.get();
    }

    public static void runAsSecondaryDamage(Runnable runnable) {
        boolean old = SECONDARY_DAMAGE.get();
        SECONDARY_DAMAGE.set(true);
        try {
            runnable.run();
        } finally {
            SECONDARY_DAMAGE.set(old);
        }
    }
}
