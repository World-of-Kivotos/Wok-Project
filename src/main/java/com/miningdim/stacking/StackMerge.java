package com.miningdim.stacking;

import com.miningdim.champion.MiningChampions;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体合并核心 (需求规格 FR-1)。无状态纯逻辑 (静态方法 + 入参实体列表), 故可被 GameTest 直接驱动 (不依赖 tick 调度),
 * 亦被 {@link StackingSystem} 的周期扫描按区块本地候选调用。所有操作须在服务端主线程 (NFR-5): 合并会 discard 实体、
 * 改 NBT, 不可并发。
 *
 * 合并语义:
 *  - 候选准入 ({@link #canStack}): 白名单式 —— 只有 {@link #STACKABLE_TYPES} 四种 (决策 D1) 才可能参与堆叠, 其余
 *    LivingEntity (含玩家/村民/盔甲架/僵尸骷髅/自研精英怪) 一律不合并; 通过白名单后再走命名/驯服/Boss/装备/blacklist
 *    二次过滤 (FR-1.1 / C-4)。旧版曾是黑名单式 (唯一类型闸 instanceof LivingEntity, 靠排除项收口), 致玩家被
 *    discard 成幽灵号 (F004)、村民/盔甲架/铁傀儡的装备与交易表被永久销毁 (F005)、矿洞刷怪 (F002) 与自研精英怪
 *    (F018/F037) 全部被误并 —— 白名单准入从根上堵死这类误伤, 而非依赖排除项覆盖穷举。
 *  - 候选准入 vs 结算合法性 ({@link #canMerge} vs {@link #canStack}, 三独立复核者共同指出的回归修复): canStack 只
 *    回答 "这是否仍是本系统认可的合法堆叠个体", 被 {@link StackDeath}/{@link StackPassive}/{@link StackBreed}/
 *    {@link StackSplit} 四个结算 handler 共用作准入判据; canMerge 额外叠加两条【仅合并期】瞬时态闸 (被拴住 /
 *    拆分保护期未到)。这两道闸严禁混进 canStack —— 被拴住或保护期内的堆叠只是 "暂时不该被吸收进别的堆叠",
 *    不代表 "不再是合法堆叠"; 若混进 canStack, 一个被拴住的 N 只堆叠死亡时 {@code StackDeath.onLivingDeath} 会在
 *    准入判据处直接 return, 只掉原版那 1 份, 其余 N-1 只的战利品/经验凭空消失, 且同一状态下也无法剪毛/挤奶/
 *    繁殖/拆分自救。只有 {@link #mergeCandidates} (合并候选筛选) 才应使用 canMerge。
 *  - 配对 ({@link #mergeCandidates}): 按 {@link StackMatchKey} 分组 (同 type+年龄+变体), 组内按半径就近合并,
 *    数量求和封顶 max_stack_size, 超出另起新堆叠 (FR-1.3)。
 *  - 属性保留 (FR-1.6): 合并保留 "幸存实体" 本体不动 —— 其自定义最大生命/移速/装备/药水效果天然不丢; 被并实体仅
 *    把堆叠数贡献给幸存者后 discard。即 "幸存者继承数量, 不继承被并者属性" (堆叠语义: 一个代表 N 个同种个体)。
 *  - 显示名 (FR-1.4): 幸存者堆叠数 >1 时 setCustomName("<本地化实体名> xN") + setCustomNameVisible(true)。
 *    命名实体 (canStack 已排除) 不参与, 故不会覆盖玩家 name tag。命名闸靠 {@link #isSelfAppliedLabel} 区分
 *    "本系统自己打的 xN 标记" 与 "玩家真实命名", 而非靠 {@link StackData#hasStackData} (findings 8 回归修复):
 *    {@link StackSplit#splitOne} 会给拆出的单个体写 StackSize=1 (拆分保护期用的合法数据), 若命名闸仍以
 *    hasStackData 为准, 玩家给拆出个体命名后该闸会永久失效。
 */
public final class StackMerge {

    private StackMerge() {
    }

    /**
     * 合并候选白名单 (主控决策 D1)。硬编码而非 config 键: D1 是产品决策而非运维旋钮, 且 F004 的根因正是
     * "默认放行一切 LivingEntity + 靠配置收口" —— 任何允许服主往外扩白名单的旋钮都会重新打开同一类风险
     * (放进一个非低价值农场动物的类型, 就重新暴露一次装备/交易表/幽灵号级别的资产损毁)。要扩白名单须改代码,
     * 走 code review, 不做成运行期可调。
     */
    private static final Set<EntityType<?>> STACKABLE_TYPES =
            Set.of(EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP, EntityType.COW);

    /** 该实体类型是否在合并候选白名单内 (决策 D1)。 */
    public static boolean isStackableType(Entity entity) {
        return STACKABLE_TYPES.contains(entity.getType());
    }

    /**
     * 该实体是否仍是本系统认可的合法堆叠个体 (FR-1.1 / C-4 排除)。白名单 ({@link #isStackableType}) 是第一道闸且在
     * isAlive 之前 —— 本方法被 {@link StackDeath} 对【每一次 LivingDeathEvent】调用 (F094 性能修复点), 白名单前置
     * 可让绝大多数 (非四种白名单动物的) LivingEntity 在触碰 {@link Entity#getPersistentData()} 之前就短路返回,
     * 不必逐个跑 isAlive/isChampion 等后续判定。
     *
     * 本方法是 {@link StackDeath}/{@link StackPassive}/{@link StackBreed}/{@link StackSplit} 四个结算 handler 的
     * 共用准入判据, 严禁在此叠加任何 "合并期专用" 的瞬时态闸 (isLeashed / NoMergeUntil 见 {@link #canMerge})——
     * 那类闸只回答 "现在能不能被吸收进别的堆叠", 与 "这还算不算一个合法堆叠" 是两个问题 (findings 1/3/5 回归修复)。
     *
     * 判定顺序:
     *  1) 类型不在白名单 -> false (决策 D1 主判据)。
     *  2) 未存活 -> false。
     *  3) 自研精英怪 ({@link MiningChampions#isChampion}) -> false: 纵深防御 (F018/F037)。理论上精英怪不落在
     *     四种白名单类型内, 本条闸恒不命中; 但精英怪化是运行期 capability 叠加而非类型变化, 显式判定防止未来
     *     白名单类型扩展时才第一次触发这条本该早就挡住的风险。
     *  4) 命名 (exclusions.named + hasCustomName + !{@link #isSelfAppliedLabel}): 玩家用命名牌命名的实体, 不并入
     *     也不被覆盖名。
     *  5) 驯服 (exclusions.tamed): TamableAnimal.isTame 或 AbstractHorse.isTamed。
     *  6) Boss (exclusions.boss): !canChangeDimensions 的实体即 Boss/特殊 (末影龙/凋灵在原版均 canChangeDimensions=false)。
     *  7) 已装备特殊道具 ({@link #isEquipped}): 目前仅猪的鞍 (findings 9)。
     *  8) blacklist: entity id 在配置黑名单内 (二次过滤用途, 不是准入判据 —— 严禁靠此项收窄白名单已排除的风险)。
     */
    public static boolean canStack(Entity entity) {
        // 只堆 LivingEntity (物品/弹射物/载具不在本规格范围)。
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        if (!isStackableType(entity)) {
            return false;
        }
        if (!entity.isAlive()) {
            return false;
        }
        if (MiningChampions.isChampion((LivingEntity) entity)) {
            return false;
        }
        if (StackingConfig.EXCLUSIONS_NAMED.get() && entity.hasCustomName() && !isSelfAppliedLabel(entity)) {
            return false;
        }
        if (StackingConfig.EXCLUSIONS_TAMED.get() && isTamed(entity)) {
            return false;
        }
        if (StackingConfig.EXCLUSIONS_BOSS.get() && isBoss(entity)) {
            return false;
        }
        if (isEquipped(entity)) {
            return false;
        }
        if (isBlacklisted(entity)) {
            return false;
        }
        return true;
    }

    /**
     * 该实体当前是否可作为下一轮合并的候选者 (合并侧专用判据; findings 1/3/5 修复)。是 {@link #canStack} 的超集,
     * 额外叠加两条【仅合并期】的瞬时态闸: 被拴住与拆分保护期未到。只有 {@link #mergeCandidates} (含
     * {@link StackingSystem} 周期扫描的预过滤) 应调用本方法; 结算 handler 一律用 canStack。
     */
    public static boolean canMerge(Entity entity) {
        if (!canStack(entity)) {
            return false;
        }
        if (entity instanceof Mob mob && mob.isLeashed()) {
            // 被拴住的个体 (FR-5.2 两种拴绳语义共同的前提): 无论 leashMode 是 WHOLE_STACK 还是 SPLIT_ONE, 被拴住
            // 的实体都不该再被吸入别的堆叠; 但它仍是合法堆叠个体, 死亡/被动产出/拆分照常结算 (canStack 不受影响)。
            return false;
        }
        long until = StackData.getNoMergeUntil(entity);
        if (until > 0 && entity.level().getGameTime() < until) {
            // 拆分保护期未到 (FR-5.1): 刚从堆叠拆出的个体在 splitGraceTicks 内不参与重新合并, 给玩家留出把它
            // 牵走的窗口; 保护期内它仍是合法堆叠个体 (通常 size==1, 死亡/被动产出走原版单只语义, 不受此闸影响)。
            return false;
        }
        return true;
    }

    /**
     * 该实体的 CustomName 是否恰好是 {@link #applyLabel} 会生成的那串 (本系统自己打的 "xN" 堆叠标记), 而非玩家用
     * 命名牌起的真实名字。命名排除闸靠此区分, 严禁用 {@link StackData#hasStackData} 代替 (findings 8 回归修复):
     * {@link StackSplit#splitOne} 会给拆出的单个体写 StackSize=1 (拆分保护期用的合法数据, 与 "被本系统命名" 无关),
     * 若命名闸仍以 hasStackData 为准, 玩家给拆出个体命名后该闸会永久失效 —— 保护期一过, 这只被命名的动物会被当成
     * 普通合并候选, 名字要么被下一次 applyLabel 覆写, 要么随 discard 一起销毁。
     */
    private static boolean isSelfAppliedLabel(Entity entity) {
        if (!entity.hasCustomName() || !StackData.hasStackData(entity)) {
            return false;
        }
        int size = StackData.getStackSize(entity);
        if (size <= 1) {
            // applyLabel 对 size<=1 只清名, 从不生成 "xN" 标签 —— size<=1 时任何 CustomName 必是玩家命名。
            return false;
        }
        Component expected = Component.empty().append(entity.getType().getDescription())
                .append(Component.literal(" x" + size));
        Component actual = entity.getCustomName();
        return actual != null && actual.getString().equals(expected.getString());
    }

    /**
     * 该白名单动物是否已装备特殊道具, 若是则不参与堆叠 (findings 9): 目前仅猪的鞍 ({@link Pig#isSaddled()})。
     * discard() (合并时对被并方的操作) 不像原版 {@code die()} 会走 dropEquipment, 被并方的鞍会随 discard 直接从
     * 世界上消失; 装备是玩家投入, 与 tamed/boss 同级排除, 而非在合并时补掉落 (排除范围最小, 且规避
     * "还有哪些未来结算路径也会丢装备" 的穷举负担)。
     */
    private static boolean isEquipped(Entity entity) {
        if (entity instanceof Pig pig) {
            return pig.isSaddled();
        }
        return false;
    }

    private static boolean isTamed(Entity entity) {
        if (entity instanceof TamableAnimal tamable) {
            return tamable.isTame();
        }
        if (entity instanceof AbstractHorse horse) {
            return horse.isTamed();
        }
        return false;
    }

    /** Boss 判据: 原版以 {@code canChangeDimensions()==false} 标识不可跨维度的特殊/Boss (末影龙/凋灵)。 */
    private static boolean isBoss(Entity entity) {
        return !entity.canChangeDimensions();
    }

    private static boolean isBlacklisted(Entity entity) {
        List<? extends String> blacklist = StackingConfig.EXCLUSIONS_BLACKLIST.get();
        if (blacklist.isEmpty()) {
            return false;
        }
        String id = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        return blacklist.contains(id);
    }

    /**
     * 对一组候选实体执行就近合并 (FR-1 主入口, GameTest 与周期扫描共用)。入参列表是 "同一空间局部" (如同一区块)
     * 的实体集合; 本方法内部按 {@link StackMatchKey} 分组, 再在组内按 merge.radius 就近合并。
     *
     * @param candidates 待合并实体 (调用方保证空间局部性; 本方法自行过滤 canMerge)
     * @return 实际被 discard (并入他者) 的实体数 (供测试/诊断断言合并发生)
     */
    public static int mergeCandidates(List<? extends Entity> candidates) {
        int hRadius = StackingConfig.MERGE_RADIUS_HORIZONTAL.get();
        int vRadius = StackingConfig.MERGE_RADIUS_VERTICAL.get();
        int maxStack = StackingConfig.MERGE_MAX_STACK_SIZE.get();

        // 按等价键分组 (同 type+年龄+变体)。用 canMerge (非 canStack): 被拴住 / 拆分保护期内的实体这一刻不该被
        // 吸收进别的堆叠, 但它们仍是合法堆叠个体, 不应从这里的候选池被误伤式剔除后又要求调用方另行保证。
        Map<StackMatchKey, List<Entity>> groups = new HashMap<>();
        for (Entity e : candidates) {
            if (!canMerge(e)) {
                continue;
            }
            groups.computeIfAbsent(StackMatchKey.of(e), k -> new ArrayList<>()).add(e);
        }

        int discarded = 0;
        for (List<Entity> group : groups.values()) {
            discarded += mergeGroup(group, hRadius, vRadius, maxStack);
        }
        return discarded;
    }

    /**
     * 合并单个等价组 (已同 type+年龄+变体)。组内贪心: 以每个 "幸存者" 为锚, 吸收半径内、未满 max 的同组实体,
     * 数量求和封顶 maxStack, 被吸收者 discard; 锚满 max 后跳过该锚 (其溢出部分自然成为下一个独立堆叠, FR-1.3)。
     *
     * @return 本组被 discard 的实体数
     */
    private static int mergeGroup(List<Entity> group, int hRadius, int vRadius, int maxStack) {
        int discarded = 0;
        for (int i = 0; i < group.size(); i++) {
            Entity anchor = group.get(i);
            if (!anchor.isAlive()) {
                continue; // 已在更早一轮被并入他者。
            }
            boolean anchorChanged = false;
            for (int j = i + 1; j < group.size(); j++) {
                Entity other = group.get(j);
                if (!other.isAlive()) {
                    continue;
                }
                int anchorSize = StackData.getStackSize(anchor);
                if (anchorSize >= maxStack) {
                    break; // 锚已满, 其余留作新堆叠 (FR-1.3)。
                }
                if (!withinRadius(anchor, other, hRadius, vRadius)) {
                    continue;
                }
                int otherSize = StackData.getStackSize(other);
                int room = maxStack - anchorSize;
                int moved = Math.min(room, otherSize);
                if (anchor instanceof Sheep anchorSheep && other instanceof Sheep otherSheep) {
                    // findings 2/4/6: 剪毛恢复账 (REGROW_KEY) 是 StackPassive 私有持久化键, 合并核心不碰它就会被
                    // discard 悄悄丢弃 (两个已剪堆叠合并后被并方的恢复账凭空消失, 免冷却二次剪毛)。按 moved/otherSize
                    // 比例搬账, 必须在 anchor/other 的 StackSize 被本轮改写前调用 (读的是合并前的两侧堆叠数)。
                    StackPassive.mergeRegrowLedger(anchorSheep, anchorSize, otherSheep, otherSize, moved);
                }
                StackData.setStackSize(anchor, anchorSize + moved);
                anchorChanged = true;
                if (moved >= otherSize) {
                    // other 整体并入 -> discard。
                    other.discard();
                    discarded++;
                } else {
                    // other 仅部分并入 (锚封顶), 其余留在 other 上成为新堆叠 (FR-1.3)。
                    StackData.setStackSize(other, otherSize - moved);
                }
            }
            if (anchorChanged) {
                applyLabel(anchor);
            }
        }
        return discarded;
    }

    /** 半径判定: 水平 (xz 切比雪夫/欧氏取欧氏更紧) + 垂直独立 (规格水平 5 / 垂直 3)。 */
    private static boolean withinRadius(Entity a, Entity b, int hRadius, int vRadius) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        double dy = Math.abs(a.getY() - b.getY());
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return horiz <= hRadius && dy <= vRadius;
    }

    /**
     * 给堆叠数 >1 的实体打显示名 "<本地化实体名> xN" (FR-1.4)。堆叠数 1 时清掉本系统标记名 (恢复无名)。
     * 命名实体 (canStack 排除) 永不到这里, 故不覆盖玩家 name tag。
     */
    public static void applyLabel(Entity entity) {
        int size = StackData.getStackSize(entity);
        if (size <= 1) {
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
            return;
        }
        Component base = entity.getType().getDescription();
        Component label = Component.empty().append(base).append(Component.literal(" x" + size));
        entity.setCustomName(label);
        entity.setCustomNameVisible(true);
    }
}
