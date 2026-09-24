package com.miningdim.job.fisher;

import com.miningdim.core.ItemRarityOverrides;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.Subsystem;
import com.miningdim.job.fisher.journal.FishingJournalCatalog;
import com.miningdim.job.fisher.journal.FishingJournalItem;
import com.miningdim.job.fisher.journal.FishingJournalNetwork;
import com.miningdim.job.fisher.journal.FishingJournalService;
import com.miningdim.job.fisher.ore.OreFishingItems;
import com.miningdim.job.fisher.ore.OreFishingConfig;
import com.miningdim.job.fisher.ore.OreFishSellService;
import com.miningdim.job.fisher.quality.FishQuality;
import com.miningdim.job.fisher.size.FishCatchService;
import com.miningdim.job.fisher.size.FishSizeCatalog;
import com.miningdim.job.fisher.soup.OreSoupEffects;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** WOK 本体渔业入口：目录收藏、鱼种品质、钓获体型、矿石鱼和料理联动；职业成长后续接入统一经验服务。 */
public final class FishingSystem implements Subsystem {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final RegistryObject<Item> JOURNAL = ITEMS.register("fishing_journal", FishingJournalItem::new);

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 扩展稀有度必须建在任何物品注册与稀有度查询之前, 两个物理端都在 mod 构造期走到这里。
        FishQuality.bootstrap();
        ItemRarityOverrides.register(FishQuality::rarityOverride);
        ITEMS.register(modBus);
        OreFishingItems.register(modBus);
        OreSoupEffects.register(modBus, forgeBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, OreFishingConfig.SPEC, "miningdim-fishing.toml");
        modBus.addListener((ModConfigEvent.Loading event) -> validateConfig(event.getConfig()));
        modBus.addListener((ModConfigEvent.Reloading event) -> validateConfig(event.getConfig()));
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onCreativeTab);
        forgeBus.register(this);
        forgeBus.register(new FishingTooltipHandler());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(FishingJournalNetwork::register);
    }

    private void validateConfig(ModConfig config) {
        if (config.getSpec() == OreFishingConfig.SPEC) {
            OreFishingConfig.validate();
        }
    }

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            event.accept(JOURNAL);
        }
        if (event.getTabKey().equals(CreativeModeTabs.FOOD_AND_DRINKS)) {
            OreFishingItems.FISH.values().forEach(event::accept);
            OreFishingItems.SOUPS.values().forEach(event::accept);
        }
    }

    @SubscribeEvent
    public void onReload(AddReloadListenerEvent event) {
        event.addListener(FishingJournalCatalog.INSTANCE);
        event.addListener(FishSizeCatalog.INSTANCE);
    }

    /**
     * 原版钓竿的体型结算。最低优先级且不接收已取消事件: 只测量真正会落地的渔获, 别的 MOD 取消掉的那一竿
     * 不会留下个人记录。事件里的列表是副本, 但元素与实际生成掉落物的 ItemStack 是同一批引用, 原地打标签即生效。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onItemFished(ItemFishedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FishCatchService.onSuccessfulCatch(player, event.getDrops());
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fishing")
                .then(Commands.literal("sell").executes(OreFishSellService::executeSell)
                        .then(Commands.literal("all").executes(OreFishSellService::executeSellAll)))
                .then(Commands.literal("journal").executes(context -> {
                    FishingJournalService.open(context.getSource().getPlayerOrException());
                    return 1;
                })));
    }

    @SubscribeEvent
    public void onPickup(PlayerEvent.ItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && FishingJournalService.collect(player, event.getStack())) {
            update(player);
        }
    }

    @SubscribeEvent
    public void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && FishingJournalService.collect(player, event.getCrafting())) {
            update(player);
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FishingJournalService.observeMenu(player, player.inventoryMenu);
        }
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FishingJournalService.observeMenu(player, player.inventoryMenu);
        }
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FishingJournalService.observeMenu(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 补查其它 MOD 直接写入背包等没有容器通知的路径，按玩家错峰。
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player
                && (player.tickCount + player.getId()) % 20 == 0
                && FishingJournalService.scanInventory(player)) {
            update(player);
        }
    }

    @SubscribeEvent
    public void onDatapackSync(OnDatapackSyncEvent event) {
        // 登录和 /reload 后重发目录，避免客户端保留已移除的鱼种或上一存档收藏。
        if (event.getPlayer() != null) {
            syncCatalog(event.getPlayer());
        } else {
            event.getPlayerList().getPlayers().forEach(this::syncCatalog);
        }
    }

    private void syncCatalog(ServerPlayer player) {
        FishingJournalService.scanInventory(player);
        update(player);
    }

    private void update(ServerPlayer player) {
        FishingJournalNetwork.send(player, FishingJournalService.snapshot(player), false);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        FishingJournalCatalog.INSTANCE.clear();
        FishSizeCatalog.INSTANCE.clear();
    }
}
