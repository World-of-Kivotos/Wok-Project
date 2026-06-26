package com.miningdim.stacking;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体合并核心 (需求规格 FR-1)。无状态纯逻辑 (静态方法 + 入参实体列表), 故可被 GameTest 直接驱动 (不依赖 tick 调度),
 * 亦被 {@link StackingSystem} 的周期扫描按区块本地候选调用。所有操作须在服务端主线程 (NFR-5): 合并会 discard 实体、
 * 改 NBT, 不可并发。
 *
 * 合并语义:
 *  - 候选过滤 ({@link #canStack}): 排除命名 (玩家 name tag) / 驯服 / Boss / blacklist (FR-1.1 / C-4)。
 *  - 配对 ({@link #mergeCandidates}): 按 {@link StackMatchKey} 分组 (同 type+年龄+变体), 组内按半径就近合并,
 *    数量求和封顶 max_stack_size, 超出另起新堆叠 (FR-1.3)。
 *  - 属性保留 (FR-1.6): 合并保留 "幸存实体" 本体不动 —— 其自定义最大生命/移速/装备/药水效果天然不丢; 被并实体仅
 *    把堆叠数贡献给幸存者后 discard。即 "幸存者继承数量, 不继承被并者属性" (堆叠语义: 一个代表 N 个同种个体)。
 *  - 显示名 (FR-1.4): 幸存者堆叠数 >1 时 setCustomName("<本地化实体名> xN") + setCustomNameVisible(true)。
 *    命名实体 (canStack 已排除) 不参与, 故不会覆盖玩家 name tag。
 */
public final class StackMerge {

    private StackMerge() {
    }

    /**
     * 该实体是否可参与堆叠 (FR-1.1 / C-4 排除)。读 {@link StackingConfig} 排除开关实时生效。
     *
     * 排除项:
     *  - 命名 (exclusions.named + hasCustomName): 玩家用命名牌命名的实体, 不并入也不被覆盖名。
     *  - 驯服 (exclusions.tamed): TamableAnimal.isTame 或 AbstractHorse.isTamed。
     *  - Boss (exclusions.boss): !canChangeDimensions 的实体即 Boss/特殊 (末影龙/凋灵在原版均 canChangeDimensions=false)。
     *  - blacklist: entity id 在配置黑名单内。
     */
    public static boolean canStack(Entity entity) {
        // 只堆 LivingEntity (物品/弹射物/载具不在本规格范围)。
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        if (!entity.isAlive()) {
            return false;
        }
        if (StackingConfig.EXCLUSIONS_NAMED.get() && entity.hasCustomName() && !StackData.hasStackData(entity)) {
            // 有 CustomName 且非本系统打的堆叠名 -> 视为玩家命名, 排除。已带 StackData 的实体其 CustomName 是
            // 本系统的 "xN" 标记 (canStack 在 setLabel 之前对幸存者重新判定时不应自我排除)。
            return false;
        }
        if (StackingConfig.EXCLUSIONS_TAMED.get() && isTamed(entity)) {
            return false;
        }
        if (StackingConfig.EXCLUSIONS_BOSS.get() && isBoss(entity)) {
            return false;
        }
        if (isBlacklisted(entity)) {
            return false;
        }
        return true;
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
     * @param candidates 待合并实体 (调用方保证空间局部性; 本方法自行过滤 canStack)
     * @return 实际被 discard (并入他者) 的实体数 (供测试/诊断断言合并发生)
     */
    public static int mergeCandidates(List<? extends Entity> candidates) {
        int hRadius = StackingConfig.MERGE_RADIUS_HORIZONTAL.get();
        int vRadius = StackingConfig.MERGE_RADIUS_VERTICAL.get();
        int maxStack = StackingConfig.MERGE_MAX_STACK_SIZE.get();

        // 按等价键分组 (同 type+年龄+变体)。
        Map<StackMatchKey, List<Entity>> groups = new HashMap<>();
        for (Entity e : candidates) {
            if (!canStack(e)) {
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
