package com.miningdim.district.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * 回执体积预算 (设计文档 14.3)。派发器会把超过 32767 字符的回执换成 RESPONSE_TOO_LARGE, 而契约里的列表都没有分页,
 * 服务端必须自己控制体积。
 *
 * 用法: 先把必需部分 (含空的列表与值为 false 的截断标记) 造成一个骨架, 以骨架的长度开账; 再按"新的在前"逐条往列表里
 * 加, 每条先量出序列化后的长度, 装不下就停, 由调用方把对应的截断标记置为 true。
 *
 * 一旦有一条装不下, 本预算即视为用尽, 之后的一切加入都失败: 否则后面一个列表里恰好更短的条目还能挤进去, 同一份回执
 * 就会出现"前面的列表截断了, 后面的列表反倒是全的"这种不可预测的形状。
 */
final class ResponseBudget {

    /** 总预算: 给派发器的回执信封与 32767 上限之间留出余量。 */
    static final int RESPONSE_BUDGET_CHARS = 30_000;

    /** 与回执用的是同一口径 (serializeNulls), 量出来的长度才等于真正写出去的长度。 */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final int limit;
    private int used;
    private boolean exhausted;

    private ResponseBudget(int limit, int used) {
        this.limit = limit;
        this.used = used;
    }

    /** 以骨架 (必需部分) 的长度开账。 */
    static ResponseBudget startingWith(JsonElement skeleton) {
        return new ResponseBudget(RESPONSE_BUDGET_CHARS, sizeOf(skeleton));
    }

    /** 测试用: 指定总预算。 */
    static ResponseBudget startingWith(JsonElement skeleton, int limit) {
        return new ResponseBudget(limit, sizeOf(skeleton));
    }

    /**
     * 把一条加进列表; 装不下 (或预算已用尽) 时不加, 返回 false。每条另算一个逗号。
     */
    boolean tryAdd(JsonArray target, JsonElement item) {
        if (exhausted) {
            return false;
        }
        int size = sizeOf(item) + 1;
        if (used + size > limit) {
            exhausted = true;
            return false;
        }
        target.add(item);
        used += size;
        return true;
    }

    boolean exhausted() {
        return exhausted;
    }

    static int sizeOf(JsonElement element) {
        return GSON.toJson(element).length();
    }
}
