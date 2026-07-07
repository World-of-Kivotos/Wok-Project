package com.miningdim.stacking;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Creeper;

import java.util.Objects;

/**
 * 合并等价键 (需求规格 FR-1.1: 合并须同时满足 同 entity type + 同年龄段 + 同变体维度)。两实体当且仅当其 StackMatchKey
 * {@link #equals} 时才可并入同一堆叠。半径判定 (FR-1.1 的 merge.radius) 不在键内 —— 键只管 "同类同状态", 半径由
 * {@link StackMerge} 在配对时按位置过滤。
 *
 * 三个维度:
 *  - type: 同 {@link EntityType} (羊只能并羊)。
 *  - baby: 年龄段隔离 (FR-1.2)。{@link Mob#isBaby()} 二值 (成年/幼年); 非 Mob 实体恒成年。
 *  - variant: 变体签名。羊毛色 (Sheep.getColor)、苦力怕充能 (Creeper.isPowered)、马花色 (AbstractHorse "Variant"
 *    NBT) 显式判等; 其它实体无已知变体维度时签名为空串 (仅按 type+baby 合并)。
 *
 * 设计取舍: 变体维度采用 "已知子集 + 空兜底", 而非盲序列化全量 NBT —— 全量 NBT 含位置/朝向/UUID/AI 目标等每帧异动
 * 字段, 会令任何两个同种实体永不等键 (合并彻底失效)。故此处只对规格点名的变体 (羊色/充能/马花色) 取关键字段判等,
 * 后续新变体在 {@link #variantSignature} 显式扩展 (YAGNI: 需要哪种写哪种, 不引入反射全扫)。
 */
public final class StackMatchKey {

    private final EntityType<?> type;
    private final boolean baby;
    private final String variant;

    private StackMatchKey(EntityType<?> type, boolean baby, String variant) {
        this.type = type;
        this.baby = baby;
        this.variant = variant;
    }

    /** 从实体抽取其合并等价键。 */
    public static StackMatchKey of(Entity entity) {
        boolean baby = entity instanceof Mob mob && mob.isBaby();
        return new StackMatchKey(entity.getType(), baby, variantSignature(entity));
    }

    /**
     * 变体签名 (规格 FR-1.1 的 "变体维度")。仅对点名变体取关键字段; 其余返回空串。
     * 新变体在此显式扩展 —— 严禁退化为序列化全量 NBT (见类注释)。
     */
    private static String variantSignature(Entity entity) {
        if (entity instanceof Sheep sheep) {
            // 羊毛色: 不同颜色不合并 (剪毛产出按色结算)。getColor 返回 DyeColor, 取 ordinal 稳定。
            return "wool:" + sheep.getColor().getId();
        }
        if (entity instanceof Creeper creeper) {
            // 充能苦力怕与普通苦力怕分堆 (爆炸/掉落不同)。
            return creeper.isPowered() ? "charged:1" : "charged:0";
        }
        if (entity instanceof AbstractHorse horse) {
            // 马花色存于实体 NBT 的 "Variant" int (马/驴/骡共用 AbstractHorse, 仅马有 Variant 子键, 缺键即 0)。
            CompoundTag tag = new CompoundTag();
            horse.addAdditionalSaveData(tag);
            return "horse:" + tag.getInt("Variant");
        }
        return "";
    }

    public EntityType<?> type() {
        return type;
    }

    public boolean baby() {
        return baby;
    }

    public String variant() {
        return variant;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StackMatchKey other)) {
            return false;
        }
        return baby == other.baby && type == other.type && variant.equals(other.variant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, baby, variant);
    }

    @Override
    public String toString() {
        return "StackMatchKey[" + EntityType.getKey(type) + ", baby=" + baby + ", variant='" + variant + "']";
    }
}
