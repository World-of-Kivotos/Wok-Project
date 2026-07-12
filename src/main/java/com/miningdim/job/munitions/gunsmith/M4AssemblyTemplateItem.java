package com.miningdim.job.munitions.gunsmith;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class M4AssemblyTemplateItem extends Item {

    private static final GunsmithPlatform PLATFORM = GunsmithPlatform.AR;

    public M4AssemblyTemplateItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(held);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(held);
        }
        // 功能门 (审查 G-1): 组枪出口与冲压同关, 3A 章 WIP 全链默认关闭。
        if (!com.miningdim.job.munitions.MunitionsConfig.GUNSMITH_ENABLED.get()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith.disabled"), true);
            return InteractionResultHolder.consume(held);
        }

        Inventory inventory = player.getInventory();
        EnumMap<GunsmithPressPart, Integer> slots = findRequiredPartSlots(inventory);
        GunsmithPressPart missing = firstMissing(slots);
        if (missing != null) {
            player.displayClientMessage(Component.translatable("message.miningdim.m4_template.missing_part",
                    Component.translatable(missing.labelKey())), true);
            return InteractionResultHolder.fail(held);
        }

        ItemStack m4 = GunsmithGunFactory.materializeM4A1();
        if (m4.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.miningdim.m4_template.tacz_missing"), true);
            return InteractionResultHolder.fail(held);
        }

        EnumMap<GunsmithPressPart, ItemStack> consumedParts = snapshotParts(inventory, slots);
        stampGunData(m4, consumedParts);
        if (!player.getAbilities().instabuild) {
            consumeParts(inventory, slots);
        }
        ItemHandlerHelper.giveItemToPlayer(serverPlayer, m4);
        player.getCooldowns().addCooldown(this, 20);
        player.displayClientMessage(Component.translatable("message.miningdim.m4_template.success",
                GunsmithPartItem.formatCoefficient(averageCoefficient(consumedParts))), true);
        return InteractionResultHolder.consume(held);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.miningdim.m4_template.line1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.m4_template.line2").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("tooltip.miningdim.m4_template.line3").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static EnumMap<GunsmithPressPart, Integer> findRequiredPartSlots(Inventory inventory) {
        EnumMap<GunsmithPressPart, Integer> slots = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            int slot = findSlot(inventory, part);
            if (slot >= 0) {
                slots.put(part, slot);
            }
        }
        return slots;
    }

    private static int findSlot(Inventory inventory, GunsmithPressPart part) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (GunsmithPartItem.matches(stack, PLATFORM, part)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static GunsmithPressPart firstMissing(EnumMap<GunsmithPressPart, Integer> slots) {
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            if (!slots.containsKey(part)) {
                return part;
            }
        }
        return null;
    }

    private static EnumMap<GunsmithPressPart, ItemStack> snapshotParts(Inventory inventory,
                                                                       EnumMap<GunsmithPressPart, Integer> slots) {
        EnumMap<GunsmithPressPart, ItemStack> result = new EnumMap<>(GunsmithPressPart.class);
        for (Map.Entry<GunsmithPressPart, Integer> entry : slots.entrySet()) {
            result.put(entry.getKey(), inventory.getItem(entry.getValue()).copyWithCount(1));
        }
        return result;
    }

    private static void consumeParts(Inventory inventory, EnumMap<GunsmithPressPart, Integer> slots) {
        for (int slot : slots.values()) {
            inventory.removeItem(slot, 1);
        }
        inventory.setChanged();
    }

    private static void stampGunData(ItemStack gun, EnumMap<GunsmithPressPart, ItemStack> parts) {
        CompoundTag root = new CompoundTag();
        root.putString("template", "m4a1");
        root.putString("platform", PLATFORM.id());
        root.putString("gunId", GunsmithGunFactory.M4A1_ID.toString());

        CompoundTag partTags = new CompoundTag();
        for (Map.Entry<GunsmithPressPart, ItemStack> entry : parts.entrySet()) {
            ItemStack partStack = entry.getValue();
            CompoundTag partTag = new CompoundTag();
            partTag.putString("quality", GunsmithPartItem.qualityOf(partStack).id());
            partTag.putDouble("coefficient", GunsmithPartItem.coefficientOf(partStack));
            partTags.put(entry.getKey().id(), partTag);
        }
        root.put(GunsmithGunStats.PARTS_KEY, partTags);

        CompoundTag stats = new CompoundTag();
        stats.putDouble("damage", coefficient(parts, GunsmithPressPart.BOLT));
        stats.putDouble("headshot", coefficient(parts, GunsmithPressPart.BARREL));
        stats.putDouble("recoil", (coefficient(parts, GunsmithPressPart.CORE)
                + coefficient(parts, GunsmithPressPart.STOCK)) / 2.0D);
        stats.putDouble("spread", coefficient(parts, GunsmithPressPart.HANDGUARD));
        stats.putDouble("handling", coefficient(parts, GunsmithPressPart.GRIP));
        stats.putDouble("average", averageCoefficient(parts));
        root.put(GunsmithGunStats.STATS_KEY, stats);

        gun.getOrCreateTag().put(GunsmithGunStats.ROOT_KEY, root);
    }

    private static double coefficient(EnumMap<GunsmithPressPart, ItemStack> parts, GunsmithPressPart part) {
        ItemStack stack = parts.get(part);
        return stack == null ? 1.0D : GunsmithPartItem.coefficientOf(stack);
    }

    private static double averageCoefficient(EnumMap<GunsmithPressPart, ItemStack> parts) {
        double sum = 0.0D;
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            sum += coefficient(parts, part);
        }
        return sum / GunsmithPressPart.values().length;
    }
}
