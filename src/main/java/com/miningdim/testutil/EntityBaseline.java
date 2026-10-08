package com.miningdim.testutil;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 取样盒的"进场基线": 先记下盒里已有的实体, 之后只认新出现的。
 *
 * 存在理由: 数世界里的实体或掉落物时, 盒里的东西不一定是本用例造的。
 * <ul>
 *   <li>全库共用的 {@code empty} 模板只有 1x1x1, 原版因此把用例按 5 格 (同排) / 7 格 (换排) 的间距排开,
 *       开跑前只清本格 x[-2,+3]、z[-3,+3] 的范围。取样盒稍大一点就罩进了隔壁用例。</li>
 *   <li>一轮之内格子不复用, 别的用例留下的掉落物和带 AI 的生物在本轮余下时间里没人清, 会走进来。</li>
 *   <li>runGameTestServer 复用 run/world, 上一轮停在清场范围外的残留会原样进入这一轮。</li>
 * </ul>
 * 所以按绝对数量断言的用例会随批次顺序与存档状态随机变红。用法: 在触发被测动作之前 {@link #capture},
 * 断言时只看 {@link #fresh}, finally 里 {@link #discardFresh} 把自己造出来的收走。
 */
public final class EntityBaseline<T extends Entity> {

    private final ServerLevel level;
    private final Class<T> type;
    private final AABB box;
    private final Set<UUID> known;

    private EntityBaseline(ServerLevel level, Class<T> type, AABB box, Set<UUID> known) {
        this.level = level;
        this.type = type;
        this.box = box;
        this.known = known;
    }

    /** 记下此刻取样盒里已有的该类实体。 */
    public static <T extends Entity> EntityBaseline<T> capture(ServerLevel level, Class<T> type, AABB box) {
        Set<UUID> known = new HashSet<>();
        for (T entity : level.getEntitiesOfClass(type, box)) {
            known.add(entity.getUUID());
        }
        return new EntityBaseline<>(level, type, box, known);
    }

    /** 基线之后才出现在取样盒里的实体。 */
    public List<T> fresh() {
        return level.getEntitiesOfClass(type, box, entity -> !known.contains(entity.getUUID()));
    }

    /** 基线之后才出现、且满足条件的实体。 */
    public List<T> fresh(Predicate<? super T> filter) {
        return level.getEntitiesOfClass(type, box,
                entity -> !known.contains(entity.getUUID()) && filter.test(entity));
    }

    /** 收走基线之后新出现的实体, 不留给后面的用例和下一轮。放 finally。 */
    public void discardFresh() {
        for (T entity : fresh()) {
            entity.discard();
        }
    }
}
