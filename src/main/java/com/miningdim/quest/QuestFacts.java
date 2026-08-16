package com.miningdim.quest;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一次"世界里发生了什么"的事实载体 —— 事件层与目标层之间的唯一契约。
 *
 * 事件钩子 ({@link QuestEventHooks} / {@link QuestTaczHooks}) 只负责把 Forge/TaCZ 的原始事件翻译成本类的 record,
 * 目标 ({@link QuestObjective}) 只认本类, 两侧互不 import 对方的依赖。这条边界有两个硬理由:
 *
 * 1. TaCZ 是 compileOnly 的本地 jar, 服务器可能没装。{@link GunKill} 只持 {@link ResourceLocation} 与
 *    {@link String}, 不含任何 {@code com.tacz.*} 类型, 因此目标层在 TaCZ 缺失时照样能 classload, 不会把
 *    "没装枪械 mod" 变成 NoClassDefFoundError 崩服 (审计 F061 就是这类问题)。
 * 2. 加一种新事件来源不需要动目标接口的方法签名 —— 只需新增一个 record 与一条钩子。
 *
 * 用 sealed interface 而非无约束接口: 目标实现里的 {@code instanceof} 分支得到编译期穷尽性支撑, 新增事实类型
 * 时编译器能指出所有需要复核的地方。全部实现为本文件内的嵌套 record, 故省略 permits 子句。
 */
public sealed interface QuestFacts {

    /** 触发本次事实的玩家 (始终是服务端实例; 事件钩子已过滤逻辑端)。 */
    ServerPlayer player();

    /**
     * 玩家击杀了一只生物 (原版 {@code LivingDeathEvent}, 伤害来源实体为该玩家)。
     *
     * 不区分武器 —— 枪械击杀另走 {@link GunKill} (它携带枪械专有事实)。同一次开枪击杀会<b>同时</b>产生
     * 本事实与 {@link GunKill}, 这是刻意的: "击杀 10 只僵尸"的日常任务不应该因为玩家用枪而不计数。
     */
    record EntityKill(ServerPlayer player, LivingEntity victim) implements QuestFacts {
    }

    /** 玩家破坏了一个方块 (原版 {@code BlockEvent.BreakEvent}, 未被取消)。 */
    record BlockMine(ServerPlayer player, BlockState state) implements QuestFacts {
    }

    /** 玩家与村民/流浪商人完成了一次交易 (Forge {@code TradeWithVillagerEvent})。 */
    record VillagerTrade(ServerPlayer player, AbstractVillager villager) implements QuestFacts {
    }

    /**
     * 玩家用 TaCZ 枪械击杀了一只生物 (TaCZ {@code EntityKillByGunEvent})。
     *
     * 字段全部取自 TaCZ 的权威判定, 无自研几何近似 —— {@code headshot} 就是 TaCZ 自己的
     * {@code isHeadShot()} (它按配置的头部 AABB + 延迟补偿盒算), {@code distance} 是击杀瞬间射手与目标的
     * 直线距离。TaCZ 1.1.8 的事件层只暴露"头/非头"一个部位布尔, 没有四肢枚举 ({@code GunDamageSourcePart}
     * 是穿甲/非穿甲, 不是身体部位), 故本 record 不含腿部/躯干字段。
     *
     * @param gunType TaCZ 枪械分类字符串 (对应 {@code GunTabType}: pistol / sniper / rifle / shotgun / smg /
     *                rpg / mg)。<b>可为 null</b>: TaCZ 资源索引缺失该枪 id 时无法解析分类。null 是"未知"而非
     *                "无", 限定枪型的目标遇 null 一律不计入 (见 {@link com.miningdim.quest.objective.GunKillObjective}),
     *                不限枪型的目标照常计入 —— 索引缺失不该让"用枪械击杀"这种事实凭空消失。
     * @param damage  本次致命伤害量 (TaCZ {@code getBaseDamage()})
     */
    record GunKill(ServerPlayer player, LivingEntity victim, ResourceLocation gunId, String gunType,
                   boolean headshot, double distance, float damage) implements QuestFacts {
    }

    /**
     * 玩家击发了一次 TaCZ 枪械 (TaCZ {@code GunShootEvent})。对应塔科夫"倾泻火力"类任务。
     *
     * 记的是<b>击发</b>而非命中 (主控 2026-08-16 定, 取塔科夫原味)。判据之所以仍然安全, 是因为成本是子弹本身
     * —— 子弹由军火商生产, 打出去就没了, 所以这条任务是弹药 sink 兼军火商需求源, 而不是零成本的无限动作。
     *
     * 霰弹枪一次击发只产生一条本事实 (TaCZ 的 GunShootEvent 按扣扳机发, 不按弹丸发), 故"打出 200 发"对霰弹枪
     * 与步枪是同一口径。
     *
     * @param gunType 同 {@link GunKill#gunType()}, 可为 null (TaCZ 索引缺失)
     */
    record GunShot(ServerPlayer player, ResourceLocation gunId, String gunType) implements QuestFacts {
    }

    /**
     * 玩家从矿洞成功撤离 (主动经 {@code /mining leave} 或面板离开, 且本次进入未死亡)。对应塔科夫"撤离点"类任务。
     *
     * <b>死亡被抬出去不算撤离</b> —— 这是这条判据全部意义所在。同理掉线也不算。
     *
     * @param difficulty  本次进入的矿洞难度档
     * @param dwellTicks  本次进入的停留时长 (tick)。发出本事实前调用方已过停留门槛; 带上它是为了让目标可以
     *                    再加更严的时长要求 (如"单次驻留 20 分钟以上的撤离")。
     */
    record MiningExtraction(ServerPlayer player, com.miningdim.core.Difficulty difficulty, long dwellTicks)
            implements QuestFacts {
    }

    /**
     * 玩家上交了一批物品 (塔科夫"寻物上交")。
     *
     * 与其它事实不同, 本事实<b>不是世界事件的翻译, 而是玩家主动动作的结果</b>: 由 {@code /quest turnin} 在
     * 确认目标确实要这件物品、并且已经从背包扣掉之后发出。顺序不能反 —— 先扣后记, 扣不掉就不发。
     *
     * @param item  上交的物品
     * @param count 本次实际扣掉的个数 (已按剩余需求裁剪, 不会多扣)
     */
    record ItemTurnIn(ServerPlayer player, net.minecraft.world.item.Item item, int count) implements QuestFacts {
    }
}
