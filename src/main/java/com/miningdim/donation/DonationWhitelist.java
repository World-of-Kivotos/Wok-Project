package com.miningdim.donation;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.GameMasterBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 捐赠箱可收物品的唯一裁决点。界面投入格、管理界面仓库格 (含 Shift 点击)、漏斗等自动化输入全部经过这里,
 * 服务端权威; 客户端槽位的 mayPlace 也调它, 但只是预测, 不被信任。
 *
 * 规则按判定顺序:
 *  1. 只收 {@link BlockItem}: 捐赠箱是给自管区公共工程攒建材的, 工具/武器/矿物锭一律不收。
 *  2. 可直接食用的物品不收: 胡萝卜、马铃薯、甜浆果、发光浆果在原版里是 {@code ItemNameBlockItem}
 *     (放下去是作物), 只看第 1 条会被当成方块收进来。可放置的整块食物 (蛋糕、各食物模组的派与盛宴)
 *     作为物品本身不可食用, 这一条管不到, 由拒收标签兜 (默认只列了原版蛋糕, 模组食物方块按需追加)。
 *  3. 带任何物品 NBT 的一律拒收: 这一条专门堵容器方块 —— 装满东西的潜影盒、带 BlockEntityTag 的箱子,
 *     以及改过名、附过魔的方块。它们会把"只收方块"变成"什么都能夹带"。
 *  4. 创造限定的 {@link GameMasterBlockItem} (命令方块、结构方块、拼图方块) 在代码里硬拒, 不依赖数据包。
 *  5. 数据驱动的拒收标签 {@code miningdim:donation_box_denied}: 默认含捐赠箱自身、潜影盒、矿石 (forge:ores)、
 *     钻石块/绿宝石块/下界合金块/远古残骸、刷怪笼、蛋糕与其余创造限定方块; 服主可用数据包追加。
 *
 * 空栈返回 {@link Verdict#EMPTY}, 不算"接受" —— 调用方要的是"这一栈能不能进仓库", 空栈没有意义。
 */
public final class DonationWhitelist {

    private DonationWhitelist() {
    }

    /** 数据驱动的拒收标签。 */
    public static final TagKey<Item> DENIED_TAG =
            ItemTags.create(new ResourceLocation(MiningConstants.MODID, "donation_box_denied"));

    /** 裁决结果。除 {@link #ACCEPTED} 外都表示拒收, 各值只用于提示与测试定位。 */
    public enum Verdict {
        ACCEPTED,
        EMPTY,
        NOT_A_BLOCK,
        EDIBLE,
        CARRIES_NBT,
        CREATIVE_ONLY,
        DENIED_BY_TAG;

        public boolean accepted() {
            return this == ACCEPTED;
        }
    }

    public static Verdict judge(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Verdict.EMPTY;
        }
        Item item = stack.getItem();
        if (!(item instanceof BlockItem)) {
            return Verdict.NOT_A_BLOCK;
        }
        if (item.isEdible()) {
            return Verdict.EDIBLE;
        }
        if (stack.hasTag()) {
            return Verdict.CARRIES_NBT;
        }
        if (item instanceof GameMasterBlockItem) {
            return Verdict.CREATIVE_ONLY;
        }
        if (stack.is(DENIED_TAG)) {
            return Verdict.DENIED_BY_TAG;
        }
        return Verdict.ACCEPTED;
    }

    public static boolean accepts(ItemStack stack) {
        return judge(stack).accepted();
    }
}
