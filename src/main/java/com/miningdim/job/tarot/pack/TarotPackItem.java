package com.miningdim.job.tarot.pack;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.TarotRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkHooks;

/**
 * 卡包物品 (TarotReader spec 第七章)。右键 {@link #use} 服务端权威开包 (if !level.isClientSide), 客户端只回
 * success 触发挥手动画。开包前经 {@link IEconomyService} 扣费 (普通/高级=信用点, 闪耀=青辉石), 每日限购并入
 * UTC 翻日体系 (tryChargeDaily)。开出的牌经 {@link ItemHandlerHelper#giveItemToPlayer} 给物, 一律盖 ownerUUID。
 *
 * 闪耀包不直接给牌, 开出后打开自选 GUI ({@link ShinyPackSelectMenu}) 让玩家选一张 SSR (spec 第七章)。
 *
 * 货币扣费经 {@link EconomyServices#economyService()} 定位器 (项目既定服务定位器范式; 原 TarotEconomyHooks
 * 静态 bind seam 无任何 bind 调用方, 已移除悬空 seam): 未注入即开包是装配缺陷, 在此 use 边界自然抛
 * IllegalStateException 暴露 (异常纪律: 不静默掩盖)。
 */
public final class TarotPackItem extends Item {

    /** 每日限购计数键 (并入 economy UTC 翻日; spec 第十章)。 */
    private static final String DAILY_KEY_PACK = "tarot_pack";

    private final PackKind kind;

    public TarotPackItem(Properties properties, PackKind kind) {
        super(properties.stacksTo(64));
        this.kind = kind;
    }

    public PackKind kind() {
        return kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }

        // 闪耀包: 扣费与消耗都推迟到选牌成功那一刻 (在 ShinyPackSelectMenu.clickMenuButton)。仅打开自选 GUI;
        // 玩家 ESC 不选则青辉石与包都不损失 (修复 sink 无对价: 关界面=零损失)。
        if (kind == PackKind.SHINY) {
            // 远程 menu (IForgeMenuType) 必须用带 buf writer 的 openScreen; 本 menu 无附加字段, 写空 buf。
            NetworkHooks.openScreen(serverPlayer, new ShinyPackSelectMenu.Provider(), buf -> { });
            return InteractionResultHolder.success(stack);
        }

        // 普通/高级: 信用点扣费 (含每日限购) -> 开包 -> 消耗 1 包 (原子: 扣费失败不开包不消耗)。
        if (!chargeCreditPack(serverPlayer)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.miningdim.tarot.pack.cannot_afford"), true);
            return InteractionResultHolder.fail(stack);
        }
        openCreditPack(serverPlayer);
        stack.shrink(1);
        return InteractionResultHolder.consume(stack);
    }

    /** 普通/高级扣信用点 (含每日限购; spec 第十章)。闪耀包不走此路 (青辉石, 选牌时扣)。 */
    private boolean chargeCreditPack(ServerPlayer player) {
        IEconomyService eco = EconomyServices.economyService();
        return switch (kind) {
            case COMMON -> eco.tryChargeDaily(player, Currency.CREDIT, TarotConfig.PRICE_COMMON_PACK.get(),
                    DAILY_KEY_PACK, dailyPackCap());
            case ADVANCED -> eco.tryChargeDaily(player, Currency.CREDIT, TarotConfig.PRICE_ADVANCED_PACK.get(),
                    DAILY_KEY_PACK, dailyPackCap());
            case SHINY -> throw new IllegalStateException("shiny pack must charge AZURE at selection, not here");
        };
    }

    private static long dailyPackCap() {
        return TarotConfig.DAILY_PACK_LIMIT.get();
    }

    /** 服务端开普通/高级包 (RNG 权威)。重复牌转出的碎片一并给物 (spec 第七章)。 */
    private void openCreditPack(ServerPlayer player) {
        PackGachaService gacha = TarotRuntime.gacha();
        switch (kind) {
            case COMMON -> giveResult(player, gacha.openCommon(player, player.getRandom()));
            case ADVANCED -> {
                PackGachaService.OpenResult result = gacha.openAdvanced(player, player.getRandom());
                giveResult(player, result);
                // 派生包: 就地再开等量高级包 (期望 E<1 收敛; spec 第七章), 并入本次产物。
                for (int i = 0; i < result.derivedPacks(); i++) {
                    giveResult(player, gacha.openAdvanced(player, player.getRandom()));
                }
            }
            default -> throw new IllegalStateException("openCreditPack called for non-credit pack: " + kind);
        }
    }

    /** 给一次开包结果: 真牌逐张给物, 重复牌转出的碎片合并成一堆给物 (spec 第七章重复转碎片)。 */
    private static void giveResult(ServerPlayer player, PackGachaService.OpenResult result) {
        for (ItemStack card : result.cards()) {
            ItemHandlerHelper.giveItemToPlayer(player, card);
        }
        if (result.shardRefund() > 0) {
            ItemHandlerHelper.giveItemToPlayer(player,
                    com.miningdim.job.tarot.craft.TarotCraftService.makeShards(result.shardRefund()));
        }
    }

    /**
     * 闪耀包选牌成功后扣青辉石并消耗 1 个闪耀包 (在 {@link ShinyPackSelectMenu#clickMenuButton} 调用)。
     * 原子: 先校验持有闪耀包 -> 扣青辉石 -> 都成功才消耗一个包。任一不过返回 false 且无副作用 (扣费失败不消耗包)。
     *
     * @return true 已扣费并消耗 1 包 (可发牌); false 无包或余额不足 (无副作用)
     */
    public static boolean chargeAndConsumeShiny(ServerPlayer player) {
        ItemStack pack = findShinyPack(player);
        if (pack == null) {
            return false; // 无闪耀包 (界面残留/被丢弃): 不发牌。
        }
        IEconomyService eco = EconomyServices.economyService();
        if (!eco.tryCharge(player, Currency.AZURE, TarotConfig.PRICE_SHINY_PACK_AZURE.get())) {
            return false; // 青辉石不足: 不扣不消耗。
        }
        pack.shrink(1);
        return true;
    }

    /** 在玩家背包 (含副手) 找一个闪耀包堆; 无则 null。 */
    private static ItemStack findShinyPack(ServerPlayer player) {
        for (ItemStack s : player.getInventory().items) {
            if (isShinyPack(s)) {
                return s;
            }
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (isShinyPack(s)) {
                return s;
            }
        }
        return null;
    }

    private static boolean isShinyPack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof TarotPackItem pack && pack.kind == PackKind.SHINY;
    }
}
