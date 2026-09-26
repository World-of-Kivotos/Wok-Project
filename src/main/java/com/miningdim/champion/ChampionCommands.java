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
 * 自研精英怪命令 (取代已移除的 Champions {@code /champions summon})。OP (level 2) 专用: 真服测试按需召唤指定星级 +
 * 词条的自研冠军 (自然刷靠矿洞 {@code MobPressureSystem} 难度掷取, 命令则精准点名), 以及召唤世界 BOSS。
 *
 * {@code /mchampion summon <entity> <star>} —— 召唤该实体升格为该星冠军, 词条按 {@link AffixRoller} 四池预算掷取。
 * {@code /mchampion summon <entity> <star> <affixes>} —— 词条显式指定 (空格分隔枚举名, 大小写不敏感, 支持
 *   {@code champions:} 前缀历史写法), 品质按星兜底 ({@link ChampionAffixState#defaultQualityFor}); 调试可越互斥/预算。
 *   summon 必须由玩家执行, 落在玩家前方 2 格。
 * {@code /mchampion worldboss <entity> <star 8-10> [affixes]} —— 召唤世界 BOSS ({@link WorldBoss}; 服主 2026-09-26
 *   拍板世界 BOSS 暂用指令刷, 出现时全服提示)。词条参数与 summon 同一套解析; 落在命令源的位置与维度, 不要求执行者是
 *   玩家: 控制台用 {@code execute in <维度> positioned <x y z> run mchampion worldboss ...}, 命令方块落在方块处。
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
                                                        StringArgumentType.getString(ctx, "affixes")))))))
                .then(Commands.literal("worldboss")
                        .then(Commands.argument("entity", ResourceLocationArgument.id())
                                .then(Commands.argument("star",
                                                IntegerArgumentType.integer(WorldBoss.MIN_STAR, StarRank.MAX_STAR))
                                        .executes(ctx -> worldBoss(ctx, null))
                                        .then(Commands.argument("affixes", StringArgumentType.greedyString())
                                                .executes(ctx -> worldBoss(ctx,
                                                        StringArgumentType.getString(ctx, "affixes")))))));
        dispatcher.register(root);
    }

    /** 召唤 + 盖章。affixArg=null 走 roll, 非空走解析指定。 */
    private static int summon(CommandContext<CommandSourceStack> ctx, String affixArg) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        int star = IntegerArgumentType.getInteger(ctx, "star");
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "entity");
        Mob mob = createMob(src, level, id);
        if (mob == null) {
            return 0; // 已 sendFailure。
        }
        Map<AffixDef, AffixQuality> affixMap = resolveAffixes(src, id, StarRank.ofStar(star), level.getRandom(),
                affixArg);
        if (affixMap == null) {
            mob.discard();
            return 0; // 解析失败已 sendFailure。
        }

        // 落点: 玩家前方 2 格 (同高度)。
        Vec3 look = player.getLookAngle();
        double px = player.getX() + look.x * 2.0D;
        double pz = player.getZ() + look.z * 2.0D;
        mob.moveTo(px, player.getY(), pz, player.getYRot(), 0.0F);
        level.addFreshEntity(mob);

        // 盖章 (自然升格共用入口: cap + 血量 + 6★+ 血池)。
        ChampionPromoter.applyChampion(mob, star, affixMap);

        src.sendSuccess(() -> Component.literal(
                "已召唤 " + star + "star 冠军 " + id + " 词条=" + affixMap.keySet()), true);
        return 1;
    }

    /**
     * 召唤世界 BOSS: 落在命令源的位置与维度 (玩家、控制台 execute positioned、命令方块都行), 盖章 + 打世界 BOSS 标记 +
     * 全服公告全部交给 {@link WorldBoss#spawn}。affixArg=null 走 roll, 非空走解析指定。
     */
    private static int worldBoss(CommandContext<CommandSourceStack> ctx, String affixArg) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        int star = IntegerArgumentType.getInteger(ctx, "star");
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "entity");
        Mob mob = createMob(src, level, id);
        if (mob == null) {
            return 0; // 已 sendFailure。
        }
        Map<AffixDef, AffixQuality> affixMap = resolveAffixes(src, id, StarRank.ofStar(star), level.getRandom(),
                affixArg);
        if (affixMap == null) {
            mob.discard();
            return 0; // 解析失败已 sendFailure。
        }
        if (!WorldBoss.spawn(level, mob, src.getPosition(), src.getRotation().y, star, affixMap)) {
            src.sendFailure(Component.literal("世界 BOSS 未能进入世界 (入世被拒), 未发全服公告: " + id));
            return 0;
        }
        String where = level.dimension().location() + " " + mob.blockPosition().toShortString();
        src.sendSuccess(() -> Component.literal(
                "已召唤 " + star + "star 世界 BOSS " + id + " 于 " + where + " 词条=" + affixMap.keySet()), true);
        return 1;
    }

    /** 按实体 id 建一只尚未入世的 Mob; 未知类型 / 非 Mob 时 sendFailure 并返回 null。 */
    private static Mob createMob(CommandSourceStack src, ServerLevel level, ResourceLocation id) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null) {
            src.sendFailure(Component.literal("未知实体类型: " + id));
            return null;
        }
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            src.sendFailure(Component.literal("该实体不是 Mob, 无法升格为冠军: " + id));
            return null;
        }
        return mob;
    }

    /**
     * 定词条: affixArg 为 null/空白时按星四池预算掷取, 否则按名字解析 (解析失败已 sendFailure, 返回 null)。
     */
    private static Map<AffixDef, AffixQuality> resolveAffixes(CommandSourceStack src, ResourceLocation id,
                                                              StarRank rank, RandomSource rng, String affixArg) {
        if (affixArg == null || affixArg.isBlank()) {
            // 自动掷取与自然生成同口径 (spec 9A.4 审查修复): 非白名单异形实体剔除 SIZE 族改抽同池,
            // 不给末影龙/蜘蛛这类实体静默掷出体型词条 (显式指定路径才是调试特权, 见下分支)。
            return rollAffixes(rank, rng, SizeAffixEligibility.isEligible(id.toString()));
        }
        Map<AffixDef, AffixQuality> affixMap = parseAffixes(affixArg, rank, src);
        // spec 9A.4: 体型词条仅白名单人形碰撞箱实体可 roll。命令显式路径可越白名单 (与越互斥/预算同属调试特权,
        // 见类 javadoc), 但对非白名单实体回执提示 —— 自然生成时该体型词条会被 roller 剔除改抽同池等成本词条,
        // 不会真附身异形; 命令强制附加仅供调试观察。
        if (affixMap != null && !SizeAffixEligibility.isEligible(id.toString()) && containsSizeAffix(affixMap)) {
            src.sendSuccess(() -> Component.literal(
                    "提示: " + id + " 非体型词条白名单实体 (spec 9A.4), 自然生成会改抽同池等成本词条; 命令已强制附加仅供调试"),
                    false);
        }
        return affixMap;
    }

    /** 按星四池预算掷取词条 -> def→品质 (sizeEligible=false 剔除 SIZE 族, 与自然生成同口径)。 */
    private static Map<AffixDef, AffixQuality> rollAffixes(StarRank rank, RandomSource rng, boolean sizeEligible) {
        Map<AffixDef, AffixQuality> map = new EnumMap<>(AffixDef.class);
        List<AffixSelection> rolled = AffixRoller.roll(rank, rng, sizeEligible);
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

    /** affixMap 是否含体型词条 (SIZE 族: 巨大化/缩小化), 供非白名单实体提示判定。 */
    private static boolean containsSizeAffix(Map<AffixDef, AffixQuality> affixMap) {
        for (AffixDef def : affixMap.keySet()) {
            if (def.mutexFlag() == AffixDef.MutexFlag.SIZE) {
                return true;
            }
        }
        return false;
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
