package com.miningdim.job.engineer;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * 护甲板生产结算逻辑 (MillenniumEngineer_Mod_DesignSpec 3.2 / 4.2 品质结算)。给定档位 / 输入矿石栈 / 校准品质 /
 * RNG / 生产者 UUID, 计算: 消耗多少矿 + 产出几块板 (含品质 +1、闪耀概率) + 下界合金碎片返还 (闪耀失败)。
 *
 * 矿石消耗与产出为定值/概率 (3.2): 极品 1 锭 -> 2 板; 闪耀 2 锭 -> 概率 1 板 (失败返还碎片); 其余 1 板。
 * 品质结算 (4.2): qualityHits 达阈值时有概率额外 +1 板 (闪耀本就概率产出, 品质 +1 仍适用)。
 *
 * 纯逻辑 + 注入 RandomSource (品质 +1 / 闪耀概率), 无世界引用; GameTest 用固定种子断言确定产出。
 * 护甲板的生产者盖章经 {@link NanoNbt#stampProducer} 由调用方 (BE) 写入产出栈。
 */
public final class NanoProduction {

    private NanoProduction() {
    }

    /** 生产结算结果 (BE 据此扣矿、产板、退下界合金碎片; 不可变)。 */
    public record Result(int oreConsumed, int platesProduced, int scrapRefund) {
    }

    /**
     * 结算一次生产 (调用前已由 BE/Menu 重校三道门 + 矿石足量)。
     *
     * @param tier        目标档 (已通过矿石/等级/机器三门)
     * @param qualityHits 本轮校准累计命中数 (品质条)
     * @param random      RNG (品质 +1 与闪耀概率)
     * @return 结算结果 (消耗矿数 / 产板数 / 下界合金碎片返还数)
     */
    public static Result resolve(NanoTier tier, int qualityHits, RandomSource random) {
        int oreCost = tier.oreCost();

        if (tier.isRadiant()) {
            // 闪耀: 概率产 1 板, 失败返还下界合金碎片 (3.2；概率与数量经 config)。
            boolean success = random.nextDouble() < EngineerConfig.RADIANT_SUCCESS_CHANCE.get();
            if (!success) {
                return new Result(oreCost, 0, EngineerConfig.RADIANT_FAIL_REFUND.get());
            }
            int plates = 1 + bonusPlate(qualityHits, random);
            return new Result(oreCost, plates, 0);
        }

        int plates = tier.outputCount() + bonusPlate(qualityHits, random);
        return new Result(oreCost, plates, 0);
    }

    public static ItemStack makeRadiantFailureRefund(int count) {
        return count <= 0 ? ItemStack.EMPTY : new ItemStack(Items.NETHERITE_SCRAP, count);
    }

    /** 品质达阈值时按概率额外 +1 板 (4.2 产量); 否则 0。 */
    private static int bonusPlate(int qualityHits, RandomSource random) {
        if (qualityHits >= EngineerConfig.CALIBRATION_QUALITY_BONUS_THRESHOLD.get()
                && random.nextDouble() < EngineerConfig.CALIBRATION_BONUS_PLATE_CHANCE.get()) {
            return 1;
        }
        return 0;
    }

    /**
     * 造一块盖好生产者章 + 经验待结算位 + 本轮品质命中数的护甲板栈 (BE 产出时调用; 7.4 谁产谁得, 4.2 品质杠杆)。
     * qualityHits 写入板, 供取出结算经验 (品质越高经验越高) 与修甲掷特效 (品质越高概率越高) 还原品质。
     */
    public static ItemStack makePlate(NanoTier tier, int count, UUID producer, int qualityHits) {
        ItemStack plate = new ItemStack(ModEngineerItems.plate(tier).get(), count);
        NanoNbt.stampProducer(plate, producer, true, qualityHits);
        return plate;
    }
}
