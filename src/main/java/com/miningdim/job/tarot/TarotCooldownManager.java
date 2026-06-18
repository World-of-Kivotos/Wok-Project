package com.miningdim.job.tarot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 用牌冷却管理 (TarotReader spec 9.5)。两层 CD:
 *  1. 全局 GCD (任意两次用牌之间, 防一帧连甩);
 *  2. 每卡 CD (按卡分档 utility/buff/combat, 闪耀按牌表分钟级)。
 *
 * 时钟用 {@link MinecraftServer#getTickCount()} 全局服务器 tick (spec 第十二章红线: 不用 level.getGameTime,
 * 全局时钟跨维度一致; 反复进出矿洞维度时 CD 不漂移)。
 *
 * 内存态 (UUID -> 截止 tick map); 登出清理由 {@link #clear(UUID)}。强增益 "同类不可续期" 不在此 (那是效果
 * 应用层的去重, 见 {@link TarotEffectEngine}); 本类只管时间闸。全部主线程访问, 无需并发保护。
 */
public final class TarotCooldownManager {

    /** 每卡 CD 分档 (spec 9.5); datapack "cooldownCategory" 字段值映射。SHINY 不走分档 (读牌表 ticks)。 */
    public enum Category {
        UTILITY("utility"),
        BUFF("buff"),
        COMBAT("combat");

        private final String id;

        Category(String id) {
            this.id = id;
        }

        public static Category byId(String id) {
            for (Category c : values()) {
                if (c.id.equals(id)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown tarot cooldown category: " + id);
        }
    }

    /** 玩家级 GCD 截止 tick。 */
    private final Map<UUID, Long> gcdEnd = new HashMap<>();
    /** 玩家级普通 (非闪耀级) 每卡 CD 截止 tick (键: playerUUID -> (cardId -> endTick))。可被女祭司闪耀清空。 */
    private final Map<UUID, Map<Integer, Long>> cardEnd = new HashMap<>();
    /** 玩家级闪耀级牌 CD 截止 tick (键同上)。分钟级 CD, 不在女祭司闪耀可清范围 (spec 9.3 "不含闪耀级")。 */
    private final Map<UUID, Map<Integer, Long>> shinyCardEnd = new HashMap<>();

    /**
     * 校验并占用冷却: 若 GCD 未到或该卡 CD 未到则返回 false (不占用, 用牌被拒); 否则置 GCD 与该卡 CD 截止
     * tick 并返回 true。原子: 任一未通过都不修改状态 (防部分占用)。
     *
     * @param player        使用者
     * @param cardId        卡 id (0-21)
     * @param cardCdTicks   该卡每卡 CD (调用方按品质/分档/闪耀牌表算好传入)
     * @param gcdTicks      全局 GCD (TarotConfig.GCD_TICKS)
     * @param shiny         本次是否以闪耀级触发 (闪耀级 CD 记入独立表, 不被女祭司闪耀清空; spec 9.3)
     * @return true 通过并已占用; false 仍在冷却 (用牌被拒)
     */
    public boolean tryUse(ServerPlayer player, int cardId, int cardCdTicks, int gcdTicks, boolean shiny) {
        long now = currentTick(player);
        UUID id = player.getUUID();

        Long g = gcdEnd.get(id);
        if (g != null && now < g) {
            return false;
        }
        // 该卡 CD 在普通表或闪耀表任一未到即拒 (同一 cardId 的两级 CD 互相独立计时, 取严)。
        if (cardCooling(cardEnd, id, cardId, now) || cardCooling(shinyCardEnd, id, cardId, now)) {
            return false;
        }

        // 通过: 占用 GCD 与该卡 CD (闪耀级记入独立表)。
        gcdEnd.put(id, now + gcdTicks);
        Map<UUID, Map<Integer, Long>> target = shiny ? shinyCardEnd : cardEnd;
        target.computeIfAbsent(id, k -> new HashMap<>()).put(cardId, now + cardCdTicks);
        return true;
    }

    private static boolean cardCooling(Map<UUID, Map<Integer, Long>> table, UUID id, int cardId, long now) {
        Map<Integer, Long> cards = table.get(id);
        if (cards == null) {
            return false;
        }
        Long c = cards.get(cardId);
        return c != null && now < c;
    }

    /** 清空某卡的非闪耀级 CD (按卡逐个清的入口; 闪耀级 CD 不在可清范围)。 */
    public void clearCard(UUID player, int cardId) {
        Map<Integer, Long> cards = cardEnd.get(player);
        if (cards != null) {
            cards.remove(cardId);
        }
    }

    /**
     * 清空某玩家全部非闪耀级每卡 CD (女祭司闪耀效果体)。保留 GCD (防连甩) 与闪耀级牌 CD (spec 9.3 明确
     * "清空自身全部塔罗 CD 不含闪耀级"; 闪耀分钟级 CD 不可被此清掉, 否则破坏闪耀冷却平衡)。
     */
    public void clearAllCards(UUID player) {
        cardEnd.remove(player);
    }

    /** 登出清理某玩家全部 CD 状态 (内存态不跨会话; 重连重新计时)。 */
    public void clear(UUID player) {
        gcdEnd.remove(player);
        cardEnd.remove(player);
        shinyCardEnd.remove(player);
    }

    private static long currentTick(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            // ServerPlayer 必然有 server; null 是装配/时序缺陷, 自然抛出暴露 (异常纪律)。
            throw new IllegalStateException("ServerPlayer has no MinecraftServer (cooldown clock unavailable)");
        }
        return server.getTickCount();
    }
}
