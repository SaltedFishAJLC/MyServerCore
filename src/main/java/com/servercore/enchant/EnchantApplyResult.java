package com.servercore.enchant;

public record EnchantApplyResult(boolean success, String message) {

    public static EnchantApplyResult ok() {
        return new EnchantApplyResult(true, "");
    }

    public static EnchantApplyResult fail(String message) {
        return new EnchantApplyResult(false, message == null ? "" : message);
    }
}
