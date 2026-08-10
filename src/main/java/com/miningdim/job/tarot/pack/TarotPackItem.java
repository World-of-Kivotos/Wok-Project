package com.miningdim.job.tarot.pack;

import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.job.tarot.TarotRuntime;
import net.minecraft.nbt.CompoundTag;
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

import java.util.ArrayDeque;
import java.util.UUID;

/** A purchased or dropped pack. Currency is charged when the pack is acquired, never when it is opened. */
public final class TarotPackItem extends Item {

    private static final String K_OWNER = "OwnerUUID";

    private final PackKind kind;

    public TarotPackItem(Properties properties, PackKind kind) {
        super(properties.stacksTo(64));
        this.kind = kind;
    }

    public PackKind kind() {
        return kind;
    }

    public static ItemStack create(PackKind kind, UUID owner) {
        if (owner == null) {
            throw new IllegalArgumentException("tarot pack owner must not be null");
        }
        Item item = switch (kind) {
            case COMMON -> TarotRegistry.PACK_COMMON.get();
            case ADVANCED -> TarotRegistry.PACK_ADVANCED.get();
            case SHINY -> TarotRegistry.PACK_SHINY.get();
        };
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putUUID(K_OWNER, owner);
        return stack;
    }

    public static UUID owner(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(K_OWNER) ? tag.getUUID(K_OWNER) : null;
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
        if (!bindOrValidate(stack, serverPlayer)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.miningdim.tarot.pack.not_owner"), true);
            return InteractionResultHolder.fail(stack);
        }

        if (kind == PackKind.SHINY) {
            NetworkHooks.openScreen(serverPlayer, new ShinyPackSelectMenu.Provider(), buf -> { });
            return InteractionResultHolder.success(stack);
        }

        OpenSummary summary = kind == PackKind.COMMON
                ? openCommon(serverPlayer)
                : openAdvancedChain(serverPlayer);
        stack.shrink(1);
        serverPlayer.displayClientMessage(Component.translatable(
                "message.miningdim.tarot.pack.opened",
                summary.cards, summary.shards, summary.derived), true);
        return InteractionResultHolder.consume(stack);
    }

    private static OpenSummary openCommon(ServerPlayer player) {
        PackGachaService.OpenResult result = TarotRuntime.gacha().openCommon(player, player.getRandom());
        giveResult(player, result);
        return new OpenSummary(result.cards().size(), result.shardRefund(), 0);
    }

    /** Opens every generated advanced pack until the geometric chain ends or the daily safety cap is reached. */
    private static OpenSummary openAdvancedChain(ServerPlayer player) {
        PackGachaService gacha = TarotRuntime.gacha();
        PackGachaService.OpenResult root = gacha.openAdvanced(player, player.getRandom());
        giveResult(player, root);

        int cards = root.cards().size();
        int shards = root.shardRefund();
        int derivedOpened = 0;
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int i = 0; i < root.derivedPacks(); i++) {
            pending.addLast(1);
        }

        TarotPackSavedData data = TarotPackSavedData.get(player.getServer().overworld());
        int cap = TarotConfig.DAILY_PACK_LIMIT.get();
        long today = TarotPackClock.currentUtcDayStamp();
        boolean capped = false;
        while (!pending.isEmpty()) {
            pending.removeFirst();
            if (!data.tryRecordDerived(player.getUUID(), cap, today)) {
                capped = true;
                break;
            }
            PackGachaService.OpenResult derived = gacha.openAdvanced(player, player.getRandom());
            giveResult(player, derived);
            cards += derived.cards().size();
            shards += derived.shardRefund();
            derivedOpened++;
            for (int i = 0; i < derived.derivedPacks(); i++) {
                pending.addLast(1);
            }
        }
        if (capped) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.tarot.pack.derived_limit"), true);
        }
        return new OpenSummary(cards, shards, derivedOpened);
    }

    private static void giveResult(ServerPlayer player, PackGachaService.OpenResult result) {
        for (ItemStack card : result.cards()) {
            ItemHandlerHelper.giveItemToPlayer(player, card);
        }
        if (result.shardRefund() > 0) {
            ItemHandlerHelper.giveItemToPlayer(player,
                    com.miningdim.job.tarot.craft.TarotCraftService.makeShards(result.shardRefund()));
        }
    }

    /** Consumes one owned shiny pack after the server accepts a GUI selection. */
    public static boolean consumeShiny(ServerPlayer player) {
        ItemStack pack = findShinyPack(player);
        if (pack == null) {
            return false;
        }
        pack.shrink(1);
        return true;
    }

    private static ItemStack findShinyPack(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (isUsableShinyPack(stack, player)) {
                return stack;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (isUsableShinyPack(stack, player)) {
                return stack;
            }
        }
        return null;
    }

    private static boolean isUsableShinyPack(ItemStack stack, ServerPlayer player) {
        return !stack.isEmpty()
                && stack.getItem() instanceof TarotPackItem pack
                && pack.kind == PackKind.SHINY
                && bindOrValidate(stack, player);
    }

    private static boolean bindOrValidate(ItemStack stack, ServerPlayer player) {
        UUID owner = owner(stack);
        if (owner == null) {
            stack.getOrCreateTag().putUUID(K_OWNER, player.getUUID());
            return true;
        }
        return owner.equals(player.getUUID());
    }

    private record OpenSummary(int cards, int shards, int derived) {
    }
}
