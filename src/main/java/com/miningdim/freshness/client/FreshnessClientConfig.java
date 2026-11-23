package com.miningdim.freshness.client;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 客户端显示配置 (规格「客户端显示」的开关一节): {@code miningdim-freshness-client.toml}。
 *
 * 纯客户端、只影响画什么:
 * <ul>
 *   <li>{@code slotNumbers}: 格子角落那行数字 —— {@code ALWAYS} / {@code UNDER_24H} (默认) / {@code OFF};</li>
 *   <li>{@code slotBar}: 顶部那条保鲜条 (默认开);</li>
 *   <li>{@code hotbarReminder}: 快捷栏上方那一次提醒 (默认开, 每小时最多一次)。</li>
 * </ul>
 *
 * 默认值刻意保守: 数字默认只在 24 小时以内出现 —— 满背包都写着 "47h" 是噪音, 而"快烂了"才需要一眼看见。
 */
public final class FreshnessClientConfig {

    private FreshnessClientConfig() {
    }

    /** 数字显示档。 */
    public enum Numbers {
        /** 一直显示。 */
        ALWAYS,
        /** 只在剩不到 24 小时时显示 (默认)。 */
        UNDER_24H,
        /** 不显示。 */
        OFF
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<Numbers> SLOT_NUMBERS;
    public static final ForgeConfigSpec.BooleanValue SLOT_BAR;
    public static final ForgeConfigSpec.BooleanValue HOTBAR_REMINDER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("freshnessClient");
        SLOT_NUMBERS = builder.comment("Show the remaining-time number in the slot corner: ALWAYS / UNDER_24H / OFF")
                .defineEnum("slotNumbers", Numbers.UNDER_24H);
        SLOT_BAR = builder.comment("Draw the freshness bar on top of the slot")
                .define("slotBar", true);
        HOTBAR_REMINDER = builder.comment("Remind above the hotbar once per hour when something is about to rot")
                .define("hotbarReminder", true);
        builder.pop();
        SPEC = builder.build();
    }

    public static boolean slotBarEnabled() {
        return SLOT_BAR.get();
    }

    public static boolean hotbarReminderEnabled() {
        return HOTBAR_REMINDER.get();
    }

    /** 这一格该不该显示数字 (腐烂的一律显示: "已腐烂"必须看得见, 与 UNDER_24H 无关)。 */
    public static boolean slotNumbersVisible(long remainingMinutes) {
        return switch (SLOT_NUMBERS.get()) {
            case ALWAYS -> true;
            case OFF -> false;
            case UNDER_24H -> remainingMinutes <= 24L * 60L;
        };
    }
}
