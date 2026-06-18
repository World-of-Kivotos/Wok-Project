package com.miningdim.job.engineer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 纳米护甲板与受修护甲的 ItemStack NBT 单一权威读写 (MillenniumEngineer_Mod_DesignSpec 6.3 / 7.4 / 9.2)。
 * 集中所有 NBT 键, 避免散落字面量导致键漂移 (多维 grep 铁律: 改键只需改本类一处)。
 *
 * 两类 NBT:
 *  1. 护甲板 (生产者盖章, 反代练): producerUUID (经验归属) + productionXpPending (取走即清防重复刷)。
 *  2. 受修护甲 (特效状态, 逐件存): 特效集合 (哪几件带何效果) + 护盾每件的剩余次数/再生计时 +
 *     重塑/机能修复无逐件额外状态 (按当前耐久实时判, 故不存 tick)。
 *
 * 全部键收敛在 mod 自有 root tag {@value #ROOT}, 不污染原版/其他 mod 的 NBT 命名空间。
 */
public final class NanoNbt {

    private NanoNbt() {
    }

    /** mod 自有 NBT 根标签 (护甲板与受修护甲都挂在此根下, 隔离原版/他 mod 键)。 */
    private static final String ROOT = "MiningdimNano";

    // ---- 护甲板键 ----
    private static final String K_PRODUCER = "ProducerUUID";
    private static final String K_XP_PENDING = "ProductionXpPending";
    private static final String K_QUALITY = "QualityHits"; // 生产时校准累计品质命中数 (经验/特效杠杆共用)

    // ---- 受修护甲键 ----
    private static final String K_EFFECTS = "Effects";          // ListTag<StringTag> 特效 id 列表
    private static final String K_SHIELD_CHARGES = "ShieldCharges"; // 护盾剩余可生成次数
    private static final String K_SHIELD_REGEN = "ShieldRegenTick";  // 距下次生成的倒计时 tick
    private static final String K_SHIELD_WINDOW = "ShieldWindowTick"; // 当前免疫窗剩余 tick

    private static CompoundTag root(ItemStack stack) {
        return stack.getOrCreateTagElement(ROOT);
    }

    private static Optional<CompoundTag> rootIfPresent(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        return Optional.of(tag.getCompound(ROOT));
    }

    // ============================================================
    // 护甲板: 生产者盖章 (7.4 / 9.2)
    // ============================================================

    /**
     * 给护甲板盖章生产者 UUID + 标记生产经验待结算 + 记录本轮校准品质命中数 (4.2/7.4 品质杠杆: 取出结算经验 +
     * 修甲掷特效概率均据此). qualityHits 与板持久化, 使三条品质杠杆 (产量 / 经验 / 特效概率) 都能还原品质。
     */
    public static void stampProducer(ItemStack plate, UUID producer, boolean xpPending, int qualityHits) {
        CompoundTag tag = root(plate);
        tag.putUUID(K_PRODUCER, producer);
        tag.putBoolean(K_XP_PENDING, xpPending);
        tag.putInt(K_QUALITY, Math.max(0, qualityHits));
    }

    /** 取护甲板生产者 UUID (无则 empty; 调用方据此判经验归属, 不掩盖)。 */
    public static Optional<UUID> producer(ItemStack plate) {
        return rootIfPresent(plate)
                .filter(t -> t.hasUUID(K_PRODUCER))
                .map(t -> t.getUUID(K_PRODUCER));
    }

    /** 生产经验是否待结算 (取出者匹配且为 true 才给经验; 7.4)。 */
    public static boolean isProductionXpPending(ItemStack plate) {
        return rootIfPresent(plate).map(t -> t.getBoolean(K_XP_PENDING)).orElse(false);
    }

    /** 清生产经验待结算位 (取走即清, 防塞回再取重复刷; 7.4 / 9.3)。 */
    public static void clearProductionXpPending(ItemStack plate) {
        rootIfPresent(plate).ifPresent(t -> t.putBoolean(K_XP_PENDING, false));
    }

    /** 取板生产时记录的品质命中数 (无键 -> 0; 经验/特效概率品质杠杆共用; 4.2/7.4/6.1)。 */
    public static int qualityHits(ItemStack plate) {
        return rootIfPresent(plate).map(t -> t.getInt(K_QUALITY)).orElse(0);
    }

    // ============================================================
    // 受修护甲: 特效状态 (6.2 / 6.3, 逐件存)
    // ============================================================

    /** 清空护甲全部纳米特效状态 (再次纳米修复先清旧; 闪耀必清; 6.1)。 */
    public static void clearEffects(ItemStack armor) {
        CompoundTag tag = armor.getTag();
        if (tag == null) {
            return;
        }
        if (tag.contains(ROOT, Tag.TAG_COMPOUND)) {
            CompoundTag r = tag.getCompound(ROOT);
            r.remove(K_EFFECTS);
            r.remove(K_SHIELD_CHARGES);
            r.remove(K_SHIELD_REGEN);
            r.remove(K_SHIELD_WINDOW);
        }
    }

    /** 写入一组特效 (修复掷出时调用); 同时按特效初始化护盾计数/计时。 */
    public static void writeEffects(ItemStack armor, Set<NanoEffect> effects) {
        CompoundTag tag = root(armor);
        ListTag list = new ListTag();
        for (NanoEffect e : effects) {
            list.add(StringTag.valueOf(e.id()));
        }
        tag.put(K_EFFECTS, list);
        if (effects.contains(NanoEffect.SHIELD)) {
            tag.putInt(K_SHIELD_CHARGES, EngineerConfig.SHIELD_MAX_CHARGES.get());
            tag.putInt(K_SHIELD_REGEN, EngineerConfig.SHIELD_REGEN_INTERVAL_TICKS.get());
            tag.putInt(K_SHIELD_WINDOW, 0);
        }
    }

    /** 读护甲当前生效特效集合 (空集 = 无特效); 未知 id 跳过 (NBT 兜底不崩)。 */
    public static EnumSet<NanoEffect> effects(ItemStack armor) {
        EnumSet<NanoEffect> result = EnumSet.noneOf(NanoEffect.class);
        rootIfPresent(armor).ifPresent(r -> {
            if (r.contains(K_EFFECTS, Tag.TAG_LIST)) {
                ListTag list = r.getList(K_EFFECTS, Tag.TAG_STRING);
                for (int i = 0; i < list.size(); i++) {
                    NanoEffect e = NanoEffect.byId(list.getString(i));
                    if (e != null) {
                        result.add(e);
                    }
                }
            }
        });
        return result;
    }

    /** 护甲是否带某特效。 */
    public static boolean hasEffect(ItemStack armor, NanoEffect effect) {
        return effects(armor).contains(effect);
    }

    // ---- 护盾逐件状态 (剩余次数 / 再生倒计时 / 免疫窗剩余) ----

    public static int shieldCharges(ItemStack armor) {
        return rootIfPresent(armor).map(t -> t.getInt(K_SHIELD_CHARGES)).orElse(0);
    }

    public static void setShieldCharges(ItemStack armor, int charges) {
        root(armor).putInt(K_SHIELD_CHARGES, charges);
    }

    public static int shieldRegenTick(ItemStack armor) {
        return rootIfPresent(armor).map(t -> t.getInt(K_SHIELD_REGEN)).orElse(0);
    }

    public static void setShieldRegenTick(ItemStack armor, int tick) {
        root(armor).putInt(K_SHIELD_REGEN, tick);
    }

    public static int shieldWindowTick(ItemStack armor) {
        return rootIfPresent(armor).map(t -> t.getInt(K_SHIELD_WINDOW)).orElse(0);
    }

    public static void setShieldWindowTick(ItemStack armor, int tick) {
        root(armor).putInt(K_SHIELD_WINDOW, tick);
    }
}
