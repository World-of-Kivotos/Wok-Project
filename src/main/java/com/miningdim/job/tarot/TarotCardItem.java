package com.miningdim.job.tarot;

import com.miningdim.job.tarot.card.TarotCardData;
import com.miningdim.job.tarot.card.TarotCardLoader;
import com.miningdim.job.tarot.client.TarotCardClient;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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
    private static final String K_EFFECT_TOOLTIP = "EffectTooltip";
    private static final String K_EFFECT_TOOLTIP_VERSION = "EffectTooltipVersion";
    /**
     * 牌效说明缓存的格式版本。实际写进 NBT 的版本号还混入了该牌 datapack JSON 的内容哈希
     * ({@link #effectTooltipVersion}), 数据包改了数值, 旧牌下次进玩家背包时就会重写说明 —— 专用服务器的客户端
     * 只读得到这份缓存, 版本号写死时改数值后旧牌会一直显示旧数值。
     */
    private static final int EFFECT_TOOLTIP_FORMAT = 2;

    public TarotCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(TarotCardClient.extension());
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
        // 闪耀不分正逆位 (spec 第六章): 一律按正位生成, 免得卡面倒着画、说明里写"逆位"。
        tag.putBoolean(K_ORIENTATION, quality.displayUpright(upright));
        tag.putUUID(K_OWNER, owner);
        refreshEffectTooltip(stack);
        return stack;
    }

    /**
     * 三个身份键是否齐全<b>且</b>取值落在域内 —— 即 {@link #cardId}/{@link #quality}/{@link #upright} 三个
     * getter 此刻调都不会抛。
     *
     * 为什么需要它: 创造模式直给的裸牌 (见 {@link #owner} 的注释) 是真实场景, 只读展示 (WebUI 物品详情)
     * 点开这样一张牌必须降级成普通物品, 而不是报错。为什么必须留在本类: 三个键名是 private 常量, 外部
     * 另抄一份键名就成了第二份真源。为什么不能只判键存在: {@link #quality} 内的
     * {@link TarotQuality#byOrdinal} 对越界序号照样抛。
     */
    public static boolean hasReadableCardIdentity(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (!hasCardIdentity(tag)) {
            return false;
        }
        int cardId = tag.getInt(K_CARD_ID);
        int qualityOrdinal = tag.getInt(K_QUALITY);
        return cardId >= 0 && cardId < TarotArcana.COUNT
                && qualityOrdinal >= 0 && qualityOrdinal < TarotQuality.values().length;
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
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (!hasCardIdentity(tag)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TarotCardTooltip(cardId(stack), quality(stack), upright(stack)));
        } catch (RuntimeException malformedCard) {
            return Optional.empty();
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (!hasCardIdentity(tag)) {
            return;
        }
        TarotArcana arcana;
        TarotQuality quality;
        boolean upright;
        try {
            arcana = TarotArcana.byId(tag.getInt(K_CARD_ID));
            quality = TarotQuality.byOrdinal(tag.getInt(K_QUALITY));
            upright = tag.getBoolean(K_ORIENTATION);
        } catch (RuntimeException malformedCard) {
            tooltip.add(Component.translatable("tooltip.miningdim.tarot.invalid_data")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        tooltip.add(Component.translatable("tooltip.miningdim.tarot.arcana." + arcana.id())
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.miningdim.tarot.quality." + quality.id())
                .withStyle(style -> style.withColor(TextColor.fromRgb(quality.rgb()))));
        if (quality.hasOrientation()) {
            tooltip.add(Component.translatable(upright
                            ? "tooltip.miningdim.tarot.orientation.upright"
                            : "tooltip.miningdim.tarot.orientation.reversed")
                    .withStyle(ChatFormatting.GRAY));
        }
        appendOwnerLine(tag, level, tooltip);

        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("tooltip.miningdim.tarot.effect.title")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        List<Component> effectLines = cachedEffectTooltip(tag);
        if (effectLines.isEmpty()) {
            // 单机/内置服务器可直接读取已重载的数据；专用服务器会在创建物品或背包 tick 时把同一结果写入 NBT。
            effectLines = liveEffectTooltip(arcana, quality, upright);
        }
        if (effectLines.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.miningdim.tarot.effect.unavailable")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            for (Component line : effectLines) {
                tooltip.add(line.copy().withStyle(ChatFormatting.GRAY));
            }
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        // F073: 只比对 NBT 里的版本号与非空缓存表 (map 查表 + 一次 int 比较), 不反序列化其内容——每张牌每 tick
        // 全量 Gson 解析整份 tooltip JSON 结果只为判空即丢, 是纯浪费。缓存行本身写坏 (JSON 串损坏) 时不靠背包 tick
        // 反复重试, 显示侧已有兜底——appendHoverText 在 cachedEffectTooltip 解析失败返回空表时会回落到
        // liveEffectTooltip, 玩家看到的说明不会因此消失。
        if (!level.isClientSide && !hasUsableEffectTooltip(stack.getTag())) {
            // 兼容更新前已经存在的卡牌, 以及数据包改过数值的旧牌: 进入玩家背包后按当前牌效重写, 由原版物品同步送到客户端。
            refreshEffectTooltip(stack);
        }
    }

    private static boolean hasCardIdentity(CompoundTag tag) {
        return tag != null
                && tag.contains(K_CARD_ID, Tag.TAG_INT)
                && tag.contains(K_QUALITY, Tag.TAG_INT)
                && tag.contains(K_ORIENTATION, Tag.TAG_BYTE);
    }

    /**
     * 当前牌效说明应有的版本号: 格式版本混入该牌 datapack JSON 的内容哈希。牌效表未加载 (客户端、重载中) 时返回 0,
     * 调用方据此不判"过期"—— 拿不到当前数据就无从重写。
     */
    private static int effectTooltipVersion(int cardId) {
        TarotCardLoader loader = TarotRuntime.cardLoader();
        if (!loader.isLoaded() || cardId < 0 || cardId >= TarotArcana.COUNT) {
            return 0;
        }
        int version = EFFECT_TOOLTIP_FORMAT * 31 + loader.contentHash(TarotArcana.byId(cardId));
        return version == 0 ? 1 : version; // 0 留给"未知", 哈希恰好撞上时挪开。
    }

    /** 缓存表非空且版本与当前牌效一致——只查 NBT 结构, 不反序列化内容 (F073 热路径判据)。 */
    private static boolean hasUsableEffectTooltip(CompoundTag tag) {
        if (!hasCardIdentity(tag) || tag.getList(K_EFFECT_TOOLTIP, Tag.TAG_STRING).isEmpty()) {
            return false;
        }
        int expected = effectTooltipVersion(tag.getInt(K_CARD_ID));
        return expected == 0 || tag.getInt(K_EFFECT_TOOLTIP_VERSION) == expected;
    }

    private static void refreshEffectTooltip(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (!hasCardIdentity(tag)) {
            return;
        }
        try {
            TarotArcana arcana = TarotArcana.byId(tag.getInt(K_CARD_ID));
            TarotQuality quality = TarotQuality.byOrdinal(tag.getInt(K_QUALITY));
            List<Component> lines = TarotEffectTooltipFormatter.format(
                    TarotRuntime.cardLoader().get(arcana), quality, tag.getBoolean(K_ORIENTATION));
            if (lines.isEmpty()) {
                return;
            }
            ListTag encoded = new ListTag();
            for (Component line : lines) {
                encoded.add(StringTag.valueOf(Component.Serializer.toJson(line)));
            }
            tag.put(K_EFFECT_TOOLTIP, encoded);
            tag.putInt(K_EFFECT_TOOLTIP_VERSION, effectTooltipVersion(arcana.cardId()));
        } catch (RuntimeException dataNotReadyOrMalformed) {
            // 物品可能在客户端视觉预览或资源重载完成前被构造；缺数据只让说明暂不可用，不能让 tooltip/渲染崩溃。
        }
    }

    /**
     * 绑定行: 本人的牌不打扰; 绑定在别人名下 (买来的/捡到的) 明确写"打不出", 无主牌也写明。只在客户端判定
     * (需要本地玩家 UUID), 服务端调用 (极少见) 不加这一行。
     */
    private static void appendOwnerLine(CompoundTag tag, Level level, List<Component> tooltip) {
        if (level == null || !level.isClientSide) {
            return;
        }
        UUID local = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> TarotCardClient.localPlayerId());
        UUID owner = tag.hasUUID(K_OWNER) ? tag.getUUID(K_OWNER) : null;
        if (local == null || local.equals(owner)) {
            return;
        }
        tooltip.add(Component.translatable(owner == null
                        ? "tooltip.miningdim.tarot.owner.none"
                        : "tooltip.miningdim.tarot.owner.other")
                .withStyle(ChatFormatting.RED));
    }

    private static List<Component> cachedEffectTooltip(CompoundTag tag) {
        if (!hasCardIdentity(tag) || !tag.contains(K_EFFECT_TOOLTIP, Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag encoded = tag.getList(K_EFFECT_TOOLTIP, Tag.TAG_STRING);
        List<Component> lines = new java.util.ArrayList<>(encoded.size());
        for (int i = 0; i < encoded.size(); i++) {
            try {
                Component line = Component.Serializer.fromJson(encoded.getString(i));
                if (line != null) {
                    lines.add(line);
                }
            } catch (RuntimeException malformedCache) {
                return List.of();
            }
        }
        return List.copyOf(lines);
    }

    private static List<Component> liveEffectTooltip(TarotArcana arcana, TarotQuality quality, boolean upright) {
        try {
            return TarotEffectTooltipFormatter.format(TarotRuntime.cardLoader().get(arcana), quality, upright);
        } catch (RuntimeException dataNotReadyOrMalformed) {
            return List.of();
        }
    }

    /** 该牌当前 datapack 效果表 (供 use handler 取 CD 分档/效果列表)。 */
    public static TarotCardData dataFor(ItemStack stack) {
        return TarotRuntime.cardLoader().get(TarotArcana.byId(cardId(stack)));
    }
}
