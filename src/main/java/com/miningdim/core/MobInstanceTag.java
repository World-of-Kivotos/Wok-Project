package com.miningdim.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;

/**
 * 怪物所属矿洞实例的持久化标记 (设计文档 10.5 step5)。
 *
 * 这是"这只怪算不算某个实例的刷怪额度"的<b>唯一判据</b>, 也是实例计数 ({@link InstanceState#liveMobs()})
 * 的不变量锚点: <b>带标记且在世界里 ⟺ 在计数里</b>。计数的增减由 MobPressureSystem 挂在实体进出世界的那对
 * 事件上按本标记判定, 因此凡是要占额度的刷怪路径 (压力系统主动刷怪、陷阱引擎的身后苦力怕) 都必须在实体
 * <b>入场之前</b>打上标记 —— 晚一步, 入场事件就看不到标记, 这只怪永远不计数。
 *
 * 放在 core 而不是各自子系统里: 压力子系统与陷阱子系统都往同一个 liveMobs 里记账, 标记键若各持一份字面量,
 * 两边写的键一旦分叉, 表现是"陷阱刷的怪永远销不掉账" —— 正是 F030 那一类只增不减的账本漂移。
 *
 * 用 PersistentData 而非自定义 capability: 标记要随实体存盘并跨区块卸载/重载存活 (重载回来的怪要能被重新
 * 计上), PersistentData 天然满足且零注册成本。
 */
public final class MobInstanceTag {

    /** NBT 键 (namespaced 防与其他 mod 撞键)。 */
    public static final String KEY = MiningConstants.MODID + ":instance";

    private MobInstanceTag() {
    }

    /** 打标记 (必须在实体加入世界之前调用, 见类注释)。 */
    public static void mark(Mob mob, long instanceId) {
        mob.getPersistentData().putLong(KEY, instanceId);
    }

    /** 读标记; 未标记返回 null (调用方据此判定"不占任何实例额度")。 */
    public static Long instanceIdOf(Mob mob) {
        CompoundTag pd = mob.getPersistentData();
        return pd.contains(KEY) ? pd.getLong(KEY) : null;
    }

    /** 是否带标记 (用于实体查询的谓词, 免去装箱)。 */
    public static boolean isTagged(Mob mob) {
        return mob.getPersistentData().contains(KEY);
    }
}
