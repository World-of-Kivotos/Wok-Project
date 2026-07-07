package com.miningdim.champion;

import com.miningdim.champion.integration.ChampionPromoter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自研精英怪调试命令 (取代已移除的 Champions {@code /champions summon})。OP (level 2) 专用, 供真服测试按需召唤指定
 * 星级 + 词条的自研冠军 (自然刷靠矿洞 {@code MobPressureSystem} 难度掷取, 命令则精准点名)。
 *
 * {@code /mchampion summon <entity> <star>} —— 召唤该实体升格为该星冠军, 词条按 {@link AffixRoller} 四池预算掷取。
 * {@code /mchampion summon <entity> <star> <affixes>} —— 词条显式指定 (空格分隔枚举名, 大小写不敏感, 支持
 *   {@code champions:} 前缀历史写法), 品质按星兜底 ({@link ChampionAffixState#defaultQualityFor}); 调试可越互斥/预算。
 *
 * 盖章唯一入口 {@link ChampionPromoter#applyChampion} (与自然升格共用: 写 {@link MiningChampions} capability + 接管
 * 基础血量, 6★+ 建血池)。命令只做参数解析 + 建实体 + 委派, 不碰任何 Champions 类。
 */
public final class ChampionCommands {

    private static final int OP_LEVEL = 2;

    private ChampionCommands() {
    }

    /** 注册 /mchampion 命令树 (由 {@link ChampionSystem} 在 RegisterCommandsEvent 调用)。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mchampion")
                .requires(src -> src.hasPermission(OP_LEVEL))
                .then(Commands.literal("summon")
                        .then(Commands.argument("entity", ResourceLocationArgument.id())
                                .then(Commands.argument("star",
                                                IntegerArgumentType.integer(StarRank.MIN_STAR, StarRank.MAX_STAR))
                                        .executes(ctx -> summon(ctx, null))
                                        .then(Commands.argument("affixes", StringArgumentType.greedyString())
                                                .executes(ctx -> summon(ctx,
                                                        StringArgumentType.getString(ctx, "affixes")))))));
        dispatcher.register(root);
    }

    /** 召唤 + 盖章。affixArg=null 走 roll, 非空走解析指定。 */
    private static int summon(CommandContext<CommandSourceStack> ctx, String affixArg) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        int star = IntegerArgumentType.getInteger(ctx, "star");
        StarRank rank = StarRank.ofStar(star);

        ResourceLocation id = ResourceLocationArgument.getId(ctx, "entity");
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null) {
            src.sendFailure(Component.literal("未知实体类型: " + id));
            return 0;
        }
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            src.sendFailure(Component.literal("该实体不是 Mob, 无法升格为冠军: " + id));
            return 0;
        }

        Map<AffixDef, AffixQuality> affixMap;
        if (affixArg == null || affixArg.isBlank()) {
            affixMap = rollAffixes(rank, level.getRandom());
        } else {
            affixMap = parseAffixes(affixArg, rank, src);
            if (affixMap == null) {
                mob.discard();
                return 0; // 解析失败已 sendFailure。
            }
        }

        // 落点: 玩家前方 2 格 (同高度)。
        Vec3 look = player.getLookAngle();
        double px = player.getX() + look.x * 2.0D;
        double pz = player.getZ() + look.z * 2.0D;
        mob.moveTo(px, player.getY(), pz, player.getYRot(), 0.0F);
        level.addFreshEntity(mob);

        // 盖章 (自然升格共用入口: cap + 血量 + 6★+ 血池)。
        ChampionPromoter.applyChampion(mob, star, affixMap);

        Map<AffixDef, AffixQuality> shown = affixMap;
        src.sendSuccess(() -> Component.literal(
                "已召唤 " + star + "star 冠军 " + id + " 词条=" + shown.keySet()), true);
        return 1;
    }

    /** 按星四池预算掷取词条 -> def→品质。 */
    private static Map<AffixDef, AffixQuality> rollAffixes(StarRank rank, RandomSource rng) {
        Map<AffixDef, AffixQuality> map = new EnumMap<>(AffixDef.class);
        List<AffixSelection> rolled = AffixRoller.roll(rank, rng);
        for (AffixSelection sel : rolled) {
            map.put(sel.affix(), sel.quality());
        }
        return map;
    }

    /**
     * 解析空格分隔的词条名 (如 "regen_tissue sprint thorns"); 品质按星兜底。未知词条名 sendFailure 返 null。
     * 支持 champions: 前缀 (历史 registry 写法) 与大小写不敏感。
     */
    private static Map<AffixDef, AffixQuality> parseAffixes(String arg, StarRank rank, CommandSourceStack src) {
        Map<AffixDef, AffixQuality> map = new EnumMap<>(AffixDef.class);
        for (String token : arg.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            String name = token.contains(":") ? token.substring(token.indexOf(':') + 1) : token;
            AffixDef def = affixByName(name);
            if (def == null) {
                src.sendFailure(Component.literal("未知词条: " + token));
                return null;
            }
            map.put(def, ChampionAffixState.defaultQualityFor(def, rank));
        }
        return map;
    }

    /** 词条名 (枚举名, 大小写不敏感) -> AffixDef; 未知返 null。 */
    private static AffixDef affixByName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        for (AffixDef def : AffixDef.values()) {
            if (def.name().equals(upper)) {
                return def;
            }
        }
        return null;
    }
}
