package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.ModMunitionsItems;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class GunsmithBlueprintItem extends Item {

    private static final String ROOT_KEY = "MiningDimGunsmithBlueprint";
    private static final String GUN_ID_KEY = "GunId";

    public GunsmithBlueprintItem(Properties properties) {
        super(properties);
    }

    public static ItemStack createStack(Item item, GunsmithBlueprint blueprint) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(blueprint, "blueprint");
        ItemStack stack = new ItemStack(item);
        CompoundTag root = new CompoundTag();
        root.putString(GUN_ID_KEY, blueprint.gunId().toString());
        stack.getOrCreateTag().put(ROOT_KEY, root);
        return stack;
    }

    public static boolean isBlueprintItem(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return stack.is(ModMunitionsItems.GUNSMITH_BLUEPRINT.get());
    }

    public static GunsmithBlueprint requireBlueprint(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Gunsmith blueprint stack is empty");
        }
        if (!isBlueprintItem(stack)) {
            throw new IllegalArgumentException("Stack is not a gunsmith blueprint");
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Gunsmith blueprint has no compound NBT data");
        }
        CompoundTag root = tag.getCompound(ROOT_KEY);
        if (!root.contains(GUN_ID_KEY, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Gunsmith blueprint has no gun id string");
        }
        String gunIdValue = root.getString(GUN_ID_KEY);
        ResourceLocation gunId = ResourceLocation.tryParse(gunIdValue);
        if (gunId == null) {
            throw new IllegalArgumentException("Invalid gunsmith blueprint gun id: " + gunIdValue);
        }
        return GunsmithBlueprint.require(gunId);
    }

    @Nullable
    static GunsmithBlueprint tryBlueprint(ItemStack stack) {
        // getName/appendHoverText 跑在客户端渲染线程 (物品栏/手持名悬浮), 抛异常会直接崩客户端 —— 无外层
        // Controller 兜底。故渲染钩子对损坏/裸 NBT (仅 op /give 可造) 降级显示; 服务端装配路径仍走
        // requireBlueprint 硬校验。(审查 GS-2)
        try {
            return requireBlueprint(stack);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        GunsmithBlueprint blueprint = tryBlueprint(stack);
        if (blueprint == null) {
            return super.getName(stack);
        }
        return Component.translatable("item.miningdim.gunsmith_blueprint.name",
                Component.translatable(blueprint.nameKey()));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(held);
        }
        player.displayClientMessage(
                Component.translatable("message.miningdim.gunsmith_blueprint.use_assembly_bench"), true);
        return InteractionResultHolder.consume(held);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        GunsmithBlueprint blueprint = tryBlueprint(stack);
        if (blueprint == null) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_blueprint.invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Component platform = Component.translatable(blueprint.platform().labelKey());
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_blueprint.platform", platform)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_blueprint.gun_id",
                Component.literal(blueprint.gunId().toString())).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_blueprint.parts",
                platform, blueprint.requiredParts().size())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_blueprint.use_assembly_bench")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
