package com.miningdim.district.guard;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.guard.create.CreateBlockPolicy;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 个人圈地限制的判定 (设计文档 22.20): 合成快照、直接调 {@link PersonalClaimGuard}, 不需要 Flan (真 Flan 的行为在
 * {@code flan.real.FlanClaimGuardGameTests})。全部同步, 不碰方块, 不装门面 (view 直接构造)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PersonalClaimGuardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_claim_policy";
    private static final String OVERWORLD = "minecraft:overworld";

    private PersonalClaimGuardGameTests() {
    }

    /** 装着的 view: 给定设置与区 (都在主世界)。 */
    private static GuardView view(GuardSettings settings, DistrictZoneSnapshot.DistrictInput... districts) {
        return new GuardView(settings, DistrictZoneSnapshot.build(List.of(districts)), CreateBlockPolicy.of(settings),
                true);
    }

    private static DistrictZoneSnapshot.DistrictInput district(String id, int minX, int minZ, int maxX, int maxZ) {
        return new DistrictZoneSnapshot.DistrictInput(id, id + "-name", new DistrictBounds(OVERWORLD, minX, minZ, maxX,
                maxZ), List.of());
    }

    private static PlotArea box(int minX, int minZ, int maxX, int maxZ) {
        return new PlotArea(minX, minZ, maxX, maxZ);
    }

    /**
     * 新圈: 框在区内、碰到 minX − 8、只碰到角 (minX − 8, minZ − 8): 拒; 止于 minX − 9、别的维度的同一坐标: 放; 两个区的外围
     * 重叠: 报第一个区。拒的给出区 id、名字与禁圈区的框 (区 ± 8)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void createDecisionMatrix(GameTestHelper helper) {
        Level level = helper.getLevel();
        Level nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "前提: 下界已加载");
        GuardView view = view(GuardSettings.defaults(), district("abydos", 0, 0, 199, 199),
                district("millennium", 210, 0, 409, 199));

        PersonalClaimGuard.Decision inside = PersonalClaimGuard.decideCreate(view, level, null, box(50, 50, 60, 60));
        helper.assertTrue(inside.denied() && inside.hit() != null && inside.hit().districtId().equals("abydos")
                        && inside.hit().districtName().equals("abydos-name")
                        && inside.hit().zone().equals(box(-8, -8, 207, 207)),
                "区里: 拒, 报区 id、名字与禁圈区 (区 ± 8), 实为 " + inside);
        helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, null, box(-20, 50, -8, 60)).denied(),
                "碰到 minX − 8: 拒");
        helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, null, box(-8, 50, -20, 60)).denied(),
                "两角反着给也一样");
        helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, null, box(-20, -20, -8, -8)).denied(),
                "只碰到角 (minX − 8, minZ − 8): 拒");
        helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, null, box(-20, 50, -9, 60)).outcome()
                        == PersonalClaimGuard.Outcome.ALLOW,
                "止于 minX − 9: 放");
        helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, null, box(-20, -20, -9, -8)).outcome()
                        == PersonalClaimGuard.Outcome.ALLOW,
                "角外一格 (minX − 9): 放");
        helper.assertTrue(PersonalClaimGuard.decideCreate(view, nether, null, box(50, 50, 60, 60)).outcome()
                        == PersonalClaimGuard.Outcome.ALLOW,
                "别的维度的同一坐标: 放");
        PersonalClaimGuard.Decision overlap = PersonalClaimGuard.decideCreate(view, level, null,
                box(203, 50, 205, 60));
        helper.assertTrue(overlap.denied() && overlap.hit() != null && overlap.hit().districtId().equals("abydos"),
                "两个区的外围重叠: 报第一个区, 实为 " + overlap);
        PersonalClaimGuard.Decision second = PersonalClaimGuard.decideCreate(view, level, null,
                box(300, 50, 310, 60));
        helper.assertTrue(second.denied() && second.hit() != null && second.hit().districtId().equals("millennium")
                        && second.hit().zone().equals(box(202, -8, 417, 207)),
                "只碰到第二个区的外围: 报第二个区, 实为 " + second);
        helper.succeed();
    }

    /**
     * 改范围: 外面的领地扩进外围: 拒; 老领地缩小: 放; 老领地把远离区的一边往外挪 (外围那部分不变): 放; 老领地沿外围加长:
     * 拒; 压进区内的老领地只能缩; 新占的是第二个区的外围: 拒并报第二个区。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void resizeMayNotAddBanColumns(GameTestHelper helper) {
        Level level = helper.getLevel();
        GuardView view = view(GuardSettings.defaults(), district("abydos", 0, 0, 199, 199),
                district("millennium", 300, 0, 499, 199));
        List<String> wrong = new ArrayList<>();
        expect(wrong, view, level, "外面的领地扩进外围", box(-30, 50, -20, 60), box(-30, 50, -5, 60), "abydos");
        expect(wrong, view, level, "外面的领地挪一挪 (不碰外围)", box(-30, 50, -20, 60), box(-40, 50, -20, 60), null);
        expect(wrong, view, level, "压着外围的老领地缩小", box(-15, 50, -3, 60), box(-15, 50, -5, 60), null);
        expect(wrong, view, level, "老领地缩到外围之外", box(-15, 50, -3, 60), box(-15, 50, -10, 60), null);
        expect(wrong, view, level, "老领地把远离区的一边往外挪", box(-15, 50, -3, 60), box(-25, 50, -3, 60), null);
        expect(wrong, view, level, "老领地沿外围加长", box(-15, 50, -3, 60), box(-15, 50, -3, 80), "abydos");
        expect(wrong, view, level, "老领地往区里再扩一列", box(-15, 50, -3, 60), box(-15, 50, -2, 60), "abydos");
        expect(wrong, view, level, "压进区内的老领地缩小", box(10, 10, 20, 20), box(12, 12, 18, 18), null);
        expect(wrong, view, level, "压进区内的老领地扩大", box(10, 10, 20, 20), box(10, 10, 25, 20), "abydos");
        expect(wrong, view, level, "压进区内的老领地原样", box(10, 10, 20, 20), box(10, 10, 20, 20), null);
        expect(wrong, view, level, "东边老领地新占第二个区的外围", box(200, 50, 210, 60), box(200, 50, 295, 60),
                "millennium");
        helper.assertTrue(wrong.isEmpty(), "改范围的判定: " + wrong);
        helper.succeed();
    }

    private static void expect(List<String> wrong, GuardView view, Level level, String what, PlotArea old,
                               PlotArea next, String expectedDistrict) {
        PersonalClaimGuard.Decision decision = PersonalClaimGuard.decideResize(view, level, null, false, old, next);
        String actual = decision.denied() && decision.hit() != null ? decision.hit().districtId() : null;
        boolean ok = expectedDistrict == null ? decision.outcome() == PersonalClaimGuard.Outcome.ALLOW
                : decision.denied() && expectedDistrict.equals(actual);
        if (!ok) {
            wrong.add(what + ": 应为 " + (expectedDistrict == null ? "放" : "拒 (" + expectedDistrict + ")")
                    + ", 实为 " + decision);
        }
    }

    /**
     * Flan 1.11.16 resizeClaim 的新框公式: 四个角各拖一次; /flan expand 四个方向 (先按朝向取一个角当 from, 外推出 to);
     * 宽度为 1 的退化情形。与真 Flan 改完之后的范围的核对在 FlanClaimGuardGameTests.resizeRules。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void resizedBoxFollowsFlanFormula(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        formula(wrong, "拖西北角", 0, 0, 9, 9, 0, 0, -5, -5, box(-5, -5, 9, 9));
        formula(wrong, "拖东北角", 0, 0, 9, 9, 9, 0, 15, -3, box(0, -3, 15, 9));
        formula(wrong, "拖西南角", 0, 0, 9, 9, 0, 9, -2, 12, box(-2, 0, 9, 12));
        formula(wrong, "拖东南角往里", 0, 0, 9, 9, 9, 9, 4, 4, box(0, 0, 4, 4));
        formula(wrong, "expand 南 3", 0, 0, 9, 9, 9, 9, 9, 12, box(0, 0, 9, 12));
        formula(wrong, "expand 东 3", 0, 0, 9, 9, 9, 9, 12, 9, box(0, 0, 12, 9));
        formula(wrong, "expand 北 3", 0, 0, 9, 9, 0, 0, 0, -3, box(0, -3, 9, 9));
        formula(wrong, "expand 西 3", 0, 0, 9, 9, 0, 0, -3, 0, box(-3, 0, 9, 9));
        formula(wrong, "宽 1: expand 南", 5, 0, 5, 9, 5, 9, 5, 12, box(5, 0, 5, 12));
        formula(wrong, "宽 1: expand 东", 5, 0, 5, 9, 5, 9, 8, 9, box(5, 0, 8, 9));
        formula(wrong, "宽 1: expand 西", 5, 0, 5, 9, 5, 0, 2, 0, box(2, 0, 5, 9));
        helper.assertTrue(wrong.isEmpty(), "新框公式: " + wrong);
        helper.succeed();
    }

    private static void formula(List<String> wrong, String what, int minX, int minZ, int maxX, int maxZ, int fromX,
                                int fromZ, int toX, int toZ, PlotArea expected) {
        PlotArea actual = PersonalClaimGuard.resizedBox(minX, minZ, maxX, maxZ, fromX, fromZ, toX, toZ);
        if (!actual.equals(expected)) {
            wrong.add(what + ": 应为 " + expected + ", 实为 " + actual);
        }
    }

    /**
     * 认人与开关: 2 级 OP 放行 (碰到禁圈区时发金色提示); 1 级 OP、UUID 在 2 级 OP 名单里的 FakePlayer 子类、没有玩家: 拒;
     * personalClaims = false、门面 OFF、没装上: 全放; 管理员领地改范围: 放。拒绝的提示是红字, 新圈与改范围各一个键。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void actorsAndSwitches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GuardSettings defaults = GuardSettings.defaults();
        DistrictZoneSnapshot.DistrictInput abydos = district("abydos", 0, 0, 199, 199);
        GuardView view = view(defaults, abydos);
        PlotArea buffer = box(-20, 50, -8, 60);

        GameProfile opProfile = new GameProfile(UUID.randomUUID(), "Gt_Claim_Op");
        GameProfile levelOneProfile = new GameProfile(UUID.randomUUID(), "Gt_Claim_Op1");
        GameProfile fakeProfile = new GameProfile(UUID.randomUUID(), "Gt_Claim_Fake");
        try {
            server.getPlayerList().getOps().add(new ServerOpListEntry(opProfile, 2, false));
            server.getPlayerList().getOps().add(new ServerOpListEntry(levelOneProfile, 1, false));
            server.getPlayerList().getOps().add(new ServerOpListEntry(fakeProfile, 2, false));
            ServerPlayer op = new ServerPlayer(server, level, opProfile);
            ServerPlayer levelOne = new ServerPlayer(server, level, levelOneProfile);
            FakePlayer fake = new FakePlayer(level, fakeProfile);
            helper.assertTrue(op.hasPermissions(2) && !levelOne.hasPermissions(2) && fake.hasPermissions(2),
                    "前提: 2 级 OP、1 级 OP、UUID 在 2 级 OP 名单里的假玩家");

            PersonalClaimGuard.Decision exempt = PersonalClaimGuard.decideCreate(view, level, op, buffer);
            helper.assertTrue(exempt.outcome() == PersonalClaimGuard.Outcome.OP_EXEMPT && exempt.hit() != null,
                    "2 级 OP: 放行, 但记下碰到的区 (要发提示)");
            Component gold = PersonalClaimGuard.message(exempt, false);
            helper.assertTrue(gold != null && gold.getContents() instanceof TranslatableContents translatable
                            && PersonalClaimGuard.KEY_OP_EXEMPT.equals(translatable.getKey())
                            && translatable.getArgs().length == 3
                            && "abydos-name".equals(String.valueOf(translatable.getArgs()[0]))
                            && "abydos".equals(String.valueOf(translatable.getArgs()[2]))
                            && TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(gold.getStyle().getColor()),
                    "OP 的提示是金字 claim_op_exempt (区名、8、区 id)");
            helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, op, box(-40, 50, -30, 60)).outcome()
                            == PersonalClaimGuard.Outcome.ALLOW,
                    "OP 在外面圈: 放, 不提示");
            helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, levelOne, buffer).denied(),
                    "1 级 OP 不例外 (P14)");
            helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, fake, buffer).denied(),
                    "UUID 在 OP 名单里的假玩家不例外 (22.5 的教训)");
            helper.assertTrue(PersonalClaimGuard.decideCreate(view, level, null, buffer).denied(), "没有玩家: 拒");
            helper.assertTrue(PersonalClaimGuard.decideResize(view, level, op, false, box(-40, 50, -30, 60),
                            box(-40, 50, -5, 60)).outcome() == PersonalClaimGuard.Outcome.OP_EXEMPT,
                    "OP 把个人领地扩进外围: 放行并提示");
            helper.assertTrue(PersonalClaimGuard.decideResize(view, level, levelOne, true, box(0, 0, 199, 199),
                            box(0, 0, 250, 199)).outcome() == PersonalClaimGuard.Outcome.ALLOW,
                    "管理员领地 (父领地) 改范围: 不管");

            PersonalClaimGuard.Decision denied = PersonalClaimGuard.decideCreate(view, level, levelOne, buffer);
            Component red = PersonalClaimGuard.message(denied, false);
            Component redResize = PersonalClaimGuard.message(denied, true);
            helper.assertTrue(red != null && red.getContents() instanceof TranslatableContents create
                            && PersonalClaimGuard.KEY_CREATE_DENIED.equals(create.getKey())
                            && "abydos-name".equals(String.valueOf(create.getArgs()[0]))
                            && "8".equals(String.valueOf(create.getArgs()[1]))
                            && redResize != null && redResize.getContents() instanceof TranslatableContents resize
                            && PersonalClaimGuard.KEY_RESIZE_DENIED.equals(resize.getKey())
                            && TextColor.fromLegacyFormat(ChatFormatting.RED).equals(red.getStyle().getColor()),
                    "拒绝的提示是红字: 新圈 claim_create_denied、改范围 claim_resize_denied (区名、8)");
            helper.assertTrue(PersonalClaimGuard.message(PersonalClaimGuard.Decision.ALLOW, false) == null,
                    "放行不提示");

            helper.assertTrue(PersonalClaimGuard.decideCreate(view(defaults.withPersonalClaims(false), abydos), level,
                            levelOne, buffer).outcome() == PersonalClaimGuard.Outcome.ALLOW,
                    "急停 personalClaims = false: 全放");
            helper.assertTrue(PersonalClaimGuard.decideCreate(GuardView.OFF, level, levelOne, buffer).outcome()
                            == PersonalClaimGuard.Outcome.ALLOW,
                    "门面 OFF (功能关着、GameTest 默认): 全放");
            GuardView notInstalled = new GuardView(defaults, view.zones(), CreateBlockPolicy.of(defaults), false);
            helper.assertTrue(PersonalClaimGuard.decideCreate(notInstalled, level, levelOne, buffer).outcome()
                            == PersonalClaimGuard.Outcome.ALLOW,
                    "没装上: 全放");
            helper.assertTrue(PersonalClaimGuard.decideResize(view(defaults.withPersonalClaims(false), abydos), level,
                            levelOne, false, box(-40, 50, -30, 60), box(-40, 50, -5, 60)).outcome()
                            == PersonalClaimGuard.Outcome.ALLOW,
                    "急停也放行改范围");
        } finally {
            server.getPlayerList().getOps().remove(opProfile);
            server.getPlayerList().getOps().remove(levelOneProfile);
            server.getPlayerList().getOps().remove(fakeProfile);
        }
        helper.succeed();
    }
}
