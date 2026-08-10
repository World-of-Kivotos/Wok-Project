package com.kivotos.armorer.armor;

/** 插板伤害公式的单一权威。只处理传入的一个伤害段，不重建 TaCZ 完整弹丸伤害。 */
public final class PlateArmorMath {

    private PlateArmorMath() {
    }

    public static double reduceSegment(double amount, double protectionRate) {
        requireAmount(amount);
        requireRate(protectionRate);
        return amount * (1.0D - protectionRate);
    }

    /** D = X - min(X,T) * G；超过 T 的部分不受 G 保护。 */
    public static double reduceWithPressureCapacity(double amount, double generalProtection, double capacity) {
        requireAmount(amount);
        requireRate(generalProtection);
        if (capacity < 0.0D) {
            throw new IllegalArgumentException("pressure capacity must be non-negative: " + capacity);
        }
        return amount - Math.min(amount, capacity) * generalProtection;
    }

    private static void requireAmount(double amount) {
        if (amount < 0.0D || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("damage amount must be finite and non-negative: " + amount);
        }
    }

    private static void requireRate(double rate) {
        if (rate < 0.0D || rate >= 1.0D || !Double.isFinite(rate)) {
            throw new IllegalArgumentException("protection rate must be finite and in [0,1): " + rate);
        }
    }
}

