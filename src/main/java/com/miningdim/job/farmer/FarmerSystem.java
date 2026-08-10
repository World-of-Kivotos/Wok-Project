package com.miningdim.job.farmer;

import com.miningdim.core.Subsystem;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.block.FarmerBlocks;
import com.miningdim.job.farmer.block.FarmerCropBlock;
import com.miningdim.job.farmer.block.FarmerFarmlandBlock;
import com.miningdim.job.farmer.item.FarmerCreativeTab;
import com.miningdim.job.farmer.item.FarmerItems;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 农夫职业子系统入口 (FarmingXP_Mod_DesignSpec; 模块化铁律 3: 自持 DeferredRegister + 自订阅事件)。
 *
 * 自注册:
 *  - 方块/物品/创造页 DeferredRegister ({@link FarmerBlocks}/{@link FarmerItems}/{@link FarmerCreativeTab}, modBus);
 *  - 收获经验结算 ({@link #onCropHarvested}, forgeBus BreakEvent): 只认 mod 作物成熟态破坏;
 *  - 放置上限 + 档位门控 ({@link #onFarmlandPlace}, forgeBus EntityPlaceEvent): 超限/未解锁拒放;
 *  - 耕地破坏回收计数 ({@link #onFarmlandBroken}, 复用 BreakEvent);
 *  - 反作弊骨粉 ({@link #onBonemeal}, forgeBus BonemealEvent): mod 作物禁骨粉;
 *  - 卖菜命令 ({@link #onRegisterCommands}, RegisterCommandsEvent): 自注册 /farmer sell &lt;amount&gt; 子根作为
 *    {@link FarmerWheatSellService#sell} 的触发点 (包内闭合, 不改共享 JobCommands; 审查 Critical 1)。
 *
 * 不持有玩家进度: 经验走共享 {@link JobServices#jobService()} 入账 (JobId.FARMER), 衰减/翻日/升级由框架裁决;
 * 耕地放置计数走 {@link FarmerSavedData} (overworld 持久层)。
 *
 * 已在 {@code MiningDim.registerSubsystems()} 实装 (本子系统经 modBus/forgeBus 自注册其全部注册项与事件)。
 */
public final class FarmerSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job/farmer");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        FarmerBlocks.register(modBus);
        FarmerItems.register(modBus);
        FarmerCreativeTab.register(modBus);
        FarmerLootModifiers.register(modBus);
        forgeBus.register(this);
        LOGGER.info("[miningdim] farmer job subsystem registered (5 farmland tiers + crop yield + Farmer's Delight + harvest xp + placement cap + /farmer sell)");
    }

    // ============================================================
    // 卖菜命令 (审查 Critical 1: FarmerWheatSellService.sell 的运行期触发点)
    // ============================================================

    /**
     * 自注册农夫包私有命令根 /farmer sell &lt;amount&gt; 作为 {@link FarmerWheatSellService#sell} 的触发点。
     *
     * 为何在本子系统自注册而非改共享 JobCommands: 卖菜是农夫专有的经济交互, 包内闭合 (不污染 /job 通用根, 不改
     * 共享 JobCommands)。amount 下界 1 由 {@link IntegerArgumentType#integer(int)} 在 Brigadier 层强制。
     * 反馈用 {@link Component#literal} (不引入新 lang key, 共享 lang 文件由集成阶段维护)。
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("farmer")
                        .then(Commands.literal("crops")
                                .executes(this::cropTableCommand))
                        .then(Commands.literal("sell")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(this::sellCommand))));
    }

    private int cropTableCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int farmerLevel = JobServices.jobService().level(player, JobId.FARMER);
        ctx.getSource().sendSuccess(() -> Component.translatable("message.miningdim.farmer.crop_table_header")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("message.miningdim.farmer.crop_table_columns")
                .withStyle(ChatFormatting.GRAY), false);
        for (FarmerCropTable.Row row : FarmerCropTable.rows()) {
            Component tierName = FarmerBlocks.farmland(row.tier()).get().getName();
            Component unlock = row.tier().isUnlockedAt(farmerLevel)
                    ? Component.translatable("message.miningdim.farmer.crop_table_unlocked")
                    : Component.translatable("message.miningdim.farmer.crop_table_locked", row.unlockLevel());
            ctx.getSource().sendSuccess(() -> Component.translatable(
                            "message.miningdim.farmer.crop_table_row",
                            tierName, unlock, row.growthMinutes(), row.yieldMultiplier(),
                            FarmerCropTable.amount(row.farmerWheatPerHour()), row.farmerWheatPerSixHours())
                    .withStyle(row.tier().isUnlockedAt(farmerLevel)
                            ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY), false);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("message.miningdim.farmer.crop_table_compat")
                .withStyle(ChatFormatting.AQUA), false);
        ctx.getSource().sendSuccess(() -> Component.translatable("message.miningdim.farmer.crop_table_note")
                .withStyle(ChatFormatting.GRAY), false);
        return FarmerTier.values().length;
    }

    /**
     * /farmer sell &lt;amount&gt; 执行体: 委派 {@link FarmerWheatSellService#sell}, 据结果回显。命令边界统一兜底
     * 玩家输入/业务结果 (与 entry.MiningCommands 同范式), 不内联结算逻辑。
     */
    private int sellCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        FarmerWheatSellService.SellResult result = FarmerWheatSellService.sell(player, amount);
        if (result.economyOffline()) {
            ctx.getSource().sendFailure(Component.literal("Economy service is not available; nothing was sold."));
            return 0;
        }
        if (result.soldCount() <= 0) {
            ctx.getSource().sendFailure(Component.literal("You have no mod wheat to sell."));
            return 0;
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal("Sold " + result.soldCount() + " wheat for "
                        + result.creditsGranted() + " credit(s)."),
                false);
        return result.soldCount();
    }

    // ============================================================
    // 收获经验结算 (第二章: 只认 mod 作物成熟态破坏并掉落; 第十章: 防重复刷取)
    // ============================================================

    /**
     * mod 作物成熟态被玩家破坏时结算农夫经验。原始经验 = 单作物经验 × 该档耕地产量 (表B):
     *  - 仅 {@link FarmerCropBlock} 且处于成熟态 (isMaxAge) 才结算 (未成熟破坏经验 = 0);
     *  - 下方耕地档位决定产量 (高档产更多 -> 单次结算原始经验更高);
     *  - 入账走框架 {@link JobServices#jobService()#grantXp}, 受每日有效经验软上限衰减 (2000 系) 约束。
     *
     * 经验与作物掉落解耦 (第二章): 本法只算经验, 不动掉落 (loot table 照常掉小麦), 故软上限只削经验不削小麦。
     */
    // LOWEST: 必须排在所有可能取消 BreakEvent 的监听器 (领地/保护类) 之后再结算。挂 NORMAL 时
    // isCanceled() 只看得见 HIGHEST/HIGH 阶段的取消, 被 LOW/LOWEST 取消的破坏仍会先发出经验,
    // 玩家可对同一株受保护作物反复破坏刷经验。
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCropHarvested(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return; // 仅服务端玩家破坏结算 (假玩家/客户端不结算)。
        }
        BlockState state = event.getState();
        boolean nativeCrop = state.getBlock() instanceof FarmerCropBlock crop && crop.isMaxAge(state);
        boolean compatibleCrop = FarmerHarvests.isSupportedMatureCrop(state);
        if (!nativeCrop && !compatibleCrop) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FarmerTier tier = nativeCrop
                ? FarmerCropBlock.tierBelow(player.level(), event.getPos())
                : FarmerHarvests.tierFor(player.level(), event.getPos(), state);
        if (tier == null) {
            return; // 下方不是 mod 耕地 (原版耕地上的 mod 作物不产经验, 反扩建): 经验 = 0。
        }
        int yield = tier.yieldPerHarvest();

        // 经验入账 (表B: 单作物经验 × 产量), 受框架每日软上限衰减。
        long rawXp = (long) FarmerConstants.SINGLE_CROP_XP * yield;
        JobServices.jobService().grantXp(player, JobId.FARMER, rawXp);

        // 小麦掉落由本处单一权威发放 (loot table 只补种种子, 不掉小麦), 株数 = 该档产量, 与经验/经济计数严格一致
        // (第七章: 小麦产量纯由方块上限 × 单块速率 × 产量决定, 不受经验软上限削减)。
        if (nativeCrop) {
            Block.popResource(level, event.getPos(), new ItemStack(FarmerItems.FARMER_WHEAT.get(), yield));
        }
    }

    /**
     * Takes over Farmer's Delight tomato right-click harvesting before its native 1-2 drop path.
     *
     * LOWEST 而非 HIGHEST: 采摘会掉落物品、重置作物年龄并发放经验, 这些副作用必须排在领地与保护类
     * 监听器判定之后。挂 HIGHEST 时本处最先执行, 保护监听器随后才取消事件, 副作用已经落地, 玩家可
     * 收获无权区域的作物。原版方块 use() 在全部 RightClickBlock 监听器之后才调用, 因此降到 LOWEST
     * 仍然抢在 Farmer's Delight 原生掉落路径之前。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCropPicked(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) {
            return; // 已被保护类监听器拒绝: 不得产出作物、经验与音效。
        }
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        if (!FarmerHarvests.isPickableFarmersDelightTomato(state)) {
            return;
        }
        FarmerTier tier = FarmerHarvests.tierFor(level, event.getPos(), state);
        if (tier == null) {
            return;
        }

        int nativeCount = 1 + level.random.nextInt(2);
        Block.popResource(level, event.getPos(),
                new ItemStack(requiredItem("farmersdelight", "tomato"),
                        nativeCount * tier.yieldPerHarvest()));
        if (level.random.nextFloat() < 0.05F) {
            Block.popResource(level, event.getPos(),
                    new ItemStack(requiredItem("farmersdelight", "rotten_tomato")));
        }
        level.playSound(null, event.getPos(),
                requiredSound("farmersdelight", "block.tomatoes.pick_tomatoes"),
                SoundSource.BLOCKS, 1.0F, 0.8F + level.random.nextFloat() * 0.4F);
        level.setBlock(event.getPos(), state.setValue(BlockStateProperties.AGE_3, 0), 2);

        JobServices.jobService().grantXp(player, JobId.FARMER,
                (long) FarmerConstants.SINGLE_CROP_XP * tier.yieldPerHarvest());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static Item requiredItem(String namespace, String path) {
        ResourceLocation id = new ResourceLocation(namespace, path);
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            throw new IllegalStateException("Required compatible crop item is not registered: " + id);
        }
        return item;
    }

    private static SoundEvent requiredSound(String namespace, String path) {
        ResourceLocation id = new ResourceLocation(namespace, path);
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (sound == null) {
            throw new IllegalStateException("Required compatible crop sound is not registered: " + id);
        }
        return sound;
    }

    // ============================================================
    // 放置上限 + 档位门控 (表A 方块上限 + 表B 解锁等级; 设计目标 2/5 反扩建硬封顶)
    // ============================================================

    /**
     * 放置 mod 耕地时校验等级门控 (档位是否解锁) 与方块上限 (已放数是否到顶), 超限/未解锁直接取消放置。
     * 通过则计数 +1 (全局按玩家 UUID 计, 见 {@link FarmlandPlacementGuard} 口径裁决)。
     */
    @SubscribeEvent
    public void onFarmlandPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return; // 仅服务端玩家放置受闸门约束 (活塞/掉落方块等非玩家放置不计入也不受限)。
        }
        if (!(event.getPlacedBlock().getBlock() instanceof FarmerFarmlandBlock farmland)) {
            return; // 非 mod 耕地: 不约束。
        }
        ServerLevel overworld = player.server.overworld();
        FarmerSavedData data = FarmerSavedData.get(overworld);
        int currentLevel = JobServices.jobService().level(player, JobId.FARMER);
        int placed = data.placedCount(player.getUUID());

        FarmlandPlacementGuard.PlaceResult result =
                FarmlandPlacementGuard.checkPlacement(farmland.tier(), currentLevel, placed);
        switch (result) {
            case REJECT_TIER_LOCKED:
                event.setCanceled(true);
                player.displayClientMessage(
                        Component.translatable("message.miningdim.farmer.tier_locked",
                                farmland.tier().unlockLevel()), true);
                return;
            case REJECT_CAP_REACHED:
                event.setCanceled(true);
                player.displayClientMessage(
                        Component.translatable("message.miningdim.farmer.cap_reached",
                                FarmlandPlacementGuard.capForLevel(currentLevel)), true);
                return;
            case ALLOW:
            default:
                data.increment(player.getUUID());
        }
    }

    /**
     * 破坏 mod 耕地时回收放置计数 (-1)。与 {@link #onCropHarvested} 复用同一 BreakEvent: 该处只认作物,
     * 本处只认耕地, 互不重叠。仅服务端玩家破坏计入回收 (与放置侧对称: 玩家放/玩家破)。
     */
    @SubscribeEvent
    public void onFarmlandBroken(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getState().getBlock() instanceof FarmerFarmlandBlock)) {
            return;
        }
        ServerLevel overworld = player.server.overworld();
        FarmerSavedData.get(overworld).decrement(player.getUUID());
    }

    // ============================================================
    // 反作弊: mod 作物禁骨粉 (第十章; 否则每日软上限形同虚设)
    // ============================================================

    /**
     * 骨粉用于 mod 作物时直接取消事件 (不消耗骨粉、不成长、不产经验)。方块层 {@link FarmerCropBlock} 已把
     * isValidBonemealTarget 设 false (vanilla 不会推进), 本处再取消事件作二次封堵 (确保任何骨粉路径都被拦)。
     */
    @SubscribeEvent
    public void onBonemeal(BonemealEvent event) {
        BlockState state = event.getBlock();
        boolean nativeFarmerCrop = state.getBlock() instanceof FarmerCropBlock;
        boolean compatibleCropOnFarmerSoil = FarmerHarvests.isSupportedCrop(state)
                && FarmerHarvests.tierFor(event.getLevel(), event.getPos(), state) != null;
        if (nativeFarmerCrop || compatibleCropOnFarmerSoil) {
            event.setCanceled(true);
        }
    }

    @Override
    public String name() {
        return "FarmerSystem";
    }
}
