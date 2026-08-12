package com.miningdim.job.tarot.pack;

import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.job.tarot.TarotConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.ItemHandlerHelper;

/** Rare PvE source for shiny packs: qualifying self-hosted champions can award one to their killer. */
public final class TarotPackDropHandler {

    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) {
            return;
        }
        MiningChampionData champion = MiningChampions.get(victim).orElse(null);
        if (champion == null || !champion.isChampion() || champion.isSummonedByAffix()
                || champion.star() < TarotConfig.SHINY_PACK_DROP_MIN_STAR.get()) {
            return;
        }
        double chance = Math.min(1.0D, TarotConfig.SHINY_PACK_DROP_CHANCE_PER_STAR.get()
                * (champion.star() - TarotConfig.SHINY_PACK_DROP_MIN_STAR.get() + 1));
        if (victim.getRandom().nextDouble() >= chance) {
            return;
        }
        ItemHandlerHelper.giveItemToPlayer(killer, TarotPackItem.create(PackKind.SHINY, killer.getUUID()));
        killer.displayClientMessage(
                Component.translatable("message.miningdim.tarot.pack.rare_drop", champion.star()), false);
    }
}
