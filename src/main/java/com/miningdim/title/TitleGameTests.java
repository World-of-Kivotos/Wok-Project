package com.miningdim.title;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.miningdim.core.MiningConstants;
import com.miningdim.store.MiningDb;
import com.miningdim.store.StoreTx;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.testutil.TempStoreDb;
import com.miningdim.title.network.S2CTitleSync;
import com.miningdim.title.store.SqliteTitleRepository;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 称号系统 GameTest (Title_System_DesignSpec 第十章)。
 *
 * 服务类用例全部建在 {@link TempStoreDb} 的临时统一库上, 使用各自新建的 {@link TitleDefinitions}, 不改写服务端
 * 真正生效的定义与库。需要经过 TitleSystem 真实钩子 (登录、登出、重生、显示名事件) 的用例, 把临时实现注入
 * {@link TitleServices}, 并在 finally 里恢复原门面。强断言 (删被测核心逻辑必挂, 禁永真弱校验):
 * <ol>
 *   <li>grant 按主键幂等: 第二次发放返回 ALREADY_OWNED, 库里仍只有 1 行且来源是第一次的;</li>
 *   <li>回收正在佩戴的称号会同时卸下 (库里佩戴行被删), 回收未佩戴的称号不影响佩戴;</li>
 *   <li>未拥有 / 定义缺失时 equip 分别返回 NOT_OWNED / UNKNOWN_TITLE 且不落库;</li>
 *   <li>定义被删除后持有与佩戴记录保留、显示隐藏, 定义恢复后显示复原;</li>
 *   <li>grantInTransaction 所在事务回滚后库与缓存都无残留 (事务内读到的未提交持有不写回缓存), 提交后可见;
 *       自动提交或 null 连接直接拒绝, 且先于定义检查; 外层事务开着时 grant / equip / revoke 一律拒绝;</li>
 *   <li>定义校验: 非法稀有度 / 颜色 / 色标数 / 缺 text 的条目被跳过, 合法条目 (含 style 覆盖) 照常加载;</li>
 *   <li>TierPalette 逐字渐变首尾恰为首尾色标、中点恰为中间色标, 按码点拆分;</li>
 *   <li>佩戴后 NameFormat 与 TabListNameFormat 产出的显示名带 {@code [称号] } 前缀, 卸下后消失;</li>
 *   <li>真实登录事件把库里的佩戴载入缓存并补刷显示名与 Tab 名, 真实登出事件移出缓存; 提交的数据关库重开仍在;</li>
 *   <li>首批 14 个称号定义随数据包加载, 稀有度与设计表一致, 两种语言的文字与说明键齐全;</li>
 *   <li>S2CTitleSync 编解码往返 (含 null 称号);</li>
 *   <li>经真实 PlayerList.respawn 重生后卸下称号, 仍向客户端广播 Tab 名恢复原版;</li>
 *   <li>队伍兜底重算: 佩戴称号的玩家加入 / 离开队伍后 Tab 名随之更新, 没变化不发包, 未佩戴的玩家不碰。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TitleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "title";

    private static final ResourceLocation ALPHA = new ResourceLocation(MiningConstants.MODID, "test/alpha");
    private static final ResourceLocation BETA = new ResourceLocation(MiningConstants.MODID, "test/beta");
    private static final ResourceLocation UNKNOWN = new ResourceLocation(MiningConstants.MODID, "test/unknown");
    private static final String ALPHA_TEXT = "阿尔法";
    private static final String BETA_TEXT = "贝塔称号";
    /** Tab 显示名更新包里显示名为 null (交还原版显示) 时的记法。 */
    private static final String NO_TAB_NAME = "<vanilla>";

    private TitleGameTests() {
    }

    // ---- 1. 幂等发放 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantIsIdempotentAndKeepsFirstSource(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            TitleService service = newService(helper, connection, testDefinitions());
            UUID offline = UUID.randomUUID();

            GrantResult first = service.grant(offline, ALPHA, TitleSource.ACHIEVEMENT, "mining/ore_codex");
            GrantResult second = service.grant(offline, ALPHA, TitleSource.ADMIN, "op");
            helper.assertTrue(first == GrantResult.GRANTED, "首次发放必须是 GRANTED, 实为 " + first);
            helper.assertTrue(second == GrantResult.ALREADY_OWNED, "重复发放必须是 ALREADY_OWNED, 实为 " + second);
            helper.assertTrue(countOwnedRows(connection, offline) == 1,
                    "重复发放后库里只能有 1 行, 实为 " + countOwnedRows(connection, offline));
            String source = singleText(connection, "SELECT source FROM title_owned WHERE player_uuid=?", offline);
            String sourceRef = singleText(connection, "SELECT source_ref FROM title_owned WHERE player_uuid=?", offline);
            helper.assertTrue("achievement".equals(source), "重复发放不得覆盖最早的来源, 实为 " + source);
            helper.assertTrue("mining/ore_codex".equals(sourceRef), "重复发放不得覆盖最早的 source_ref, 实为 " + sourceRef);
            helper.assertTrue(service.owned(offline).equals(Set.of(ALPHA)),
                    "离线玩家的持有应从库里读到 {alpha}, 实为 " + service.owned(offline));

            GrantResult unknown = service.grant(offline, UNKNOWN, TitleSource.ADMIN, null);
            helper.assertTrue(unknown == GrantResult.UNKNOWN_TITLE, "未定义的称号必须拒发, 实为 " + unknown);
            helper.assertTrue(countOwnedRows(connection, offline) == 1, "拒发的称号不得落库");
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 2. 回收正在佩戴的称号 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void revokingEquippedTitleAlsoUnequips(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            TitleService service = newService(helper, connection, testDefinitions());
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();

            service.grant(player, ALPHA, TitleSource.ADMIN, null);
            service.grant(player, BETA, TitleSource.ADMIN, null);
            EquipResult equipped = service.equip(player, ALPHA);
            helper.assertTrue(equipped == EquipResult.EQUIPPED, "已拥有的称号必须能佩戴, 实为 " + equipped);
            helper.assertTrue(ALPHA.toString().equals(equippedRow(connection, uuid)),
                    "佩戴必须落库, 实为 " + equippedRow(connection, uuid));

            helper.assertTrue(service.revoke(uuid, BETA), "回收已拥有的称号必须返回 true");
            helper.assertTrue(service.equipped(uuid).equals(Optional.of(ALPHA)),
                    "回收一个没在佩戴的称号不得影响当前佩戴, 实为 " + service.equipped(uuid));

            helper.assertTrue(service.revoke(uuid, ALPHA), "回收正在佩戴的称号必须返回 true");
            helper.assertTrue(service.equipped(uuid).isEmpty(),
                    "回收正在佩戴的称号后必须自动卸下, 实为 " + service.equipped(uuid));
            helper.assertTrue(equippedRow(connection, uuid) == null, "自动卸下必须删掉库里的佩戴行");
            helper.assertTrue(service.owned(uuid).isEmpty(), "两个称号都回收后持有应为空, 实为 " + service.owned(uuid));
            helper.assertTrue(countOwnedRows(connection, uuid) == 0, "回收必须删掉库里的持有行");
            helper.assertTrue(service.displayPrefix(uuid) == null, "卸下后显示前缀必须为空");
            helper.assertTrue(!service.revoke(uuid, ALPHA), "再次回收同一称号必须返回 false");
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 3. equip 的服务端校验 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void equipRejectsUnownedAndUndefinedTitles(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            TitleService service = newService(helper, connection, testDefinitions());
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();

            EquipResult undefined = service.equip(player, UNKNOWN);
            helper.assertTrue(undefined == EquipResult.UNKNOWN_TITLE, "定义缺失必须返回 UNKNOWN_TITLE, 实为 " + undefined);
            EquipResult unowned = service.equip(player, ALPHA);
            helper.assertTrue(unowned == EquipResult.NOT_OWNED, "未拥有必须返回 NOT_OWNED, 实为 " + unowned);
            helper.assertTrue(service.equipped(uuid).isEmpty(), "校验失败不得改变佩戴");
            helper.assertTrue(equippedRow(connection, uuid) == null, "校验失败不得落库");

            service.grant(player, ALPHA, TitleSource.EVENT, "test");
            helper.assertTrue(service.equip(player, ALPHA) == EquipResult.EQUIPPED, "发放后必须能佩戴");
            EquipResult cleared = service.equip(player, null);
            helper.assertTrue(cleared == EquipResult.UNEQUIPPED, "null 表示卸下, 实为 " + cleared);
            helper.assertTrue(service.equipped(uuid).isEmpty() && equippedRow(connection, uuid) == null,
                    "卸下必须同时清掉缓存与库");
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 4. 定义删除 / 恢复 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removedDefinitionKeepsOwnershipButHidesDisplayUntilRestored(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            TitleDefinitions definitions = testDefinitions();
            TitleService service = newService(helper, connection, definitions);
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();
            service.loadPlayer(player);
            service.grant(player, BETA, TitleSource.MARRIAGE, null);
            service.equip(player, BETA);
            String expectedPrefix = "[" + BETA_TEXT + "] ";
            helper.assertTrue(prefixText(service, uuid).equals(expectedPrefix),
                    "佩戴后显示前缀应为 " + expectedPrefix + ", 实为 " + prefixText(service, uuid));

            Map<ResourceLocation, TitleDefinition> withoutBeta = new LinkedHashMap<>(testDefinitionMap());
            withoutBeta.remove(BETA);
            definitions.install(withoutBeta);

            helper.assertTrue(service.owned(uuid).contains(BETA), "定义删除后持有记录必须保留");
            helper.assertTrue(countOwnedRows(connection, uuid) == 1, "定义删除不得删库里的持有行");
            helper.assertTrue(service.equipped(uuid).equals(Optional.of(BETA)), "定义删除不得改动佩戴记录");
            helper.assertTrue(service.displayPrefix(uuid) == null && service.displayBadge(uuid) == null,
                    "定义删除后显示必须跳过");
            helper.assertTrue(service.equip(player, BETA) == EquipResult.UNKNOWN_TITLE,
                    "定义缺失时不得再次佩戴");

            definitions.install(testDefinitionMap());
            helper.assertTrue(prefixText(service, uuid).equals(expectedPrefix),
                    "定义恢复后显示必须自动复原, 实为 " + prefixText(service, uuid));
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 5. grantInTransaction ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantInTransactionLeavesNothingAfterRollback(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            TitleService service = newService(helper, connection, testDefinitions());
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();
            service.loadPlayer(player);
            helper.assertTrue(service.owned(uuid).isEmpty(), "前置: 新玩家持有为空");

            AtomicReference<GrantResult> insideRollback = new AtomicReference<>();
            AtomicReference<Set<ResourceLocation>> ownedInsideTx = new AtomicReference<>();
            List<String> joinedOuterTx = new ArrayList<>();
            boolean rolledBack = false;
            try {
                StoreTx.run(connection, () -> {
                    insideRollback.set(service.grantInTransaction(connection, uuid, ALPHA,
                            TitleSource.POINT_SHOP, "shop:alpha"));
                    // 同一连接看得到未提交的持有; owned() 此时只返回、不写回缓存 (inOpenTransaction 守卫)。
                    ownedInsideTx.set(service.owned(uuid));
                    // 写穿操作不得并入外层事务: 它们的缓存、提示与显示刷新无法随回滚撤销, 必须直接拒绝。
                    if (!rejectedInsideTransaction(() -> service.equip(player, ALPHA))) {
                        joinedOuterTx.add("equip");
                    }
                    if (!rejectedInsideTransaction(() -> service.grant(player, BETA, TitleSource.ADMIN, null))) {
                        joinedOuterTx.add("grant(ServerPlayer)");
                    }
                    if (!rejectedInsideTransaction(
                            () -> service.grant(UUID.randomUUID(), BETA, TitleSource.ADMIN, null))) {
                        joinedOuterTx.add("grant(UUID)");
                    }
                    if (!rejectedInsideTransaction(() -> service.revoke(uuid, ALPHA))) {
                        joinedOuterTx.add("revoke");
                    }
                    throw new SimulatedChargeFailure();
                });
            } catch (SimulatedChargeFailure expected) {
                rolledBack = true;
            }
            helper.assertTrue(rolledBack, "模拟扣点失败必须让外层事务回滚");
            helper.assertTrue(insideRollback.get() == GrantResult.GRANTED,
                    "事务内的发放本身应返回 GRANTED, 实为 " + insideRollback.get());
            helper.assertTrue(ownedInsideTx.get() != null && ownedInsideTx.get().contains(ALPHA),
                    "事务内经同一连接应读到未提交的持有, 实为 " + ownedInsideTx.get());
            helper.assertTrue(joinedOuterTx.isEmpty(),
                    "外层事务开着时这些写穿操作必须抛 IllegalStateException, 实际放行了 " + joinedOuterTx);
            helper.assertTrue(countOwnedRows(connection, uuid) == 0,
                    "回滚后库里不得残留持有行, 实为 " + countOwnedRows(connection, uuid));
            helper.assertTrue(service.owned(uuid).isEmpty(),
                    "回滚后缓存不得残留称号 (事务内的读取不得写回缓存), 实为 " + service.owned(uuid));
            helper.assertTrue(service.equipped(uuid).isEmpty() && equippedRow(connection, uuid) == null,
                    "被拒的事务内 equip 不得改动缓存与库, 实为 " + service.equipped(uuid));

            GrantResult committed = StoreTx.call(connection, () -> service.grantInTransaction(connection, uuid, ALPHA,
                    TitleSource.POINT_SHOP, "shop:alpha"));
            helper.assertTrue(committed == GrantResult.GRANTED, "提交路径应返回 GRANTED, 实为 " + committed);
            helper.assertTrue(countOwnedRows(connection, uuid) == 1, "提交后库里必须有持有行");
            helper.assertTrue(service.owned(uuid).contains(ALPHA), "提交后缓存重载必须看到新称号");
            helper.assertTrue(service.equip(player, ALPHA) == EquipResult.EQUIPPED, "事务发放的称号提交后可佩戴");

            boolean autoCommitRejected = false;
            try {
                service.grantInTransaction(connection, uuid, BETA, TitleSource.POINT_SHOP, null);
            } catch (IllegalStateException expected) {
                autoCommitRejected = true;
            }
            helper.assertTrue(autoCommitRejected, "连接不在事务中时 grantInTransaction 必须拒绝");
            helper.assertTrue(!service.owned(uuid).contains(BETA), "被拒绝的事务发放不得落库");

            // 事务校验先于定义检查: 没开事务的接线错误不能被 UNKNOWN_TITLE 这种正常拒发结果盖过去。
            boolean unknownStillRejected = false;
            try {
                service.grantInTransaction(connection, uuid, UNKNOWN, TitleSource.POINT_SHOP, null);
            } catch (IllegalStateException expected) {
                unknownStillRejected = true;
            }
            helper.assertTrue(unknownStillRejected, "自动提交连接上发未定义的称号也必须抛 IllegalStateException");
            boolean nullTxRejected = false;
            try {
                service.grantInTransaction(null, uuid, UNKNOWN, TitleSource.POINT_SHOP, null);
            } catch (IllegalArgumentException expected) {
                nullTxRejected = true;
            }
            helper.assertTrue(nullTxRejected, "tx 为 null 时必须先于定义检查直接拒绝");
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 6. 定义校验 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void definitionValidationSkipsOnlyBadEntries(GameTestHelper helper) {
        Map<ResourceLocation, JsonElement> parsed = new LinkedHashMap<>();
        parsed.put(id("good_plain"), json("{\"text\":{\"translate\":\"title.miningdim.mining.ore_codex\"},"
                + "\"rarity\":\"gold\"}"));
        parsed.put(id("good_gradient"), json("{\"text\":{\"text\":\"婚\"},\"rarity\":\"silver\","
                + "\"style\":{\"gradient\":[\"#FF0000\",\"#0000FF\"],\"bold\":true},\"sort\":5}"));
        parsed.put(id("good_color"), json("{\"text\":{\"text\":\"x\"},\"rarity\":\"legend\","
                + "\"style\":{\"color\":\"#123456\"}}"));
        parsed.put(id("bad_rarity"), json("{\"text\":{\"text\":\"x\"},\"rarity\":\"mythic\"}"));
        parsed.put(id("bad_color_digits"), json("{\"text\":{\"text\":\"x\"},\"rarity\":\"gold\","
                + "\"style\":{\"color\":\"#12345\"}}"));
        parsed.put(id("bad_color_name"), json("{\"text\":{\"text\":\"x\"},\"rarity\":\"gold\","
                + "\"style\":{\"color\":\"red\"}}"));
        parsed.put(id("bad_gradient_short"), json("{\"text\":{\"text\":\"x\"},\"rarity\":\"gold\","
                + "\"style\":{\"gradient\":[\"#FFFFFF\"]}}"));
        parsed.put(id("bad_gradient_long"), json("{\"text\":{\"text\":\"x\"},\"rarity\":\"gold\","
                + "\"style\":{\"gradient\":[\"#FFFFFF\",\"#000000\",\"#111111\",\"#222222\"]}}"));
        parsed.put(id("bad_gradient_stop"), json("{\"text\":{\"text\":\"x\"},\"rarity\":\"gold\","
                + "\"style\":{\"gradient\":[\"#FFFFFF\",\"#GGGGGG\"]}}"));
        parsed.put(id("bad_both_styles"), json("{\"text\":{\"text\":\"x\"},\"rarity\":\"gold\","
                + "\"style\":{\"color\":\"#FFFFFF\",\"gradient\":[\"#FFFFFF\",\"#000000\"]}}"));
        parsed.put(id("bad_missing_text"), json("{\"rarity\":\"gold\"}"));
        parsed.put(id("bad_not_object"), new JsonPrimitive("oops"));

        Map<ResourceLocation, TitleDefinition> loaded = TitleDefinitionLoader.parseAll(parsed);
        helper.assertTrue(loaded.keySet().equals(Set.of(id("good_plain"), id("good_gradient"), id("good_color"))),
                "只有三条合法定义应被加载, 实为 " + loaded.keySet());

        TitleDefinition plain = loaded.get(id("good_plain"));
        helper.assertTrue(plain.rarity() == TierPalette.GOLD && plain.sort() == TierPalette.GOLD.defaultSort()
                        && !plain.bold() && plain.colors().length == 1 && plain.colors()[0] == 0xFFCC33,
                "无 style 时应沿用金档色板与默认排序, 实为 " + plain);
        TitleDefinition gradient = loaded.get(id("good_gradient"));
        helper.assertTrue(gradient.isGradient() && gradient.bold() && gradient.sort() == 5
                        && gradient.colors().length == 2 && gradient.colors()[0] == 0xFF0000
                        && gradient.colors()[1] == 0x0000FF,
                "style.gradient/bold/sort 覆盖必须生效, 实为 " + gradient);
        TitleDefinition color = loaded.get(id("good_color"));
        helper.assertTrue(!color.isGradient() && color.bold() && color.colors()[0] == 0x123456,
                "style.color 覆盖为单色、粗体沿用传说档, 实为 " + color);
        helper.succeed();
    }

    // ---- 7. 七档色板与逐字渐变 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tierPaletteGradientHitsStopsPerCodePoint(GameTestHelper helper) {
        helper.assertTrue(TierPalette.values().length == 7, "色板必须恰好七档");
        helper.assertTrue(TierPalette.byId("DIAMOND").equals(Optional.of(TierPalette.DIAMOND))
                        && TierPalette.byId("mythic").isEmpty(),
                "byId 应大小写不敏感且未知 id 返回空");
        helper.assertTrue(!TierPalette.BRONZE.isGradient() && !TierPalette.BRONZE.bold()
                        && TierPalette.BRONZE.primaryColor() == 0xC8834A
                        && TierPalette.BRONZE.legacyCode().equals(ChatFormatting.GOLD.toString()),
                "铜档应为单色 #C8834A、不加粗、降级码 §6");
        helper.assertTrue(TierPalette.DIAMOND.isGradient() && TierPalette.DIAMOND.bold()
                        && TierPalette.DIAMOND.legacyCode().equals(
                        ChatFormatting.AQUA.toString() + ChatFormatting.BOLD),
                "钻石档应为渐变、加粗、降级码 §b§l");

        int[] stops = TierPalette.DIAMOND.stops();
        MutableComponent rendered = TierPalette.gradient("ABCDE", stops, true);
        List<Component> pieces = rendered.getSiblings();
        helper.assertTrue(pieces.size() == 5, "五个字必须拆成五段, 实为 " + pieces.size());
        helper.assertTrue(colorOf(pieces.get(0)) == 0x6FF2FF, "首字必须恰为首色标, 实为 " + hex(colorOf(pieces.get(0))));
        helper.assertTrue(colorOf(pieces.get(2)) == 0x7FB0FF, "中点必须恰为中间色标, 实为 " + hex(colorOf(pieces.get(2))));
        helper.assertTrue(colorOf(pieces.get(4)) == 0xD59CFF, "末字必须恰为末色标, 实为 " + hex(colorOf(pieces.get(4))));
        int between = colorOf(pieces.get(1));
        helper.assertTrue(between != 0x6FF2FF && between != 0x7FB0FF, "相邻色标之间必须插值出新颜色");
        for (Component piece : pieces) {
            helper.assertTrue(piece.getStyle().isBold(), "渐变档每个字都必须加粗");
        }
        helper.assertTrue("ABCDE".equals(rendered.getString()), "拆字不得改变文字, 实为 " + rendered.getString());

        // U+20000 是代理对 (两个 char), 必须作为一个字上色, 不能被劈成两半。
        String supplementary = new String(Character.toChars(0x20000));
        MutableComponent mixed = TierPalette.gradient("字" + supplementary + "字", stops, false);
        helper.assertTrue(mixed.getSiblings().size() == 3, "按码点拆分应得三段, 实为 " + mixed.getSiblings().size());
        helper.assertTrue(mixed.getSiblings().get(1).getString().equals(supplementary), "代理对必须完整保留在同一段");

        MutableComponent single = TierPalette.gradient("字", stops, false);
        helper.assertTrue(single.getSiblings().size() == 1 && colorOf(single.getSiblings().get(0)) == 0x6FF2FF,
                "单字取首色标");
        helper.succeed();
    }

    // ---- 8. 聊天与 Tab 显示 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void equippedTitleReachesChatAndTabListNames(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        ITitleService previous = currentFacade();
        try {
            TitleService service = newService(helper, connection, testDefinitions());
            TitleServices.registerTitleService(service);
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            String name = player.getGameProfile().getName();
            service.grant(player, BETA, TitleSource.ADMIN, null);
            service.equip(player, BETA);

            String prefix = "[" + BETA_TEXT + "] ";
            String display = player.getDisplayName().getString();
            helper.assertTrue(display.startsWith(prefix) && display.endsWith(name),
                    "佩戴后聊天显示名应为 " + prefix + name + ", 实为 " + display);
            Component tab = player.getTabListDisplayName();
            helper.assertTrue(tab != null && tab.getString().equals(prefix + name),
                    "佩戴后 Tab 名应为 " + prefix + name + ", 实为 " + (tab == null ? null : tab.getString()));

            service.equip(player, null);
            helper.assertTrue(player.getDisplayName().getString().equals(name),
                    "卸下后聊天显示名应恢复为 " + name + ", 实为 " + player.getDisplayName().getString());
            helper.assertTrue(player.getTabListDisplayName() == null, "卸下后 Tab 名应回到原版默认 (null)");
        } finally {
            restoreFacade(previous);
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 9. 登录 / 登出事件与落盘 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginLoadsLogoutEvictsAndDataSurvivesReopen(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        MinecraftServer server = helper.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "title-mock-player");
        UUID uuid = profile.getId();
        try {
            Path dbPath = dir.resolve("titles.db");
            Connection first = TempStoreDb.openUnified(dbPath);
            ITitleService previous = currentFacade();
            try {
                SqliteTitleRepository repository = new SqliteTitleRepository(first);
                repository.insertOwned(uuid, ALPHA, TitleSource.ACHIEVEMENT, "x", 1L);
                repository.setEquipped(uuid, ALPHA);
                TitleService service = new TitleService(repository, testDefinitions(), server);
                TitleServices.registerTitleService(service);
                helper.assertTrue(service.displayPrefix(uuid) == null, "未加载进缓存前, 显示路径不得查库");

                // placeNewPlayer 派发真实的 PlayerLoggedInEvent, 由 TitleSystem 的登录钩子把库里的数据载入注入的门面。
                ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, profile);
                String expected = "[" + ALPHA_TEXT + "] " + profile.getName();
                helper.assertTrue(service.cachedPlayerCount() == 1,
                        "登录事件后缓存应有 1 名玩家, 实为 " + service.cachedPlayerCount());
                helper.assertTrue(("[" + ALPHA_TEXT + "] ").equals(prefixText(service, uuid)),
                        "登录事件后应显示库里的佩戴, 实为 " + prefixText(service, uuid));
                helper.assertTrue(expected.equals(player.getDisplayName().getString()),
                        "登录钩子必须补刷聊天显示名, 实为 " + player.getDisplayName().getString());
                Component tab = player.getTabListDisplayName();
                helper.assertTrue(tab != null && expected.equals(tab.getString()),
                        "登录钩子必须补刷 Tab 名, 实为 " + (tab == null ? null : tab.getString()));

                // PlayerList.remove 派发真实的 PlayerLoggedOutEvent, 由登出钩子移出缓存。
                server.getPlayerList().remove(player);
                helper.assertTrue(service.cachedPlayerCount() == 0,
                        "登出事件后缓存必须移出该玩家, 实为 " + service.cachedPlayerCount());
                helper.assertTrue(service.displayPrefix(uuid) == null, "登出后显示路径不再有该玩家");
                helper.assertTrue(service.equipped(uuid).equals(Optional.of(ALPHA)), "登出只丢缓存, 库里佩戴仍在");
            } finally {
                restoreFacade(previous);
                MiningDb.close(first);
            }

            Connection second = TempStoreDb.openUnified(dbPath);
            try {
                SqliteTitleRepository reopened = new SqliteTitleRepository(second);
                helper.assertTrue(reopened.owned(uuid).equals(Set.of(ALPHA)), "关库重开后持有必须仍在");
                helper.assertTrue(reopened.equipped(uuid).equals(Optional.of(ALPHA)), "关库重开后佩戴必须仍在");
            } finally {
                MiningDb.close(second);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 10. 首批 14 个称号 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bundledTitlesLoadWithBothLanguages(GameTestHelper helper) throws IOException {
        helper.assertTrue(TitleServices.isRegistered(), "GameTest 服务端启动后称号门面必须已注入");
        ITitleService titles = TitleServices.titleService();
        Map<String, TierPalette> expected = new LinkedHashMap<>();
        expected.put("mining/ore_codex", TierPalette.GOLD);
        expected.put("mining/blocks_100k", TierPalette.DIAMOND);
        expected.put("mining/hard_active_100h", TierPalette.MASTER);
        expected.put("combat/long_shot", TierPalette.GOLD);
        expected.put("combat/star_10", TierPalette.DIAMOND);
        expected.put("combat/solo_star_9", TierPalette.LEGEND);
        expected.put("profession/max_level", TierPalette.PLATINUM);
        expected.put("profession/journal_100", TierPalette.DIAMOND);
        expected.put("profession/all_max", TierPalette.MASTER);
        expected.put("economy/tycoon", TierPalette.MASTER);
        expected.put("economy/lucky_case", TierPalette.DIAMOND);
        expected.put("economy/all_in", TierPalette.BRONZE);
        expected.put("social/daily_clear_60", TierPalette.DIAMOND);
        expected.put("meta/count_40", TierPalette.PLATINUM);

        JsonObject zh = readLang("zh_cn");
        JsonObject en = readLang("en_us");
        for (Map.Entry<String, TierPalette> entry : expected.entrySet()) {
            ResourceLocation id = new ResourceLocation(MiningConstants.MODID, entry.getKey());
            TitleDefinition definition = titles.definition(id).orElse(null);
            helper.assertTrue(definition != null, "数据包里缺少称号定义 " + id);
            helper.assertTrue(definition.rarity() == entry.getValue(),
                    id + " 的稀有度应为 " + entry.getValue().id() + ", 实为 " + definition.rarity().id());
            String textKey = translateKey(definition.text());
            String expectedKey = "title.miningdim." + entry.getKey().replace('/', '.');
            helper.assertTrue(expectedKey.equals(textKey), id + " 的文字键应为 " + expectedKey + ", 实为 " + textKey);
            helper.assertTrue(definition.description() != null
                            && (expectedKey + ".desc").equals(translateKey(definition.description())),
                    id + " 的说明键应为 " + expectedKey + ".desc");
            for (String key : List.of(expectedKey, expectedKey + ".desc")) {
                helper.assertTrue(hasText(zh, key) && hasText(en, key), "zh_cn / en_us 必须都有非空键 " + key);
            }
            Component badge = titles.badge(id).orElse(null);
            helper.assertTrue(badge != null, id + " 定义存在时必须能渲染徽记");
            if (definition.isGradient()) {
                helper.assertTrue(badge.getString().equals("[" + TitleRenderer.resolveText(definition.text()) + "]"),
                        id + " 的渐变徽记应为 [解析后的文字], 实为 " + badge.getString());
            } else {
                helper.assertTrue(badge.getSiblings().size() == 2
                                && expectedKey.equals(translateKey(badge.getSiblings().get(0))),
                        id + " 的单色徽记必须保留 translate, 交给客户端按其语言显示");
            }
        }
        helper.assertTrue("矿物学者".equals(zh.get("title.miningdim.mining.ore_codex").getAsString()),
                "矿脉图鉴称号的中文文案应为 矿物学者");

        // 渐变档要在服务端拆字: 专用服务端不加载 assets 语言文件时, 必须退回模组自带的中文表而不是键名。
        ResourceLocation diamond = new ResourceLocation(MiningConstants.MODID, "mining/blocks_100k");
        String diamondKey = "title.miningdim.mining.blocks_100k";
        String resolved = TitleRenderer.resolveText(titles.definition(diamond).orElseThrow().text());
        String expectedText = Language.getInstance().has(diamondKey)
                ? Language.getInstance().getOrDefault(diamondKey)
                : "地脉行者";
        helper.assertTrue(expectedText.equals(resolved), "渐变称号文字应解析为 " + expectedText + ", 实为 " + resolved);
        helper.succeed();
    }

    // ---- 11. 同步包 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void titleSyncPacketRoundTrips(GameTestHelper helper) {
        TitleDefinition beta = testDefinitionMap().get(BETA);
        Component badge = TitleRenderer.renderBadge(beta);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        S2CTitleSync.encode(new S2CTitleSync(42, badge), buffer);
        S2CTitleSync decoded = S2CTitleSync.decode(buffer);
        helper.assertTrue(decoded.entityId() == 42, "entityId 往返应为 42, 实为 " + decoded.entityId());
        helper.assertTrue(decoded.title() != null && Component.Serializer.toJson(decoded.title())
                        .equals(Component.Serializer.toJson(badge)),
                "称号 Component 往返必须逐片段 (含颜色与粗体) 一致");
        helper.assertTrue(buffer.readableBytes() == 0, "解码必须读完整个包");

        FriendlyByteBuf cleared = new FriendlyByteBuf(Unpooled.buffer());
        S2CTitleSync.encode(new S2CTitleSync(7, null), cleared);
        S2CTitleSync decodedCleared = S2CTitleSync.decode(cleared);
        helper.assertTrue(decodedCleared.entityId() == 7 && decodedCleared.title() == null,
                "null 称号 (卸下) 必须原样往返");
        helper.succeed();
    }

    // ---- 12. 重生后卸下仍要刷新 Tab ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unequipAfterRespawnStillUpdatesTabList(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ITitleService previous = currentFacade();
        ServerPlayer respawned = null;
        try {
            TitleService service = newService(helper, connection, testDefinitions());
            TitleServices.registerTitleService(service);
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();
            service.grant(player, ALPHA, TitleSource.ADMIN, null);
            helper.assertTrue(service.equip(player, ALPHA) == EquipResult.EQUIPPED, "前置: 重生前佩戴成功");

            // 真实的 PlayerList.respawn 换一个新实体并派发 PlayerRespawnEvent; 连接改指新实体,
            // 与 ServerGamePacketListenerImpl#handleClientCommand 的做法一致。
            respawned = server.getPlayerList().respawn(player, false);
            respawned.connection.player = respawned;
            helper.assertTrue(respawned != player, "前置: 重生必须换一个新的玩家实体");
            EmbeddedChannel channel = channelOf(respawned);
            tabNameUpdates(channel, uuid);

            helper.assertTrue(service.equip(respawned, null) == EquipResult.UNEQUIPPED, "重生后应能卸下称号");
            List<String> updates = tabNameUpdates(channel, uuid);
            helper.assertTrue(updates.equals(List.of(NO_TAB_NAME)),
                    "重生后卸下称号必须广播 Tab 名恢复原版 (否则旧称号一直挂在所有人的 Tab 里), 实为 " + updates);
        } finally {
            if (respawned != null) {
                server.getPlayerList().remove(respawned);
            }
            restoreFacade(previous);
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 13. 队伍变化兜底重算 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tabRecheckPicksUpTeamChangesOfTitledPlayers(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ServerScoreboard scoreboard = server.getScoreboard();
        ITitleService previous = currentFacade();
        PlayerTeam team = scoreboard.addPlayerTeam("mdtitle" + UUID.randomUUID().toString().substring(0, 8));
        ServerPlayer player = null;
        try {
            TitleService service = newService(helper, connection, testDefinitions());
            TitleServices.registerTitleService(service);
            // 独立名字: 队伍成员按名字登记, 不能与其他用例共用的 test-mock-player 混在一起。
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), "title-team-mock"));
            UUID uuid = player.getUUID();
            String name = player.getScoreboardName();
            String prefix = "[" + ALPHA_TEXT + "] ";
            service.grant(player, ALPHA, TitleSource.ADMIN, null);
            service.equip(player, ALPHA);
            EmbeddedChannel channel = channelOf(player);

            team.setPlayerPrefix(Component.literal("<R>"));
            scoreboard.addPlayerToTeam(name, team);
            tabNameUpdates(channel, uuid);
            service.refreshTitledTabNames(List.of(player));
            List<String> joined = tabNameUpdates(channel, uuid);
            helper.assertTrue(joined.equals(List.of(prefix + "<R>" + name)),
                    "加入队伍后重算必须把队伍前缀补进 Tab 名并广播, 实为 " + joined);

            service.refreshTitledTabNames(List.of(player));
            List<String> unchanged = tabNameUpdates(channel, uuid);
            helper.assertTrue(unchanged.isEmpty(), "队伍没变时重算不得发包, 实为 " + unchanged);

            scoreboard.removePlayerFromTeam(name, team);
            service.refreshTitledTabNames(List.of(player));
            List<String> left = tabNameUpdates(channel, uuid);
            helper.assertTrue(left.equals(List.of(prefix + name)),
                    "离开队伍后重算必须去掉队伍前缀并广播, 实为 " + left);

            service.equip(player, null);
            tabNameUpdates(channel, uuid);
            scoreboard.addPlayerToTeam(name, team);
            service.refreshTitledTabNames(List.of(player));
            List<String> untitled = tabNameUpdates(channel, uuid);
            helper.assertTrue(untitled.isEmpty(), "未佩戴称号的玩家交给原版处理队伍样式, 重算不得碰他, 实为 " + untitled);
        } finally {
            scoreboard.removePlayerTeam(team);
            if (player != null) {
                server.getPlayerList().remove(player);
            }
            restoreFacade(previous);
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 工具 ----

    /** 测试专用定义: alpha 为金档单色 (文字 translate 以外的字面量, 便于断言), beta 为钻石档渐变。 */
    private static Map<ResourceLocation, TitleDefinition> testDefinitionMap() {
        Map<ResourceLocation, TitleDefinition> map = new LinkedHashMap<>();
        map.put(ALPHA, new TitleDefinition(ALPHA, Component.literal(ALPHA_TEXT), TierPalette.GOLD,
                List.of(), null, null, TierPalette.GOLD.defaultSort()));
        map.put(BETA, new TitleDefinition(BETA, Component.literal(BETA_TEXT), TierPalette.DIAMOND,
                List.of(), null, Component.literal("test"), TierPalette.DIAMOND.defaultSort()));
        return map;
    }

    private static TitleDefinitions testDefinitions() {
        TitleDefinitions definitions = new TitleDefinitions();
        definitions.install(testDefinitionMap());
        return definitions;
    }

    private static TitleService newService(GameTestHelper helper, Connection connection,
                                           TitleDefinitions definitions) {
        return new TitleService(new SqliteTitleRepository(connection), definitions, helper.getLevel().getServer());
    }

    /** 用例临时替换全局门面前记下原值; 从未注入时为 null。 */
    @Nullable
    private static ITitleService currentFacade() {
        return TitleServices.isRegistered() ? TitleServices.titleService() : null;
    }

    /** 恢复用例开始前的全局门面, 不让临时库上的实现泄漏给同批其他用例。 */
    private static void restoreFacade(@Nullable ITitleService previous) {
        if (previous == null) {
            TitleServices.reset();
        } else {
            TitleServices.registerTitleService(previous);
        }
    }

    /** 在外层事务体内调用一个写穿操作, 返回它是否按约定抛了 IllegalStateException。 */
    private static boolean rejectedInsideTransaction(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }

    private static EmbeddedChannel channelOf(ServerPlayer player) {
        return (EmbeddedChannel) player.connection.connection.channel();
    }

    /**
     * 读空 mock 连接的出站队列, 按发送顺序返回其中针对 player 的 Tab 显示名更新 (恢复原版即显示名为 null 时记为
     * {@link #NO_TAB_NAME})。关键步骤之前也用它丢弃此前积压的包。
     */
    private static List<String> tabNameUpdates(EmbeddedChannel channel, UUID player) {
        List<String> updates = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundPlayerInfoUpdatePacket packet
                        && packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) {
                    for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                        if (entry.profileId().equals(player)) {
                            updates.add(entry.displayName() == null ? NO_TAB_NAME : entry.displayName().getString());
                        }
                    }
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return updates;
    }

    private static String prefixText(ITitleService service, UUID player) {
        Component prefix = service.displayPrefix(player);
        return prefix == null ? "<null>" : prefix.getString();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MiningConstants.MODID, "test/" + path);
    }

    private static JsonElement json(String raw) {
        return JsonParser.parseString(raw);
    }

    private static int colorOf(Component piece) {
        TextColor color = piece.getStyle().getColor();
        return color == null ? -1 : color.getValue();
    }

    private static String hex(int color) {
        return String.format("#%06X", color);
    }

    private static String translateKey(Component component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : null;
    }

    private static boolean hasText(JsonObject lang, String key) {
        return lang.has(key) && lang.get(key).isJsonPrimitive() && !lang.get(key).getAsString().isBlank();
    }

    private static JsonObject readLang(String language) throws IOException {
        String path = "assets/" + MiningConstants.MODID + "/lang/" + language + ".json";
        InputStream stream = TitleGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        }
    }

    private static int countOwnedRows(Connection connection, UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM title_owned WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String equippedRow(Connection connection, UUID player) {
        return singleText(connection, "SELECT title_id FROM title_equipped WHERE player_uuid=?", player);
    }

    private static String singleText(Connection connection, String sql, UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 模拟"扣成就点失败"让外层事务回滚; 专用类型, 不会误吞断言失败。 */
    private static final class SimulatedChargeFailure extends RuntimeException {
        SimulatedChargeFailure() {
            super("simulated charge failure");
        }
    }
}
