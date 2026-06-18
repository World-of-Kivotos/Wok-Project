package com.miningdim.job.tarot;

import com.miningdim.job.tarot.card.TarotCardData;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 单一塔罗卡牌 Item (TarotReader spec 第三章: 不做 220 个独立 Item)。NBT 三键 + 绑定:
 *  - cardId   (int 0-21, {@link TarotArcana})
 *  - quality  (int ordinal, {@link TarotQuality})
 *  - orientation (boolean, true 正位/false 逆位)
 *  - ownerUUID (绑定; spec 第十章: 用牌校验 ownerUUID==使用者, 倒卖来的牌打不出效果)
 *
 * stacksTo(1) (不同 NBT 本就不堆叠, 单张杜绝歧义)。{@link #use} 服务端权威: 校验 owner/等级门控/GCD/每卡CD ->
 * 应用效果 -> 消耗 -> 结算经验; 客户端只回 success 触发挥手动画 (spec 第七章双端坑同理)。
 */
public final class TarotCardItem extends Item {

    private static final String K_CARD_ID = "CardId";
    private static final String K_QUALITY = "Quality";
    private static final String K_ORIENTATION = "Upright";
    private static final String K_OWNER = "OwnerUUID";

    public TarotCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** 构造一张盖好三键 + ownerUUID 的牌 (开包/合成产出唯一入口; spec 第七/八/十章)。 */
    public static ItemStack create(Item cardItem, int cardId, TarotQuality quality, boolean upright, UUID owner) {
        if (cardId < 0 || cardId >= TarotArcana.COUNT) {
            throw new IllegalArgumentException("cardId out of range [0,21]: " + cardId);
        }
        if (owner == null) {
            throw new IllegalArgumentException("card owner UUID must not be null (binding is mandatory, spec 10)");
        }
        ItemStack stack = new ItemStack(cardItem);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt(K_CARD_ID, cardId);
        tag.putInt(K_QUALITY, quality.ordinal());
        tag.putBoolean(K_ORIENTATION, upright);
        tag.putUUID(K_OWNER, owner);
        return stack;
    }

    public static int cardId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(K_CARD_ID)) {
            throw new IllegalStateException("tarot card stack missing CardId NBT");
        }
        return tag.getInt(K_CARD_ID);
    }

    public static TarotQuality quality(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(K_QUALITY)) {
            throw new IllegalStateException("tarot card stack missing Quality NBT");
        }
        return TarotQuality.byOrdinal(tag.getInt(K_QUALITY));
    }

    public static boolean upright(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(K_ORIENTATION)) {
            throw new IllegalStateException("tarot card stack missing Upright NBT");
        }
        return tag.getBoolean(K_ORIENTATION);
    }

    /** 该牌的绑定 owner; 无 owner 键返回 null (脏牌/创造模式直给的牌, use 会因 owner 不匹配拒绝)。 */
    public static UUID owner(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(K_OWNER)) {
            return null;
        }
        return tag.getUUID(K_OWNER);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // 客户端只回 success 触发挥手; 真正应用在服务端 (spec 双端权威)。
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.pass(stack);
        }
        boolean played = TarotPlayHandler.tryPlay(serverLevel, serverPlayer, stack, hand);
        if (played) {
            return InteractionResultHolder.consume(stack);
        }
        // 未打出 (owner 不符/等级不够/CD 中): 不消耗、不动画消费, 返回 fail。
        return InteractionResultHolder.fail(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(K_CARD_ID)) {
            return;
        }
        TarotArcana arcana = TarotArcana.byId(tag.getInt(K_CARD_ID));
        TarotQuality quality = TarotQuality.byOrdinal(tag.getInt(K_QUALITY));
        boolean upright = tag.getBoolean(K_ORIENTATION);

        tooltip.add(Component.translatable("tooltip.miningdim.tarot.arcana." + arcana.id())
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.miningdim.tarot.quality." + quality.id())
                .withStyle(qualityColor(quality)));
        tooltip.add(Component.translatable(upright
                        ? "tooltip.miningdim.tarot.orientation.upright"
                        : "tooltip.miningdim.tarot.orientation.reversed")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.tarot.effect." + arcana.id()
                        + (quality == TarotQuality.SHINY ? ".shiny" : (upright ? ".upright" : ".reversed")))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static ChatFormatting qualityColor(TarotQuality quality) {
        return switch (quality) {
            case R -> ChatFormatting.WHITE;
            case SR -> ChatFormatting.AQUA;
            case SSR -> ChatFormatting.LIGHT_PURPLE;
            case UR -> ChatFormatting.GOLD;
            case SHINY -> ChatFormatting.YELLOW;
        };
    }

    /** 该牌当前 datapack 效果表 (供 use handler 取 CD 分档/效果列表)。 */
    public static TarotCardData dataFor(ItemStack stack) {
        return TarotRuntime.cardLoader().get(TarotArcana.byId(cardId(stack)));
    }
}
