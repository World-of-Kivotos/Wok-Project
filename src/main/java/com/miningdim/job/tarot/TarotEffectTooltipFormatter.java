package com.miningdim.job.tarot;

import com.miningdim.job.tarot.card.TarotCardData;
import com.miningdim.job.tarot.card.TarotEffectOp;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把数据包中当前品质/朝向的原子牌效转换为可本地化、带真实数值的悬浮说明。
 *
 * <p>格式化结果使用 {@link Component} 而不是提前翻译的字符串，因此服务端可把组件序列化到物品 NBT，
 * 客户端仍会按玩家选择的语言显示。每个原子效果独占一行，避免把多段牌效挤成不可读的长句。</p>
 */
public final class TarotEffectTooltipFormatter {

    private TarotEffectTooltipFormatter() {
    }

    public static List<Component> format(TarotCardData data, TarotQuality quality, boolean upright) {
        if (data == null || quality == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        for (TarotEffectOp op : data.opsFor(quality, upright)) {
            lines.add(Component.literal("• ").append(formatOp(op)));
        }
        return List.copyOf(lines);
    }

    private static Component formatOp(TarotEffectOp op) {
        String seconds = seconds(op.durationTicks());
        String period = seconds(op.periodTicks());
        String amount = number(op.amount());
        String radius = number(op.radius());
        String cap = number(op.capUp());
        String percent = percent(op.percent());
        String chance = percent(op.chance());
        Component effect = effectName(op.effectId());

        return switch (op.kind()) {
            case SELF_POTION -> tr(op, effect, op.amplifier() + 1, seconds);
            case SELF_HEAL_OVER_TIME -> tr(op, amount, seconds, op.count());
            case SELF_HEAL, SELF_TRUE_DAMAGE, SELF_ABSORPTION -> tr(op, amount);
            case SELF_PERIODIC_ABSORPTION -> tr(op, amount, seconds, op.count());
            case SELF_FULL_HEAL, SELF_CLEANSE, CLEAR_NORMAL_TAROT_COOLDOWNS -> tr(op);
            case SELF_MAX_HEALTH -> tr(op, signed(op.amount()), seconds,
                    op.amount() >= 0.0D ? number(op.capUp()) : number(op.floorDown()));
            case SELF_CLEANSE_MAX_HEALTH -> tr(op, amount, cap, seconds);
            case SELF_BLINK -> tr(op, amount);
            case SELF_DASH -> tr(op, amount, radius, cap);
            case SELF_RANDOM_BUFF -> tr(op, seconds, chance, amount, cap);
            case SELF_FORTUNE_GAMBLE -> tr(op, chance, amount, number(op.threshold()));
            case SELF_REFRESH_BENEFICIAL, SELF_HEALING_BLOCK, SELF_HERMIT_SHINY,
                    SELF_UNTARGETABLE, SELF_KNOCKBACK_IMMUNITY, SELF_INVULNERABLE -> tr(op, seconds);
            case SELF_DELAYED_POTION -> tr(op, effect, op.amplifier() + 1, seconds, period);
            case SELF_PERIODIC_TRUE_DAMAGE -> tr(op, amount, period, seconds);
            case SELF_CLEANSE_LIMITED -> tr(op, op.count());
            case SELF_PERIODIC_CLEANSE -> tr(op, op.count(), period);
            case SELF_CLEANSE_EFFECTS -> tr(op, effectList(op.effects()));
            case SELF_DEATH_GAMBLE -> tr(op, chance, amount, seconds, number(op.floorDown()));
            case SELF_DEATH_CONTRACT -> tr(op, seconds, amount);
            case SELF_LIFESTEAL -> tr(op, percent, seconds);
            case SELF_REFLECT -> tr(op, percent, cap, seconds);
            case SELF_REFLECT_ACCUM -> tr(op, percent, cap, radius, seconds);
            case SELF_DELAYED_LEDGER -> tr(op, seconds, percent, amount);
            case SHINY_BIND_SHARE_LIFE -> tr(op, seconds, radius, seconds(op.count()));
            case ENEMY_TARGET_DAMAGE -> tr(op, radius, number(op.threshold()), amount);
            case ENEMY_TARGET_AVERAGE_HEALTH -> tr(op, radius, cap);
            case ENEMY_TARGET_POTION -> tr(op, radius, effect, op.amplifier() + 1, seconds);
            case TARGET_TOWER_STRIKE -> tr(op, radius, amount, mode(op.effectId()), cap,
                    seconds(op.durationTicks()), seconds(op.periodTicks()),
                    Component.translatable(op.immuneVulnerability()
                            ? "tooltip.miningdim.tarot.effect.clear_buffs"
                            : "tooltip.miningdim.tarot.effect.keep_buffs"));
            case AOE_ENEMY_RANDOM_DAMAGE -> tr(op, radius, amount, number(op.amount() * 2.0D));
            case AOE_ENEMY_POTION, AOE_ALLY_POTION -> tr(op, radius, effect, op.amplifier() + 1, seconds);
            case AOE_ENEMY_DAMAGE, AOE_ALLY_ABSORPTION, AOE_ALLY_HEAL -> tr(op, radius, amount);
            case AOE_ENEMY_PULL -> tr(op, radius, amount);
            case AOE_ENEMY_RANDOM_TELEPORT -> tr(op, radius, amount, cap);
            case AOE_ENEMY_MISSING_HEALTH_DAMAGE -> tr(op, radius, amount, percent);
            case AOE_EXECUTE_BELOW_PCT -> tr(op, radius, percent, number(op.threshold()),
                    op.amplifier() + 1, amount);
            case AOE_ALLY_CLEANSE_LIMITED -> tr(op, radius,
                    op.count() < 0 ? Component.translatable("tooltip.miningdim.tarot.effect.all") : op.count());
            case AOE_ALLY_PERIODIC_CLEANSE -> tr(op, radius, period, seconds);
            case AOE_ALLY_BALANCE_HEALTH -> tr(op, radius, amount, period, seconds);
            case AOE_ALLY_DAMAGE_SHARE -> tr(op, radius, percent, seconds);
            case AOE_ALLY_EMERGENCY_HEAL -> tr(op, radius, percent, amount);
            case AOE_ALLY_LOW_HEALTH_HEAL -> tr(op, radius, percent, amount);
            case AOE_ENEMY_DAMAGE_OVER_TIME, AOE_ALLY_HEAL_OVER_TIME ->
                    tr(op, radius, amount, period, seconds);
            case IMMUNITY -> tr(op, seconds, effectList(op.effects()),
                    op.immuneVulnerability()
                            ? Component.translatable("tooltip.miningdim.tarot.effect.includes_vulnerability")
                            : Component.translatable("tooltip.miningdim.tarot.effect.no_vulnerability"));
        };
    }

    private static MutableComponent tr(TarotEffectOp op, Object... args) {
        return Component.translatable("tooltip.miningdim.tarot.effect.op." + op.kind().id(), args);
    }

    private static Component effectName(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return Component.literal(id == null || id.isBlank() ? "?" : id);
        }
        MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(key);
        return effect == null ? Component.literal(id) : effect.getDisplayName();
    }

    private static Component effectList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Component.translatable("tooltip.miningdim.tarot.effect.none");
        }
        MutableComponent out = Component.empty();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                out.append(Component.literal(" / "));
            }
            out.append(effectName(ids.get(i)));
        }
        return out;
    }

    private static Component mode(String id) {
        return Component.translatable("tooltip.miningdim.tarot.effect.mode." +
                ((id == null || id.isBlank()) ? "none" : id.toLowerCase(Locale.ROOT)));
    }

    private static String seconds(int ticks) {
        return number(ticks / 20.0D);
    }

    private static String percent(double value) {
        return number(value * 100.0D) + "%";
    }

    private static String signed(double value) {
        return (value > 0.0D ? "+" : "") + number(value);
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) {
            return "?";
        }
        if (Math.rint(value) == value) {
            return Long.toString((long) value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
