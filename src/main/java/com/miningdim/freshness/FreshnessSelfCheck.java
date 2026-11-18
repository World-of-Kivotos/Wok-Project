package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 开服标签自检 (Spoilage_Granary_DesignSpec「安全阀」): 整合包少装一个模组就可能让整张标签加载失败,
 * 结果是腐肉、金苹果被销毁, 或者小麦永远不坏 —— 这两种都会毁掉存档里的东西。
 *
 * 所以每次开服逐条核对下面八项, 有一条不对就**自动转成试运行**并提醒在线 OP:
 * 腐肉 / 蜘蛛眼 / 金苹果 / 蜂蜜瓶必须是豁免, 小麦 / 农夫小麦必须是谷物, 生牛肉必须是生鲜, 面包必须是熟食。
 *
 * 判定器是参数而不是内部直取, 是为了让 GameTest 能喂假的判定器验证「有一条不对就报出来」这条路径本身
 * (真实的标签在测试环境里是对的, 反而验不到失败分支)。
 */
public final class FreshnessSelfCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/freshness");

    /** 农夫小麦按注册 ID 取, 不 import 农夫模块 —— 腐烂底座不认识农夫模块 (登记里没有这条依赖)。 */
    private static final ResourceLocation FARMER_WHEAT = new ResourceLocation(MiningConstants.MODID, "farmer_wheat");

    private FreshnessSelfCheck() {
    }

    /**
     * 逐条核对。
     *
     * @param isExempt   豁免判定 (生产传 {@link FreshnessCategories#isExempt})
     * @param classifier 类别判定 (生产传 {@link FreshnessCategories#categoryOf})
     * @return 不通过的条目说明; 空表示全部通过
     */
    public static List<String> verify(Predicate<ItemStack> isExempt,
                                      Function<ItemStack, Optional<FreshnessCategory>> classifier) {
        List<String> problems = new ArrayList<>();
        requireExempt(problems, isExempt, "腐肉", Items.ROTTEN_FLESH);
        requireExempt(problems, isExempt, "蜘蛛眼", Items.SPIDER_EYE);
        requireExempt(problems, isExempt, "金苹果", Items.GOLDEN_APPLE);
        requireExempt(problems, isExempt, "蜂蜜瓶", Items.HONEY_BOTTLE);

        requireCategory(problems, classifier, "小麦", new ItemStack(Items.WHEAT), FreshnessCategory.GRAIN);
        Item farmerWheat = ForgeRegistries.ITEMS.getValue(FARMER_WHEAT);
        if (farmerWheat == null) {
            problems.add("农夫小麦取不到 (miningdim:farmer_wheat 未注册): 农夫模块没装或注册失败");
        } else {
            requireCategory(problems, classifier, "农夫小麦", new ItemStack(farmerWheat), FreshnessCategory.GRAIN);
        }
        requireCategory(problems, classifier, "生牛肉", new ItemStack(Items.BEEF), FreshnessCategory.FRESH);
        requireCategory(problems, classifier, "面包", new ItemStack(Items.BREAD), FreshnessCategory.COOKED);
        return problems;
    }

    /** 生产入口: 自检 + 有问题就转试运行并提醒 OP。 */
    public static List<String> runOnServerStart(MinecraftServer server) {
        List<String> problems = verify(FreshnessCategories::isExempt, FreshnessCategories::categoryOf);
        if (problems.isEmpty()) {
            LOGGER.info("[miningdim] freshness self-check: 标签自检全部通过 ({} 项)", 8);
            return problems;
        }
        // 有一条不对就退回试运行: 试运行只盖章、不换腐烂物、不拦截, 于是最坏的后果只是统计不准。
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        LOGGER.error("[miningdim] freshness self-check FAILED ({} 项), 总开关已从 {} 退回试运行; "
                        + "逐条原因: {}. 用 /freshness dump 导出「物品 -> 类别」清单人工核对",
                problems.size(), previous, String.join("; ", problems));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(Component.literal("[miningdim] 腐烂系统自检未通过, 已退回试运行: "
                        + String.join("; ", problems)));
            }
        }
        return problems;
    }

    private static void requireExempt(List<String> problems, Predicate<ItemStack> isExempt,
                                      String what, Item item) {
        if (!isExempt.test(new ItemStack(item))) {
            problems.add(what + "必须是豁免 (现在会被当成易腐品)");
        }
    }

    private static void requireCategory(List<String> problems,
                                        Function<ItemStack, Optional<FreshnessCategory>> classifier,
                                        String what, ItemStack stack, FreshnessCategory expected) {
        Optional<FreshnessCategory> actual = classifier.apply(stack);
        if (actual.isEmpty()) {
            problems.add(what + "必须归入" + expected.translationKey() + " (现在不腐烂, 等于永远不坏)");
        } else if (actual.get() != expected) {
            problems.add(what + "必须归入" + expected.translationKey() + " (现在是 " + actual.get().translationKey() + ")");
        }
    }
}
