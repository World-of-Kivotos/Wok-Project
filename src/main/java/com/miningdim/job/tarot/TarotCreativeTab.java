package com.miningdim.job.tarot;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.UUID;

/**
 * 塔罗师专属创造标签页 (任务铁律: 严禁改中央 ModCreativeTabs; 各子系统自建自己的 CreativeModeTab)。
 *
 * 展示: 卡包三种 + 碎片 + 合成台 + 22 张牌的样例 (每张一张 R 正位, 创造模式预览; 玩家正常获取走开包/合成)。
 * 创造样例牌盖一个全零占位 owner (使用时 owner 不匹配打不出, 仅供查看; 正常牌经开包盖真实 owner)。
 */
public final class TarotCreativeTab {

    private TarotCreativeTab() {
    }

    /** 创造样例牌的占位 owner (全零 UUID; use 时 owner 不符故打不出, 仅供创造预览查看)。 */
    private static final UUID PREVIEW_OWNER = new UUID(0L, 0L);

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<CreativeModeTab> TAROT_TAB = TABS.register("miningdim_tarot",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim_tarot"))
                    .icon(() -> new ItemStack(TarotRegistry.PACK_SHINY.get()))
                    .displayItems((params, output) -> {
                        output.accept(TarotRegistry.PACK_COMMON.get());
                        output.accept(TarotRegistry.PACK_ADVANCED.get());
                        output.accept(TarotRegistry.PACK_SHINY.get());
                        output.accept(TarotRegistry.TAROT_SHARD.get());
                        output.accept(TarotRegistry.CRAFT_TABLE_ITEM.get());
                        // 22 张大阿卡纳样例 (R 正位预览)。
                        for (TarotArcana arcana : TarotArcana.values()) {
                            output.accept(TarotCardItem.create(TarotRegistry.TAROT_CARD.get(),
                                    arcana.cardId(), TarotQuality.R, true, PREVIEW_OWNER));
                        }
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
