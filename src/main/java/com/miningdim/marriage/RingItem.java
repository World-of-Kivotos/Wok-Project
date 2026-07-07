package com.miningdim.marriage;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 结婚/订婚戒指 Item (结婚系统 spec 第三章; NBT 盖章身份防伪, 仿塔罗 {@link com.miningdim.job.tarot.TarotCardItem}
 * 的 ownerUUID 写法)。两个独立 Item 实例 (engagement / wedding), 由 {@link #engagement} 区分基础态: 订婚戒指与
 * 结婚戒指各自一个 registry 物品 (简化双态切换 —— 典礼时回收订婚戒指、发放结婚戒指, 不在同一 Item 内改 displayId)。
 *
 * NBT 盖章 (spec 第三章 / 第七章防倒卖):
 *  - spouseUUID  (持戒者的配偶 UUID; appendHoverText 显双方身份, 防把戒指倒卖给小号白嫖婚姻福利)
 *  - marriageId  (关系 id; 与 MarriageRegistry 对账)
 *  - holderName  (持戒者显示名快照)
 *  - spouseName  (配偶显示名快照)
 *  - weddingDay  (典礼时的 overworld game time; 结婚戒指才有)
 *  - officiantId (可选证婚人 UUID; spec 第三章 PENDING, 无证婚人则不写)
 *
 * stacksTo(1): 戒指是身份凭证, 不同 NBT 本就不堆叠, 单枚杜绝歧义 (同塔罗牌)。
 */
public final class RingItem extends Item {

    private static final String K_SPOUSE_UUID = "SpouseUUID";
    private static final String K_MARRIAGE_ID = "MarriageId";
    private static final String K_HOLDER_NAME = "HolderName";
    private static final String K_SPOUSE_NAME = "SpouseName";
    private static final String K_WEDDING_DAY = "WeddingDay";
    private static final String K_OFFICIANT = "OfficiantUUID";

    /** true=订婚戒指 (尚未办典礼); false=结婚戒指 (典礼后)。 */
    private final boolean engagement;

    public RingItem(Properties properties, boolean engagement) {
        super(properties.stacksTo(1));
        this.engagement = engagement;
    }

    public boolean isEngagement() {
        return engagement;
    }

    /**
     * 造一枚订婚戒指 (买戒指入口; spec 第二章 /marriage buyring)。订婚戒指尚无配偶绑定 (购买时还没确定伴侣),
     * 故只是空白订婚戒指; 双方身份在典礼 ({@link MarriageEngine}) 时盖到结婚戒指上。
     */
    public static ItemStack createEngagement(Item engagementRing) {
        return new ItemStack(engagementRing);
    }

    /**
     * 造一枚盖好双方身份的结婚戒指 (典礼成功唯一入口; spec 第三章)。holder/spouse 显名快照写入 NBT 供 hover 显身份。
     *
     * @param weddingRing 结婚戒指 registry 物品
     * @param holder      持戒者 UUID
     * @param holderName  持戒者显示名
     * @param spouse      配偶 UUID
     * @param spouseName  配偶显示名
     * @param marriageId  关系 id (与 MarriageRegistry 对账)
     * @param weddingDay  典礼 game time
     * @param officiant   证婚人 UUID (可空; spec 第三章可选)
     */
    public static ItemStack createWedding(Item weddingRing, UUID holder, String holderName,
                                          UUID spouse, String spouseName,
                                          long marriageId, long weddingDay, UUID officiant) {
        if (holder == null || spouse == null) {
            throw new IllegalArgumentException("wedding ring holder/spouse UUID must not be null");
        }
        ItemStack stack = new ItemStack(weddingRing);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(K_SPOUSE_UUID, spouse);
        tag.putLong(K_MARRIAGE_ID, marriageId);
        tag.putString(K_HOLDER_NAME, holderName);
        tag.putString(K_SPOUSE_NAME, spouseName);
        tag.putLong(K_WEDDING_DAY, weddingDay);
        if (officiant != null) {
            tag.putUUID(K_OFFICIANT, officiant);
        }
        return stack;
    }

    /** 该戒指盖章的配偶 UUID; 未盖章 (空白订婚戒指/创造直给) 返回 null。 */
    public static UUID spouseUUID(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(K_SPOUSE_UUID)) {
            return null;
        }
        return tag.getUUID(K_SPOUSE_UUID);
    }

    /** 该戒指盖章的关系 id; 未盖章返回 -1 (与 {@code IMiningPlayerData.NO_MARRIAGE} 同值)。 */
    public static long marriageId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(K_MARRIAGE_ID)) {
            return -1L;
        }
        return tag.getLong(K_MARRIAGE_ID);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        if (engagement) {
            tooltip.add(Component.translatable("tooltip.miningdim.marriage.engagement_ring")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            tooltip.add(Component.translatable("tooltip.miningdim.marriage.engagement_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(K_MARRIAGE_ID)) {
            // 未盖章的结婚戒指 (异常态/创造直给): 显占位, 不暴露假身份。
            tooltip.add(Component.translatable("tooltip.miningdim.marriage.wedding_ring")
                    .withStyle(ChatFormatting.GOLD));
            return;
        }
        String holderName = tag.getString(K_HOLDER_NAME);
        String spouseName = tag.getString(K_SPOUSE_NAME);
        tooltip.add(Component.translatable("tooltip.miningdim.marriage.wedding_ring")
                .withStyle(ChatFormatting.GOLD));
        // 显双方身份 (spec 第三/七章: 防倒卖戒指给小号白嫖, hover 暴露 holder<->spouse 绑定关系)。
        tooltip.add(Component.translatable("tooltip.miningdim.marriage.bond", holderName, spouseName)
                .withStyle(ChatFormatting.GRAY));
    }
}
