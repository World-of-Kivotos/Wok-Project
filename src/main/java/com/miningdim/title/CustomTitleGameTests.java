package com.miningdim.title;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.store.MiningDb;
import com.miningdim.store.StoreTx;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.testutil.TempStoreDb;
import com.miningdim.title.CustomTitleViolation.Rule;
import com.miningdim.title.store.SqliteTitleRepository;
import com.miningdim.title.store.TitleStoreException;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
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
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 赞助专属称号 GameTest (Title_System_DesignSpec 13.9)。
 *
 * 校验器是纯函数, 用例直接构造阈值 ({@link #specRules()} 逐字抄设计文档 13.3 的默认值, 并核对 TitleConfig 的默认值
 * 与之一致), 不改写磁盘上的 toml。服务类用例建在 {@link TempStoreDb} 的临时统一库上, 注入可拨动的时钟验证冷却与
 * 到期; 需要经过真实登录钩子、显示名事件或命令分发器的用例把临时实现注入 {@link TitleServices}, 并在 finally 里
 * 恢复原门面。强断言 (删被测核心逻辑必挂):
 * <ol>
 *   <li>长度与内容字符上限按码点计 (含外框额度), 阈值由规则对象驱动;</li>
 *   <li>字符集: 白名单符号逐个放行; §、控制 / 零宽、私用区、emoji、白名单外字符、首尾与连续空格逐条拒绝并报第一个
 *       位置; § 写进白名单也不放行;</li>
 *   <li>违禁词: 空格、符号、全角、大小写, 以及夹进长音符、单笔画汉字、重复记号这些绕过写法全部识破, 含这些字的
 *       正常称号不误伤; 管理员代设置跳过违禁词但其余规则照常;</li>
 *   <li>颜色: 写法、色标数、WCAG 亮度下限 (0.18 两侧的 #767676 / #757575) 逐条报告; 渐变实际渲染出的过渡色同样
 *       要过线;</li>
 *   <li>命令参数拆分: 颜色、粗体、贪婪的文字原样保留;</li>
 *   <li>赞助资格: 永久、带天数、续期从较晚者顺延、过期后从现在起算、永久不被降级、撤销, 外层事务开着时写操作拒绝;</li>
 *   <li>冷却: 非赞助玩家被拒; 第一次免冷却; 预览与校验失败不消耗冷却; 冷却内被拒、满期可改; 清除冷却后立即可改;</li>
 *   <li>佩戴专属称号后聊天、Tab、名牌原样显示不加方括号, 渐变首尾色标与粗体正确, 修改立即生效, 排在持有集合最前;</li>
 *   <li>在线定时到期检查: 卸下、提示一次、记录保留; 续期恢复持有但不自动佩戴; 撤销等同到期;</li>
 *   <li>离线期间到期的玩家登录时卸下, 有效的玩家登录后原样显示;</li>
 *   <li>专属称号 id 不可发放 (含事务变体)、不可回收、数据包不得占用该前缀;</li>
 *   <li>管理员清空 / 锁定 / 解锁 / 清冷却 / 代设置的语义 (代设置既不开始也不清除玩家的冷却);</li>
 *   <li>经服务端真实命令分发器驱动玩家与管理员命令, 权限门按解析结果逐条核对全部管理员与玩家子命令;</li>
 *   <li>两种语言的称号键集合一致, 每条校验规则都有文案, 文案里不出现会被客户端吞掉的 §;</li>
 *   <li>生产构造器的阈值与冷却实时取自配置;</li>
 *   <li>佩戴与提交修改时自己先做到期检查, 不等巡检;</li>
 *   <li>登录时卸下写库失败留下的失效专属称号由巡检重试;</li>
 *   <li>玩家命令不渲染他人的专属称号、不暴露对方有没有记录, 也不向被锁定的玩家点名执行锁定的管理员;</li>
 *   <li>自助提交关闭 (默认口径): 玩家提交被拒且不落库、预览照常并附可复制参数, 资格发放、未设置、custom info
 *       的提示都指向"预览后找管理员"而不是 custom set; 管理员代设置后玩家可佩戴, 对没有资格的玩家代设置时提醒
 *       补发资格;</li>
 *   <li>默认违禁词从宽: 不收 OP、GM 这类短词, Shop 之类的正常写法不误伤。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CustomTitleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "title_custom";

    /** 用例时钟起点 (2026 年): 远离 0, 清除冷却 (冷却起点置 0) 才能按真实语义视为早已冷却完毕。 */
    private static final long T0 = 1_790_000_000_000L;
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long WEEK = Duration.ofDays(7).toMillis();

    /** 设计文档 13.3 的默认符号白名单与违禁词, 逐字抄录。 */
    private static final String SPEC_SYMBOLS = "[]【】〔〕「」『』《》〈〉()（）<>★☆◆◇♦♥♠♣✦✧·・~-_!?！？";
    private static final List<String> SPEC_BANNED = List.of("管理", "服主", "官方", "客服", "admin", "owner");

    private static final ResourceLocation GOLD = new ResourceLocation(MiningConstants.MODID, "test/custom_gold");

    /** 旧式格式码前缀 §。 */
    private static final char SECTION_SIGN = '§';

    private CustomTitleGameTests() {
    }

    // ---- 1. 配置默认值与设计文档一致 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void configDefaultsMatchTheSpec(GameTestHelper helper) {
        helper.assertTrue(TitleConfig.CUSTOM_MAX_LENGTH.getDefault() == 10, "总长上限默认应为 10");
        helper.assertTrue(TitleConfig.CUSTOM_MAX_CONTENT_CHARS.getDefault() == 8, "内容字符上限默认应为 8");
        helper.assertTrue(SPEC_SYMBOLS.equals(TitleConfig.CUSTOM_ALLOWED_SYMBOLS.getDefault()),
                "符号白名单默认值必须与 13.3 一致, 实为 " + TitleConfig.CUSTOM_ALLOWED_SYMBOLS.getDefault());
        helper.assertTrue(SPEC_BANNED.equals(List.copyOf(TitleConfig.CUSTOM_BANNED_WORDS.getDefault())),
                "违禁词默认值必须与 13.3 一致, 实为 " + TitleConfig.CUSTOM_BANNED_WORDS.getDefault());
        helper.assertTrue(TitleConfig.CUSTOM_MIN_LUMINANCE.getDefault() == 0.18D, "亮度下限默认应为 0.18");
        helper.assertTrue(TitleConfig.CUSTOM_EDIT_COOLDOWN_DAYS.getDefault() == 7, "修改冷却默认应为 7 天");
        helper.assertTrue(!TitleConfig.CUSTOM_SELF_SERVICE_ENABLED.getDefault(),
                "玩家自助提交默认应关闭 (由管理员代设置, 13.1)");
        helper.assertTrue(CustomTitleRules.symbols("[ ] ★").equals(Set.of((int) '[', (int) ']', (int) '★')),
                "白名单拆分必须按码点且忽略空白");
        helper.succeed();
    }

    // ---- 2. 长度与内容字符 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void validatorEnforcesLengthAndContentLimitsByCodePoint(GameTestHelper helper) {
        CustomTitleRules rules = specRules();
        expectValid(helper, rules, "【矿工】");
        expectValid(helper, rules, "【一二三四五六七八】");
        expectExactly(helper, rules, "【一二三四五六七八】!", Rule.TOO_LONG);
        helper.assertTrue(firstViolation(rules, "【一二三四五六七八】!").args().equals(List.of("11", "10")),
                "TOO_LONG 应报实际长度与上限, 实为 " + firstViolation(rules, "【一二三四五六七八】!").args());
        expectExactly(helper, rules, "一二三四五六七八九", Rule.CONTENT_TOO_MANY);
        expectExactly(helper, rules, "【★】", Rule.CONTENT_MISSING);
        expectExactly(helper, rules, "", Rule.EMPTY);

        // U+20000 是代理对: 8 个码点 (16 个 char) 合格, 9 个码点才超内容上限 —— 证明按码点而不是 char 计数。
        String supplementary = codePoints(0x20000);
        expectValid(helper, rules, supplementary.repeat(8));
        expectExactly(helper, rules, supplementary.repeat(9), Rule.CONTENT_TOO_MANY);

        expectValid(helper, rules, "ひらカタ");
        expectExactly(helper, rules, "ー".repeat(9), Rule.CONTENT_TOO_MANY);
        expectValid(helper, rules, "Miner 42");
        expectValid(helper, rules, "ＭＩＮＥＲ１");

        CustomTitleRules tight = new CustomTitleRules(4, 2, CustomTitleRules.symbols(SPEC_SYMBOLS), SPEC_BANNED,
                0.18D, WEEK, true);
        expectValid(helper, tight, "【矿工】");
        expectExactly(helper, tight, "【矿工人】", Rule.TOO_LONG, Rule.CONTENT_TOO_MANY);
        helper.succeed();
    }

    // ---- 3. 字符集与禁止字符 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void validatorCharsetRejectsForbiddenCharactersAtTheirPosition(GameTestHelper helper) {
        CustomTitleRules rules = specRules();
        SPEC_SYMBOLS.codePoints().forEach(symbol ->
                expectValid(helper, rules, "字" + codePoints(symbol)));

        expectExactly(helper, rules, "§a字", Rule.FORMAT_CODE);
        helper.assertTrue(firstViolation(rules, "§a字").args().equals(List.of("1")), "§ 应报第 1 个字符");
        expectExactly(helper, rules, "字\n字", Rule.INVISIBLE_CHAR);
        helper.assertTrue(firstViolation(rules, "字\n字").args().equals(List.of("2", "000A")),
                "换行应报位置 2 与码点 000A, 实为 " + firstViolation(rules, "字\n字").args());
        // 零宽空格 / 非连接符 / 连接符、词连接符、BOM、从右到左覆盖、变体选择符 16。
        for (int invisible : new int[] {0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x202E, 0xFE0F}) {
            expectExactly(helper, rules, "字" + codePoints(invisible) + "字", Rule.INVISIBLE_CHAR);
        }
        String privateUse = "字" + codePoints(0xE000) + "字";
        expectExactly(helper, rules, privateUse, Rule.PRIVATE_USE);
        helper.assertTrue(firstViolation(rules, privateUse).args().equals(List.of("2", "E000")), "私用区应报码点");
        expectExactly(helper, rules, "字" + codePoints(0x1F600), Rule.EMOJI);
        expectExactly(helper, rules, "字" + codePoints(0x2764), Rule.EMOJI);
        expectExactly(helper, rules, "字" + codePoints(0x1F1E8), Rule.EMOJI);
        expectValid(helper, rules, "★字★");

        expectExactly(helper, rules, "字#字", Rule.CHAR_NOT_ALLOWED);
        helper.assertTrue(firstViolation(rules, "字#字").args().equals(List.of("2", "#")),
                "白名单外字符应报位置与字符本身, 实为 " + firstViolation(rules, "字#字").args());
        expectExactly(helper, rules, "café", Rule.CHAR_NOT_ALLOWED);
        // 韩文填充符 (常被拿来冒充空白)、全角空格、不换行空格都不是允许的字符。
        for (int lookalikeBlank : new int[] {0x3164, 0x3000, 0x00A0}) {
            expectExactly(helper, rules, "字" + codePoints(lookalikeBlank) + "字", Rule.CHAR_NOT_ALLOWED);
        }
        expectExactly(helper, rules, "Привет", Rule.CHAR_NOT_ALLOWED, Rule.CONTENT_MISSING);

        expectValid(helper, rules, "矿 工");
        expectExactly(helper, rules, " 矿工", Rule.SPACE_EDGE);
        expectExactly(helper, rules, "矿工 ", Rule.SPACE_EDGE);
        expectExactly(helper, rules, "矿  工", Rule.SPACE_DOUBLE);
        helper.assertTrue(firstViolation(rules, "矿  工").args().equals(List.of("3")), "连续空格应报第二个空格的位置");

        List<CustomTitleViolation> repeated = violations(rules, "§§字", "#FFFFFF");
        helper.assertTrue(repeated.size() == 1 && repeated.get(0).args().equals(List.of("1")),
                "同一规则只报一次且报第一个位置, 实为 " + repeated);
        String zeroWidth = codePoints(0x200B);
        expectExactly(helper, rules, "§字" + zeroWidth + codePoints(0x1F600) + "#",
                Rule.FORMAT_CODE, Rule.INVISIBLE_CHAR, Rule.EMOJI, Rule.CHAR_NOT_ALLOWED);

        CustomTitleRules widened = new CustomTitleRules(10, 8,
                CustomTitleRules.symbols(SPEC_SYMBOLS + "#§" + zeroWidth), SPEC_BANNED, 0.18D, WEEK, true);
        expectValid(helper, widened, "字#字");
        expectExactly(helper, widened, "字§", Rule.FORMAT_CODE);
        expectExactly(helper, widened, "字" + zeroWidth, Rule.INVISIBLE_CHAR);
        helper.succeed();
    }

    // ---- 4. 违禁词归一化 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void validatorBannedWordsSeeThroughNormalization(GameTestHelper helper) {
        CustomTitleRules rules = specRules();
        for (String bypass : List.of("管理", "管 理", "管·理", "【管_理】", "ＡＤＭＩＮ大佬", "Admin大佬", "Ａｄｍｉｎ",
                "ad-min", "服★主", "官方认证", "客服", "Owner")) {
            expectExactly(helper, rules, bypass, Rule.BANNED_WORD);
        }
        helper.assertTrue(firstViolation(rules, "ＡＤＭＩＮ大佬").args().equals(List.of("admin")),
                "违禁词提示应给出配置里的原词, 实为 " + firstViolation(rules, "ＡＤＭＩＮ大佬").args());
        // 默认词表从宽 (服主有人工审查): 不收 OP、GM 这类两字母短词, 含这些字母的正常写法不再被误伤。
        for (String loosened : List.of("OP大佬", "GM", "Shop", "Hope")) {
            expectValid(helper, rules, loosened);
        }
        // 本身算内容字符、看上去却只是一笔横竖撇点或一个重复记号的字, 夹进违禁词当分隔符用 —— 与"管 理"同一类绕过。
        for (String strokeSeparated : List.of("管ー理", "服一主", "官丨方", "客丶服", "管丿理", "服乀主", "管ノ理",
                "管々理", "客ゝ服", "官ヽ方", "adーmin", "own一er", "ad一min")) {
            expectExactly(helper, rules, strokeSeparated, Rule.BANNED_WORD);
        }
        // 这些字本身照常可用, 只是匹配时不算隔开; 词表里含这些字的词仍按原样匹配, 不会被放宽成更短的词。
        expectValid(helper, rules, "第一矿工");
        expectValid(helper, rules, "ラーメン");
        CustomTitleRules strokeWord = new CustomTitleRules(10, 8, CustomTitleRules.symbols(SPEC_SYMBOLS),
                List.of("一哥"), 0.18D, WEEK, true);
        expectExactly(helper, strokeWord, "一 哥", Rule.BANNED_WORD);
        expectValid(helper, strokeWord, "哥们");
        expectValid(helper, rules, "矿工");
        expectValid(helper, rules, "Miner");

        helper.assertTrue("admin".equals(CustomTitleValidator.normalizeForBannedWords("Ａ d-m·i n！")),
                "归一化应做全角转半角、转小写并去掉空格与符号, 实为 "
                        + CustomTitleValidator.normalizeForBannedWords("Ａ d-m·i n！"));
        helper.assertTrue("管理".equals(CustomTitleValidator.normalizeForBannedWords("【管 理★】")),
                "归一化应去掉括号、空格与装饰符号");

        CustomTitleValidator.Validation adminBypass = CustomTitleValidator.validate(
                draft("管理组", false, "#FFFFFF"), rules, false);
        helper.assertTrue(adminBypass.valid(), "管理员代设置应跳过违禁词, 实为 " + adminBypass.violations());
        CustomTitleValidator.Validation adminCharset = CustomTitleValidator.validate(
                draft("管理§", false, "#FFFFFF"), rules, false);
        helper.assertTrue(ruleSet(adminCharset.violations()).equals(EnumSet.of(Rule.FORMAT_CODE)),
                "管理员代设置仍须校验字符集, 实为 " + adminCharset.violations());

        CustomTitleRules ownList = new CustomTitleRules(10, 8, CustomTitleRules.symbols(SPEC_SYMBOLS), List.of("矿工"),
                0.18D, WEEK, true);
        expectExactly(helper, ownList, "矿 工", Rule.BANNED_WORD);
        expectValid(helper, ownList, "管理");
        CustomTitleRules noList = new CustomTitleRules(10, 8, CustomTitleRules.symbols(SPEC_SYMBOLS), List.of(),
                0.18D, WEEK, true);
        expectValid(helper, noList, "管理");
        helper.succeed();
    }

    // ---- 5. 颜色与亮度 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void validatorChecksColorFormatCountAndLuminance(GameTestHelper helper) {
        CustomTitleRules rules = specRules();
        CustomTitleValidator.Validation gradient = CustomTitleValidator.validate(
                draft("【矿工】", true, "#ff3d3d", "#FFD23F"), rules, true);
        helper.assertTrue(gradient.valid() && gradient.style().colors().equals(List.of(0xFF3D3D, 0xFFD23F))
                        && gradient.style().bold() && "【矿工】".equals(gradient.style().text())
                        && "#FF3D3D,#FFD23F".equals(gradient.style().colorsText()),
                "两色渐变应通过, 颜色解析为大写存储写法, 文字原样, 实为 " + gradient);
        helper.assertTrue(CustomTitleValidator.validate(draft("字", false, "#FFFFFF", "#FFD23F", "#FF3D3D"),
                rules, true).valid(), "三色渐变应通过");

        expectColors(helper, rules, List.of("#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF"), Rule.COLOR_COUNT);
        for (String malformed : List.of("#12345", "red", "#GGGGGG", "", "FFFFFF", "#FFF")) {
            expectColors(helper, rules, List.of(malformed), Rule.COLOR_FORMAT);
        }

        expectColors(helper, rules, List.of("#767676"));
        expectColors(helper, rules, List.of("#757575"), Rule.COLOR_TOO_DARK);
        expectColors(helper, rules, List.of("#1A1A1A"), Rule.COLOR_TOO_DARK);
        expectColors(helper, rules, List.of("#0000FF"), Rule.COLOR_TOO_DARK);
        expectColors(helper, rules, List.of("#FF0000"));
        List<CustomTitleViolation> dark = CustomTitleValidator.validate(
                draft("字", false, "#FFFFFF", "#101010", "#202020"), rules, true).violations();
        helper.assertTrue(dark.size() == 2 && "#101010".equals(dark.get(0).args().get(0))
                        && "#202020".equals(dark.get(1).args().get(0)),
                "每个过暗的色标都要单独报告, 实为 " + dark);

        // 色标各自够亮, 渐变逐字渲染出的过渡色却可能很暗: 红 #FF0000 (0.213) 与绿 #008A00 (0.182) 都过线,
        // 五个字时第 2 个字是 #BF2300 (约 0.123)、正中是 #804500 (约 0.088)。
        List<CustomTitleViolation> muddy = CustomTitleValidator.validate(
                draft("ABCDE", false, "#FF0000", "#008A00"), rules, true).violations();
        helper.assertTrue(muddy.size() == 1 && muddy.get(0).rule() == Rule.GRADIENT_TOO_DARK
                        && muddy.get(0).args().equals(List.of("2", "#BF2300", "0.123", "0.180")),
                "渐变实际渲染出的过渡色也要过亮度下限, 报第一个过暗的字与它的颜色, 实为 " + muddy);
        helper.assertTrue(CustomTitleValidator.validate(draft("AB", false, "#FF0000", "#008A00"), rules, true).valid(),
                "只有两个字时渲染出来的就是两个色标本身, 应通过");
        helper.assertTrue(CustomTitleValidator.validate(draft("A B", false, "#FF0000", "#008A00"), rules, true).valid(),
                "空格看不见, 落在它身上的过渡色不计");
        helper.assertTrue(CustomTitleValidator.validate(draft("【一二三四五六七八】", true, "#FF3D3D", "#FF8A1F",
                "#FFD23F"), rules, true).valid(), "传说档三色渐变铺满 10 个字也应通过");

        double white = CustomTitleValidator.relativeLuminance(0xFFFFFF);
        double gray = CustomTitleValidator.relativeLuminance(0x767676);
        helper.assertTrue(Math.abs(white - 1.0D) < 1.0E-9 && CustomTitleValidator.relativeLuminance(0x000000) == 0.0D,
                "白色亮度应为 1, 黑色为 0, 实为 " + white);
        helper.assertTrue(gray > 0.180D && gray < 0.182D, "#767676 的 WCAG 相对亮度约 0.181, 实为 " + gray);

        CustomTitleRules anyColor = new CustomTitleRules(10, 8, CustomTitleRules.symbols(SPEC_SYMBOLS), SPEC_BANNED,
                0.0D, WEEK, true);
        expectColors(helper, anyColor, List.of("#000000"));
        CustomTitleRules bright = new CustomTitleRules(10, 8, CustomTitleRules.symbols(SPEC_SYMBOLS), SPEC_BANNED,
                0.5D, WEEK, true);
        expectColors(helper, bright, List.of("#767676"), Rule.COLOR_TOO_DARK);

        CustomTitleValidator.Validation both = CustomTitleValidator.validate(draft("", false, "#000000"), rules, true);
        helper.assertTrue(ruleSet(both.violations()).equals(EnumSet.of(Rule.EMPTY, Rule.COLOR_TOO_DARK)),
                "多条规则同时不合格时应全部报告, 实为 " + both.violations());
        helper.succeed();
    }

    // ---- 6. 命令参数拆分 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void draftParsingSplitsColorsBoldAndGreedyText(GameTestHelper helper) {
        CustomTitleDraft gradient = CustomTitleDraft.parse("#FF3D3D,#FFD23F true ★矿 工★").orElse(null);
        helper.assertTrue(gradient != null && gradient.colors().equals(List.of("#FF3D3D", "#FFD23F"))
                        && gradient.bold() && "★矿 工★".equals(gradient.text()),
                "颜色按逗号拆分、粗体解析、文字吃掉剩余输入并保留空格, 实为 " + gradient);
        CustomTitleDraft upper = CustomTitleDraft.parse("#FFFFFF FALSE x").orElse(null);
        helper.assertTrue(upper != null && !upper.bold(), "粗体不分大小写");
        CustomTitleDraft verbatim = CustomTitleDraft.parse("#FFFFFF true  x").orElse(null);
        helper.assertTrue(verbatim != null && " x".equals(verbatim.text()), "文字必须原样保留, 首部空格交给校验器报告");
        CustomTitleDraft emptyText = CustomTitleDraft.parse("#FFFFFF true ").orElse(null);
        helper.assertTrue(emptyText != null && emptyText.text().isEmpty(), "粗体后只有一个空格时文字为空串");
        CustomTitleDraft trailingComma = CustomTitleDraft.parse("#FFFFFF, true x").orElse(null);
        helper.assertTrue(trailingComma != null && trailingComma.colors().equals(List.of("#FFFFFF", "")),
                "空色标保留为空串, 由校验器报告格式错误");
        for (String malformed : List.of("", "#FFFFFF", "#FFFFFF true", "#FFFFFF maybe x", " true x")) {
            helper.assertTrue(CustomTitleDraft.parse(malformed).isEmpty(), "缺段或粗体非法应解析失败: '" + malformed + "'");
        }
        helper.succeed();
    }

    // ---- 7. 赞助资格 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sponsorshipIsPermanentOrTimedAndRenewalExtendsFromTheLaterEnd(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            AtomicLong clock = new AtomicLong(T0);
            TitleService service = newService(helper, connection, clock);
            UUID permanent = UUID.randomUUID();
            UUID timed = UUID.randomUUID();

            SponsorStatus forever = service.grantSponsor(permanent, null, "op");
            helper.assertTrue(forever.permanent() && forever.activeAt(Long.MAX_VALUE), "不带天数应为永久资格");
            helper.assertTrue(column(connection, "title_sponsor", "expires_at", permanent)
                    == null, "永久资格落库时 expires_at 必须为 NULL");

            SponsorStatus first = service.grantSponsor(timed, 30, "op");
            helper.assertTrue(Long.valueOf(T0 + 30 * DAY).equals(first.expiresAt()),
                    "30 天资格应在 T0+30d 到期, 实为 " + first.expiresAt());
            clock.set(T0 + 10 * DAY);
            SponsorStatus renewed = service.grantSponsor(timed, 30, "op2");
            helper.assertTrue(Long.valueOf(T0 + 60 * DAY).equals(renewed.expiresAt()),
                    "未到期续期应从原到期时间顺延 (T0+60d), 不吞剩余天数, 实为 " + renewed.expiresAt());
            helper.assertTrue("op2".equals(column(connection, "title_sponsor", "granted_by",
                    timed)), "续期应记下最近一次的执行者");

            clock.set(T0 + 61 * DAY);
            helper.assertTrue(!service.customTitleInfo(timed).sponsorActive(), "到期后资格应失效");
            SponsorStatus afterLapse = service.grantSponsor(timed, 5, "op");
            helper.assertTrue(Long.valueOf(T0 + 66 * DAY).equals(afterLapse.expiresAt()),
                    "已过期再续期应从现在起算, 实为 " + afterLapse.expiresAt());

            SponsorStatus stillForever = service.grantSponsor(permanent, 30, "op");
            helper.assertTrue(stillForever.permanent(), "永久资格不得被带天数的续期降级成限期");
            helper.assertTrue(service.customTitleInfo(permanent).sponsorActive(), "永久资格一直有效");

            helper.assertTrue(service.sponsors().size() == 2, "应列出两条资格, 实为 " + service.sponsors());
            helper.assertTrue(service.revokeSponsor(timed, "op"), "撤销已有资格应返回 true");
            helper.assertTrue(column(connection, "title_sponsor", "granted_by", timed) == null,
                    "撤销必须删掉资格行");
            helper.assertTrue(!service.revokeSponsor(timed, "op"), "再次撤销应返回 false");
            helper.assertTrue(service.sponsors().size() == 1, "撤销后只剩永久资格");

            boolean rejectedZeroDays = false;
            try {
                service.grantSponsor(timed, 0, "op");
            } catch (IllegalArgumentException expected) {
                rejectedZeroDays = true;
            }
            helper.assertTrue(rejectedZeroDays, "天数小于 1 必须拒绝");

            List<String> joinedOuterTx = new ArrayList<>();
            StoreTx.run(connection, () -> {
                expectRejectedInTransaction(joinedOuterTx, "grantSponsor", () -> service.grantSponsor(timed, 1, "op"));
                expectRejectedInTransaction(joinedOuterTx, "revokeSponsor",
                        () -> service.revokeSponsor(permanent, "op"));
                expectRejectedInTransaction(joinedOuterTx, "adminSetCustomTitle",
                        () -> service.adminSetCustomTitle(permanent, draft("矿工", false, "#FFFFFF"), "op"));
                expectRejectedInTransaction(joinedOuterTx, "resetCustomTitle",
                        () -> service.resetCustomTitle(permanent, "op"));
                expectRejectedInTransaction(joinedOuterTx, "setCustomTitleLocked",
                        () -> service.setCustomTitleLocked(permanent, true, "op"));
                expectRejectedInTransaction(joinedOuterTx, "clearCustomTitleCooldown",
                        () -> service.clearCustomTitleCooldown(permanent, "op"));
            });
            helper.assertTrue(joinedOuterTx.isEmpty(), "外层事务开着时这些写操作必须抛 IllegalStateException, 实际放行了 "
                    + joinedOuterTx);
            helper.assertTrue(countRows(connection, "title_custom") == 0, "被拒的事务内写操作不得落库");
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 8. 资格要求与修改冷却 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void customSetRequiresSponsorshipAndHonoursTheCooldown(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            AtomicLong clock = new AtomicLong(T0);
            TitleService service = newService(helper, connection, clock);
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();
            CustomTitleDraft first = draft("【矿工】", false, "#FFD23F");
            CustomTitleDraft second = draft("★矿工★", true, "#FF3D3D", "#FFD23F");
            CustomTitleDraft third = draft("第三版", false, "#FFFFFF");

            helper.assertTrue(service.setCustomTitle(player, first).status() == CustomTitleResult.Status.NOT_SPONSOR,
                    "非赞助玩家提交必须被拒");
            helper.assertTrue(service.previewCustomTitle(uuid, first).status()
                    == CustomTitleResult.Status.NOT_SPONSOR, "非赞助玩家预览同样被拒");
            helper.assertTrue(countRows(connection, "title_custom") == 0, "被拒的提交不得落库");

            service.grantSponsor(uuid, 30, "op");
            CustomTitleResult preview = service.previewCustomTitle(uuid, first);
            helper.assertTrue(preview.status() == CustomTitleResult.Status.VALID && preview.nextEditAt() == 0L,
                    "赞助玩家首次预览应通过且现在即可修改, 实为 " + preview);
            helper.assertTrue(countRows(connection, "title_custom") == 0, "预览不得写库");

            CustomTitleResult applied = service.setCustomTitle(player, first);
            helper.assertTrue(applied.status() == CustomTitleResult.Status.APPLIED && applied.nextEditAt() == T0 + WEEK,
                    "第一次设置不受冷却限制, 下次可改为 T0+7d, 实为 " + applied);
            helper.assertTrue("【矿工】".equals(customText(connection, uuid))
                            && "#FFD23F".equals(column(connection, "title_custom", "colors", uuid))
                            && "0".equals(column(connection, "title_custom", "bold", uuid))
                            && String.valueOf(T0).equals(updatedAt(connection, uuid)),
                    "设置必须落库文字、颜色、粗体与冷却起点");
            ResourceLocation customId = CustomTitle.idOf(uuid);
            helper.assertTrue(service.owned(uuid).contains(customId), "有记录且资格有效即持有专属称号");
            helper.assertTrue(countRows(connection, "title_owned") == 0, "专属称号的持有是推导出来的, 不写 title_owned");

            CustomTitleResult previewDuringCooldown = service.previewCustomTitle(uuid, second);
            helper.assertTrue(previewDuringCooldown.status() == CustomTitleResult.Status.VALID
                            && previewDuringCooldown.nextEditAt() == T0 + WEEK,
                    "冷却中仍可预览, 并告知下次可改时间, 实为 " + previewDuringCooldown);
            helper.assertTrue("【矿工】".equals(customText(connection, uuid))
                    && String.valueOf(T0).equals(updatedAt(connection, uuid)), "预览不得改动记录或冷却起点");

            clock.set(T0 + WEEK - 1);
            CustomTitleResult early = service.setCustomTitle(player, second);
            helper.assertTrue(early.status() == CustomTitleResult.Status.ON_COOLDOWN && early.nextEditAt() == T0 + WEEK,
                    "冷却差 1 毫秒也必须被拒, 实为 " + early);
            helper.assertTrue("【矿工】".equals(customText(connection, uuid)), "冷却内被拒不得改库");

            clock.set(T0 + WEEK);
            CustomTitleResult banned = service.setCustomTitle(player, draft("管理组", false, "#FFFFFF"));
            helper.assertTrue(banned.status() == CustomTitleResult.Status.INVALID
                            && ruleSet(banned.violations()).equals(EnumSet.of(Rule.BANNED_WORD)),
                    "玩家提交违禁词必须被拒, 实为 " + banned);
            helper.assertTrue(String.valueOf(T0).equals(updatedAt(connection, uuid)), "校验失败不得消耗冷却");
            CustomTitleResult onTime = service.setCustomTitle(player, second);
            helper.assertTrue(onTime.status() == CustomTitleResult.Status.APPLIED, "满 7 天后必须能修改, 实为 " + onTime);
            helper.assertTrue("★矿工★".equals(customText(connection, uuid))
                    && String.valueOf(T0 + WEEK).equals(updatedAt(connection, uuid)), "修改后冷却从这次重新起算");

            helper.assertTrue(service.setCustomTitle(player, third).status() == CustomTitleResult.Status.ON_COOLDOWN,
                    "刚改完又改必须被拒");
            helper.assertTrue(service.clearCustomTitleCooldown(uuid, "op") == CustomAdminResult.DONE, "清除冷却应执行");
            helper.assertTrue("0".equals(updatedAt(connection, uuid)), "清除冷却把冷却起点置 0");
            helper.assertTrue(service.clearCustomTitleCooldown(uuid, "op") == CustomAdminResult.UNCHANGED,
                    "不在冷却中时清除应返回 UNCHANGED");
            helper.assertTrue(service.setCustomTitle(player, third).status() == CustomTitleResult.Status.APPLIED,
                    "清除冷却后必须能立即修改");
            helper.assertTrue("第三版".equals(customText(connection, uuid)), "清除冷却后的修改必须落库");
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 9. 原样显示 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void equippedCustomTitleDisplaysVerbatimWithGradientAndBold(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ITitleService previous = currentFacade();
        ServerPlayer player = null;
        try {
            AtomicLong clock = new AtomicLong(T0);
            TitleService service = newService(helper, connection, clock);
            TitleServices.registerTitleService(service);
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), "title-verbatim"));
            UUID uuid = player.getUUID();
            String name = player.getGameProfile().getName();
            ResourceLocation customId = CustomTitle.idOf(uuid);

            service.grantSponsor(uuid, null, "op");
            service.setCustomTitle(player, draft("★矿工★", true, "#FF3D3D", "#FFD23F"));
            helper.assertTrue(service.equip(player, customId) == EquipResult.EQUIPPED, "赞助玩家应能佩戴自己的专属称号");

            String display = player.getDisplayName().getString();
            helper.assertTrue(("★矿工★ " + name).equals(display),
                    "聊天显示名应为原样文字 + 空格 + 名字, 不加方括号, 实为 " + display);
            Component tab = player.getTabListDisplayName();
            helper.assertTrue(tab != null && ("★矿工★ " + name).equals(tab.getString()),
                    "Tab 名应原样显示, 实为 " + (tab == null ? null : tab.getString()));
            Component badge = service.displayBadge(uuid);
            helper.assertTrue(badge != null && "★矿工★".equals(badge.getString()), "名牌徽记应为原样文字, 不加方括号");
            List<Component> pieces = badge.getSiblings();
            helper.assertTrue(pieces.size() == 4, "渐变应按码点逐字拆成 4 段, 实为 " + pieces.size());
            helper.assertTrue(colorOf(pieces.get(0)) == 0xFF3D3D && colorOf(pieces.get(3)) == 0xFFD23F,
                    "首尾字应恰为首尾色标, 实为 " + hex(colorOf(pieces.get(0))) + " / " + hex(colorOf(pieces.get(3))));
            helper.assertTrue(pieces.stream().allMatch(piece -> piece.getStyle().isBold()), "粗体必须作用于每个字");
            Component prefix = service.displayPrefix(uuid);
            helper.assertTrue(prefix != null && "★矿工★ ".equals(prefix.getString()), "前缀应为原样文字加一个空格");

            TitleDefinition definition = service.definition(customId).orElse(null);
            helper.assertTrue(definition != null && definition.isCustom() && definition.rarity() == null
                            && definition.sort() == TitleDefinition.CUSTOM_SORT,
                    "专属称号的定义按记录动态生成, 没有稀有度, 排在最前");
            helper.assertTrue(service.badge(customId).map(Component::getString).orElse("").equals("★矿工★"),
                    "badge 查询应走同一渲染");
            helper.assertTrue(service.definition(CustomTitle.idOf(UUID.randomUUID())).isEmpty(),
                    "没有记录的专属称号 id 没有定义");

            service.grant(player, GOLD, TitleSource.ADMIN, null);
            helper.assertTrue(customId.equals(service.owned(uuid).iterator().next()),
                    "专属称号在持有集合里排在最前, 实为 " + service.owned(uuid));

            service.clearCustomTitleCooldown(uuid, "op");
            service.setCustomTitle(player, draft("【Miner】", false, "#FFFFFF"));
            helper.assertTrue(("【Miner】 " + name).equals(player.getDisplayName().getString()),
                    "正在佩戴时修改必须立即刷新显示名, 实为 " + player.getDisplayName().getString());
            Component solid = service.displayBadge(uuid);
            helper.assertTrue(solid != null && solid.getSiblings().isEmpty() && "【Miner】".equals(solid.getString())
                            && colorOf(solid) == 0xFFFFFF && !solid.getStyle().isBold(),
                    "单色专属称号整段一个样式, 不加粗, 实为 " + solid);
        } finally {
            if (player != null) {
                server.getPlayerList().remove(player);
            }
            restoreFacade(previous);
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 10. 在线到期检查、续期与撤销 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void periodicExpiryCheckUnequipsKeepsRecordAndRenewalDoesNotReequip(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ITitleService previous = currentFacade();
        ServerPlayer player = null;
        try {
            AtomicLong clock = new AtomicLong(T0);
            TitleService service = newService(helper, connection, clock);
            TitleServices.registerTitleService(service);
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), "title-expiry"));
            UUID uuid = player.getUUID();
            String name = player.getGameProfile().getName();
            ResourceLocation customId = CustomTitle.idOf(uuid);
            EmbeddedChannel channel = channelOf(player);

            service.grantSponsor(uuid, 1, "op");
            service.setCustomTitle(player, draft("【矿工】", false, "#FFD23F"));
            service.equip(player, customId);
            helper.assertTrue(("【矿工】 " + name).equals(player.getDisplayName().getString()), "前置: 已佩戴专属称号");
            systemChatKeys(channel);

            clock.set(T0 + DAY);
            helper.assertTrue(!service.owned(uuid).contains(customId), "到期那一刻起专属称号就不在持有集合里");
            service.checkSponsorExpiry(List.of(player));
            helper.assertTrue(service.equipped(uuid).isEmpty() && equippedRow(connection, uuid) == null,
                    "定时检查发现到期必须卸下专属称号 (缓存与库)");
            helper.assertTrue(name.equals(player.getDisplayName().getString()), "卸下后显示名必须恢复");
            helper.assertTrue("【矿工】".equals(customText(connection, uuid)), "到期不得删除专属称号记录");
            List<String> lapsed = systemChatKeys(channel);
            helper.assertTrue(lapsed.equals(List.of("title.miningdim.sponsor.lapsed_unequipped")),
                    "到期卸下时必须提示本人一次, 实为 " + lapsed);
            service.checkSponsorExpiry(List.of(player));
            helper.assertTrue(systemChatKeys(channel).isEmpty(), "同一次到期只提示一次");

            helper.assertTrue(service.equip(player, customId) == EquipResult.NOT_OWNED, "到期后不能再佩戴");
            helper.assertTrue(service.setCustomTitle(player, draft("新称号", false, "#FFFFFF")).status()
                    == CustomTitleResult.Status.NOT_SPONSOR, "到期后不能修改");

            service.grantSponsor(uuid, 30, "op");
            helper.assertTrue(service.owned(uuid).contains(customId), "续期后专属称号立即重新出现在持有集合里");
            helper.assertTrue(service.equipped(uuid).isEmpty(), "续期不得自动重新佩戴");
            helper.assertTrue(systemChatKeys(channel).equals(List.of("title.miningdim.sponsor.granted_until_notice")),
                    "续期应提示新的到期时间");
            helper.assertTrue(service.equip(player, customId) == EquipResult.EQUIPPED, "续期后玩家可以自己再戴上");

            helper.assertTrue(service.revokeSponsor(uuid, "op"), "撤销应删掉资格");
            helper.assertTrue(service.equipped(uuid).isEmpty() && !service.owned(uuid).contains(customId),
                    "撤销等同到期: 卸下并移出持有集合");
            helper.assertTrue("【矿工】".equals(customText(connection, uuid)), "撤销同样保留专属称号记录");
            helper.assertTrue(systemChatKeys(channel).equals(List.of("title.miningdim.sponsor.lapsed_unequipped")),
                    "撤销时正在佩戴也要提示");

            service.grantSponsor(uuid, 2, "op");
            systemChatKeys(channel);
            clock.addAndGet(2 * DAY);
            service.checkSponsorExpiry(List.of(player));
            helper.assertTrue(systemChatKeys(channel).equals(List.of("title.miningdim.sponsor.lapsed")),
                    "在线期间到期而没佩戴时也提示一次");
        } finally {
            if (player != null) {
                server.getPlayerList().remove(player);
            }
            restoreFacade(previous);
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 11. 登录时的到期检查 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginUnequipsTitlesThatLapsedOfflineAndShowsActiveOnesVerbatim(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ITitleService previous = currentFacade();
        List<ServerPlayer> players = new ArrayList<>();
        try {
            GameProfile lapsedProfile = new GameProfile(UUID.randomUUID(), "title-lapsed");
            GameProfile activeProfile = new GameProfile(UUID.randomUUID(), "title-active");
            SqliteTitleRepository repository = new SqliteTitleRepository(connection);
            UUID lapsed = lapsedProfile.getId();
            UUID active = activeProfile.getId();
            repository.upsertSponsor(lapsed, "op", T0 - 30 * DAY, T0 - DAY);
            repository.saveCustomTitle(lapsed, new CustomTitleStyle("【旧】", List.of(0xFFFFFF), false), T0 - 30 * DAY);
            repository.setEquipped(lapsed, CustomTitle.idOf(lapsed));
            repository.upsertSponsor(active, "op", T0 - 30 * DAY, null);
            repository.saveCustomTitle(active, new CustomTitleStyle("◆Boss◆", List.of(0xFFD23F), true), T0 - 30 * DAY);
            repository.setEquipped(active, CustomTitle.idOf(active));

            TitleService service = new TitleService(repository, definitionsWithGold(), server, () -> T0,
                    CustomTitleGameTests::specRules);
            TitleServices.registerTitleService(service);

            // placeNewPlayer 派发真实的 PlayerLoggedInEvent, 由 TitleSystem 的登录钩子载入缓存并做到期检查。
            ServerPlayer lapsedPlayer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, lapsedProfile);
            players.add(lapsedPlayer);
            helper.assertTrue(service.equipped(lapsed).isEmpty() && equippedRow(connection, lapsed) == null,
                    "离线期间到期的专属称号必须在登录时卸下");
            helper.assertTrue("【旧】".equals(customText(connection, lapsed)), "登录卸下同样保留记录");
            helper.assertTrue(lapsedProfile.getName().equals(lapsedPlayer.getDisplayName().getString()),
                    "卸下后登录显示名不带称号");
            List<String> loginNotices = systemChatKeys(channelOf(lapsedPlayer));
            helper.assertTrue(loginNotices.contains("title.miningdim.sponsor.lapsed_unequipped"),
                    "登录时卸下必须提示本人, 实为 " + loginNotices);

            ServerPlayer activePlayer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, activeProfile);
            players.add(activePlayer);
            helper.assertTrue(("◆Boss◆ " + activeProfile.getName()).equals(activePlayer.getDisplayName().getString()),
                    "资格有效的玩家登录后应原样显示专属称号, 实为 " + activePlayer.getDisplayName().getString());
            Component tab = activePlayer.getTabListDisplayName();
            helper.assertTrue(tab != null && ("◆Boss◆ " + activeProfile.getName()).equals(tab.getString()),
                    "登录钩子必须补刷 Tab 名");
        } finally {
            for (ServerPlayer player : players) {
                server.getPlayerList().remove(player);
            }
            restoreFacade(previous);
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 12. 专属称号 id 不可发放、不可回收、数据包不得占用 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void customTitleIdsAreNeverGrantableNorRevocable(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            TitleService service = newService(helper, connection, new AtomicLong(T0));
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();
            ResourceLocation customId = CustomTitle.idOf(uuid);
            service.grantSponsor(uuid, null, "op");
            service.adminSetCustomTitle(uuid, draft("【矿工】", false, "#FFFFFF"), "op");

            UUID offline = UUID.randomUUID();
            ResourceLocation garbage = new ResourceLocation(MiningConstants.MODID, "custom/not-a-uuid");
            helper.assertTrue(service.grant(player, customId, TitleSource.ADMIN, null) == GrantResult.NOT_GRANTABLE,
                    "在线发放专属称号必须拒绝");
            helper.assertTrue(service.grant(offline, CustomTitle.idOf(offline), TitleSource.EVENT, null)
                    == GrantResult.NOT_GRANTABLE, "离线发放专属称号必须拒绝");
            helper.assertTrue(service.grant(offline, garbage, TitleSource.ADMIN, null) == GrantResult.NOT_GRANTABLE,
                    "custom/ 前缀整体保留, 后缀不是 UUID 也不能发放");
            GrantResult inTx = StoreTx.call(connection, () -> service.grantInTransaction(connection, uuid, customId,
                    TitleSource.POINT_SHOP, "shop:custom"));
            helper.assertTrue(inTx == GrantResult.NOT_GRANTABLE, "事务变体同样拒发, 实为 " + inTx);
            helper.assertTrue(countRows(connection, "title_owned") == 0, "拒发不得落库");
            boolean autoCommitStillFirst = false;
            try {
                service.grantInTransaction(connection, uuid, customId, TitleSource.POINT_SHOP, null);
            } catch (IllegalStateException expected) {
                autoCommitStillFirst = true;
            }
            helper.assertTrue(autoCommitStillFirst, "没开事务的接线错误仍先于专属称号检查抛出");

            helper.assertTrue(service.equip(player, customId) == EquipResult.EQUIPPED, "前置: 佩戴专属称号");
            helper.assertTrue(!service.revoke(uuid, customId), "revoke 不处理专属称号");
            helper.assertTrue(service.equipped(uuid).equals(Optional.of(customId))
                            && "【矿工】".equals(customText(connection, uuid)),
                    "revoke 专属称号 id 不得卸下或删除任何东西");

            helper.assertTrue(uuid.equals(CustomTitle.ownerOf(customId)), "ownerOf 应还原所有者");
            helper.assertTrue(CustomTitle.ownerOf(garbage) == null && CustomTitle.isCustomId(garbage),
                    "非 UUID 后缀仍属保留前缀, 但没有所有者");
            helper.assertTrue(CustomTitle.ownerOf(GOLD) == null && !CustomTitle.isCustomId(GOLD), "普通称号 id 不是专属称号");
            helper.assertTrue(!CustomTitle.isCustomId(new ResourceLocation("other", "custom/x")),
                    "只保留本模组命名空间下的 custom/ 前缀");

            Map<ResourceLocation, JsonElement> parsed = new LinkedHashMap<>();
            JsonElement valid = JsonParser.parseString("{\"text\":{\"text\":\"x\"},\"rarity\":\"gold\"}");
            parsed.put(new ResourceLocation(MiningConstants.MODID, "custom/" + uuid), valid);
            parsed.put(new ResourceLocation(MiningConstants.MODID, "test/plain"), valid);
            parsed.put(new ResourceLocation("other", "custom/x"), valid);
            Map<ResourceLocation, TitleDefinition> loaded = TitleDefinitionLoader.parseAll(parsed);
            helper.assertTrue(loaded.keySet().equals(Set.of(new ResourceLocation(MiningConstants.MODID, "test/plain"),
                            new ResourceLocation("other", "custom/x"))),
                    "数据包不得占用 miningdim:custom/ 前缀, 实为 " + loaded.keySet());
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 13. 管理员处置 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminResetLockUnlockAndSetFollowTheSpec(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        try {
            AtomicLong clock = new AtomicLong(T0);
            TitleService service = newService(helper, connection, clock);
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();
            ResourceLocation customId = CustomTitle.idOf(uuid);
            EmbeddedChannel channel = channelOf(player);

            helper.assertTrue(service.setCustomTitleLocked(uuid, true, "op") == CustomAdminResult.NO_CUSTOM_TITLE
                            && service.clearCustomTitleCooldown(uuid, "op") == CustomAdminResult.NO_CUSTOM_TITLE
                            && service.resetCustomTitle(uuid, "op") == CustomAdminResult.NO_CUSTOM_TITLE,
                    "没有记录时各项处置都应返回 NO_CUSTOM_TITLE");

            CustomTitleResult adminSet = service.adminSetCustomTitle(uuid, draft("管理组", false, "#FFFFFF"), "op");
            helper.assertTrue(adminSet.status() == CustomTitleResult.Status.APPLIED
                            && "管理组".equals(customText(connection, uuid)),
                    "代设置不看资格、放行违禁词, 实为 " + adminSet);
            CustomTitleResult adminCharset = service.adminSetCustomTitle(uuid, draft("管理§", false, "#FFFFFF"), "op");
            helper.assertTrue(adminCharset.status() == CustomTitleResult.Status.INVALID
                            && ruleSet(adminCharset.violations()).equals(EnumSet.of(Rule.FORMAT_CODE)),
                    "代设置仍校验字符集, 实为 " + adminCharset);
            CustomTitleResult adminDark = service.adminSetCustomTitle(uuid, draft("组", false, "#000000"), "op");
            helper.assertTrue(adminDark.status() == CustomTitleResult.Status.INVALID
                            && ruleSet(adminDark.violations()).equals(EnumSet.of(Rule.COLOR_TOO_DARK)),
                    "代设置仍校验亮度, 实为 " + adminDark);
            helper.assertTrue("管理组".equals(customText(connection, uuid)), "代设置被拒不得改库");
            helper.assertTrue(systemChatKeys(channel).contains("title.miningdim.custom.admin_set_notice"),
                    "代设置成功应提示在线玩家");

            helper.assertTrue("0".equals(updatedAt(connection, uuid)), "管理员预先备好的记录不带冷却 (冷却起点为 0)");
            service.grantSponsor(uuid, null, "op");
            helper.assertTrue(service.setCustomTitle(player, draft("【矿工】", false, "#FFFFFF")).status()
                    == CustomTitleResult.Status.APPLIED, "记录由管理员预先备好, 玩家自己的第一次设置仍不受冷却限制");
            CustomTitleResult overCooldown = service.adminSetCustomTitle(uuid, draft("巡查组", false, "#FFFFFF"), "op");
            helper.assertTrue(overCooldown.status() == CustomTitleResult.Status.APPLIED
                    && overCooldown.nextEditAt() == T0 + WEEK, "管理员代设置不受冷却限制, 回报玩家原有的冷却, 实为 "
                    + overCooldown);
            helper.assertTrue(String.valueOf(T0).equals(updatedAt(connection, uuid)),
                    "代设置既不开始也不清除玩家的冷却: 冷却起点仍是玩家自己那次修改");
            helper.assertTrue(service.setCustomTitle(player, draft("【矿工】", false, "#FFFFFF")).status()
                    == CustomTitleResult.Status.ON_COOLDOWN, "玩家自己的冷却照旧");

            helper.assertTrue(service.setCustomTitleLocked(uuid, true, "op") == CustomAdminResult.DONE, "锁定应执行");
            helper.assertTrue("1".equals(column(connection, "title_custom", "locked", uuid))
                            && "op".equals(column(connection, "title_custom", "locked_by", uuid)),
                    "锁定必须落库并记下执行者");
            helper.assertTrue(service.setCustomTitleLocked(uuid, true, "op") == CustomAdminResult.UNCHANGED,
                    "重复锁定应返回 UNCHANGED");
            service.clearCustomTitleCooldown(uuid, "op");
            helper.assertTrue(service.setCustomTitle(player, draft("【矿工】", false, "#FFFFFF")).status()
                    == CustomTitleResult.Status.LOCKED, "锁定后玩家不能修改 (即使没有冷却)");
            helper.assertTrue(service.adminSetCustomTitle(uuid, draft("值班组", false, "#FFFFFF"), "op").status()
                    == CustomTitleResult.Status.APPLIED, "锁定不影响管理员代设置");
            helper.assertTrue("1".equals(column(connection, "title_custom", "locked", uuid)),
                    "代设置不得解除锁定");
            helper.assertTrue(service.resetCustomTitle(uuid, "op") == CustomAdminResult.LOCKED,
                    "锁定中的记录拒绝清空, 以免锁定被悄悄删除");
            helper.assertTrue("值班组".equals(customText(connection, uuid)), "被拒的清空不得删记录");

            helper.assertTrue(service.setCustomTitleLocked(uuid, false, "op") == CustomAdminResult.DONE, "解锁应执行");
            helper.assertTrue("0".equals(column(connection, "title_custom", "locked", uuid))
                            && column(connection, "title_custom", "locked_by", uuid) == null,
                    "解锁必须清掉锁定与执行者");
            helper.assertTrue(service.setCustomTitleLocked(uuid, false, "op") == CustomAdminResult.UNCHANGED,
                    "重复解锁应返回 UNCHANGED");

            helper.assertTrue(service.equip(player, customId) == EquipResult.EQUIPPED, "前置: 佩戴专属称号");
            systemChatKeys(channel);
            helper.assertTrue(service.resetCustomTitle(uuid, "op") == CustomAdminResult.DONE, "清空应执行");
            helper.assertTrue(customText(connection, uuid) == null, "清空必须删除记录");
            helper.assertTrue(service.equipped(uuid).isEmpty() && equippedRow(connection, uuid) == null,
                    "清空正在佩戴的专属称号必须同时卸下");
            helper.assertTrue(systemChatKeys(channel).equals(List.of("title.miningdim.custom.reset_notice")),
                    "清空应提示在线玩家");
            helper.assertTrue(service.setCustomTitle(player, draft("【矿工】", false, "#FFFFFF")).status()
                    == CustomTitleResult.Status.APPLIED, "清空连同冷却一并清除, 玩家可以立即重新设置");
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 14. 经真实命令分发器驱动 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void commandsRunThroughTheRealDispatcherWithPermissions(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ITitleService previous = currentFacade();
        ServerPlayer player = null;
        try {
            TitleService service = newService(helper, connection, new AtomicLong(T0));
            TitleServices.registerTitleService(service);
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), "title-cmd-mock"));
            UUID uuid = player.getUUID();
            // GameTest 服务端没有用户缓存 (getProfileCache() 为 null), 原版 GameProfileArgument 按裸名字解析会 NPE;
            // 这里用选择器指向在线的 mock 玩家, 走的仍是同一个参数类型与同一条命令执行路径。
            String name = "@a[name=" + player.getGameProfile().getName() + "]";
            ResourceLocation customId = CustomTitle.idOf(uuid);
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            CapturingSource playerOut = new CapturingSource();
            CapturingSource adminOut = new CapturingSource();
            CommandSourceStack asPlayer = player.createCommandSourceStack().withSource(playerOut).withPermission(0);
            CommandSourceStack asAdmin = server.createCommandSourceStack().withSource(adminOut);

            // 权限门按解析结果判定: 被 requires 挡住的子命令解析不到可执行节点。不能只看执行是否抛错 —— 普通玩家
            // 用选择器本身就会被原版拒绝 (selector not allowed), 那样即使漏了权限门也测不出来。equip 的目标是单个
            // 在线玩家 (EntityArgument.player), 选择器要带 limit=1 才能解析。
            String single = "@a[name=" + player.getGameProfile().getName() + ",limit=1]";
            for (String adminOnly : List.of("mtitle grant " + name + " " + GOLD, "mtitle revoke " + name + " " + GOLD,
                    "mtitle list " + name, "mtitle equip " + single + " none", "mtitle sponsor grant " + name,
                    "mtitle sponsor grant " + name + " 30", "mtitle sponsor revoke " + name,
                    "mtitle sponsor info " + name, "mtitle sponsor list",
                    "mtitle custom admin set " + name + " #FFFFFF false 字", "mtitle custom admin reset " + name,
                    "mtitle custom admin lock " + name, "mtitle custom admin unlock " + name,
                    "mtitle custom admin cooldown " + name)) {
                helper.assertTrue(parsesToCommand(dispatcher, asAdmin, adminOnly), "管理员应能解析: " + adminOnly);
                helper.assertTrue(!parsesToCommand(dispatcher, asPlayer, adminOnly), "普通玩家不得执行管理员命令: " + adminOnly);
            }
            for (String playerOnly : List.of("mtitle mine", "mtitle wear none", "mtitle custom info",
                    "mtitle custom preview #FFFFFF false 字", "mtitle custom set #FFFFFF false 字")) {
                helper.assertTrue(parsesToCommand(dispatcher, asPlayer, playerOnly), "普通玩家应能解析: " + playerOnly);
                helper.assertTrue(!parsesToCommand(dispatcher, asAdmin, playerOnly), "控制台不是玩家, 看不到玩家子命令: "
                        + playerOnly);
            }

            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom set #FFD23F true 【矿工】", 0,
                    "title.miningdim.custom.not_sponsor");
            helper.assertTrue(countRows(connection, "title_custom") == 0, "非赞助玩家的提交不得落库");

            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle sponsor grant " + name + " 30", 1,
                    "title.miningdim.command.sponsor.granted_until");
            helper.assertTrue(String.valueOf(T0 + 30 * DAY).equals(column(connection, "title_sponsor", "expires_at",
                    uuid)), "命令发放 30 天资格");

            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom preview #FF3D3D,#FFD23F true ★矿 工★",
                    1, "title.miningdim.custom.preview");
            helper.assertTrue(countRows(connection, "title_custom") == 0, "预览命令不得写库");
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom set #FFFFFF maybe 矿工", 0,
                    "title.miningdim.custom.usage");
            List<String> invalid = expectCommand(helper, dispatcher, asPlayer, playerOut,
                    "mtitle custom set #000000 true 管 理", 0, "title.miningdim.custom.invalid.header");
            helper.assertTrue(invalid.contains(Rule.BANNED_WORD.translationKey())
                            && invalid.contains(Rule.COLOR_TOO_DARK.translationKey()),
                    "不合格项必须逐条列出, 实为 " + invalid);

            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom set #FF3D3D,#FFD23F true ★矿 工★", 1,
                    "title.miningdim.custom.applied");
            helper.assertTrue("★矿 工★".equals(customText(connection, uuid))
                            && "#FF3D3D,#FFD23F".equals(column(connection, "title_custom", "colors", uuid))
                            && "1".equals(column(connection, "title_custom", "bold", uuid)),
                    "命令提交必须按参数落库 (文字含空格原样保留)");
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom set #FFFFFF false 另一个", 0,
                    "title.miningdim.custom.cooldown");

            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle wear " + customId, 1,
                    "title.miningdim.command.wear.done");
            String worn = player.getDisplayName().getString();
            helper.assertTrue(("★矿 工★ " + player.getGameProfile().getName()).equals(worn),
                    "wear 专属称号后显示名原样带称号, 实为 " + worn);
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle mine", 1,
                    "title.miningdim.command.mine.header");
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom info", 1,
                    "title.miningdim.custom.info.header_self");
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle wear none", 1,
                    "title.miningdim.command.wear.cleared");
            helper.assertTrue(service.equipped(uuid).isEmpty(), "wear none 必须卸下");

            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle grant " + name + " " + customId, 0,
                    "title.miningdim.command.custom_reserved");
            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle revoke " + name + " " + customId, 0,
                    "title.miningdim.command.custom_reserved");
            helper.assertTrue(countRows(connection, "title_owned") == 0, "管理员命令同样不能发放专属称号");

            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle custom admin lock " + name, 1,
                    "title.miningdim.command.custom.admin.locked");
            helper.assertTrue("Server".equals(column(connection, "title_custom", "locked_by",
                    uuid)), "锁定记下执行者名");
            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle custom admin cooldown " + name, 1,
                    "title.miningdim.command.custom.admin.cooldown_cleared");
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom set #FFFFFF false 另一个", 0,
                    "title.miningdim.custom.locked");
            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle custom admin reset " + name, 0,
                    "title.miningdim.command.custom.admin.reset_locked");
            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle custom admin unlock " + name, 1,
                    "title.miningdim.command.custom.admin.unlocked");
            expectCommand(helper, dispatcher, asAdmin, adminOut,
                    "mtitle custom admin set " + name + " #FFFFFF false 管理组", 1,
                    "title.miningdim.command.custom.admin.set");
            helper.assertTrue("管理组".equals(customText(connection, uuid)), "管理员代设置放行违禁词");
            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle sponsor info " + name, 1,
                    "title.miningdim.command.sponsor.info.header");
            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle sponsor list", 1,
                    "title.miningdim.command.sponsor.list.header");
            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle custom admin reset " + name, 1,
                    "title.miningdim.command.custom.admin.reset");
            helper.assertTrue(customText(connection, uuid) == null, "reset 命令删除记录");
            expectCommand(helper, dispatcher, asAdmin, adminOut, "mtitle sponsor revoke " + name, 1,
                    "title.miningdim.command.sponsor.revoked");
            helper.assertTrue(countRows(connection, "title_sponsor") == 0, "sponsor revoke 删除资格");
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom set #FFFFFF false 矿工", 0,
                    "title.miningdim.custom.not_sponsor");
        } finally {
            if (player != null) {
                server.getPlayerList().remove(player);
            }
            restoreFacade(previous);
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 15. 两种语言的文案 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void titleMessagesExistInBothLanguages(GameTestHelper helper) throws IOException {
        JsonObject zh = readLang("zh_cn");
        JsonObject en = readLang("en_us");
        Set<String> zhKeys = titleKeys(zh);
        Set<String> enKeys = titleKeys(en);
        helper.assertTrue(zhKeys.equals(enKeys), "zh_cn 与 en_us 的 title.miningdim.* 键必须一一对应, 差异: "
                + symmetricDifference(zhKeys, enKeys));
        for (Rule rule : Rule.values()) {
            helper.assertTrue(zhKeys.contains(rule.translationKey()), "校验规则缺文案: " + rule.translationKey());
        }
        // 客户端按旧式格式码解析译文: § 连同其后一个字符会被吞掉, 文案里只能描述它 (U+00A7), 不能直接写它。
        List<String> formatCodeInText = new ArrayList<>();
        for (JsonObject lang : List.of(zh, en)) {
            for (String key : titleKeys(lang)) {
                if (GsonHelper.getAsString(lang, key).indexOf(SECTION_SIGN) >= 0) {
                    formatCodeInText.add(key);
                }
            }
        }
        helper.assertTrue(formatCodeInText.isEmpty(), "称号文案里不能出现 § 字符本身, 违规键: " + formatCodeInText);
        for (String key : List.of("title.miningdim.custom.desc", "title.miningdim.custom.invalid.header",
                "title.miningdim.sponsor.lapsed", "title.miningdim.sponsor.lapsed_unequipped",
                "title.miningdim.sponsor.granted_permanent_notice", "title.miningdim.sponsor.granted_until_notice",
                "title.miningdim.custom.admin_set_notice", "title.miningdim.custom.reset_notice")) {
            helper.assertTrue(zhKeys.contains(key), "服务端提示缺文案: " + key);
        }
        helper.succeed();
    }

    // ---- 16. 生产环境的阈值与冷却实时取自配置 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productionRulesAreReadFromTheLoadedConfig(GameTestHelper helper) {
        CustomTitleRules live = CustomTitleRules.fromConfig();
        long cooldown = TitleConfig.CUSTOM_EDIT_COOLDOWN_DAYS.get() * DAY;
        helper.assertTrue(live.maxLength() == TitleConfig.CUSTOM_MAX_LENGTH.get()
                        && live.maxContentChars() == TitleConfig.CUSTOM_MAX_CONTENT_CHARS.get(),
                "总长与内容字符上限必须各取各的配置项, 实为 " + live);
        helper.assertTrue(live.allowedSymbols().equals(CustomTitleRules.symbols(TitleConfig.CUSTOM_ALLOWED_SYMBOLS.get()))
                        && live.bannedWords().equals(List.copyOf(TitleConfig.CUSTOM_BANNED_WORDS.get())),
                "符号白名单与违禁词必须取自配置, 实为 " + live);
        helper.assertTrue(live.minLuminance() == TitleConfig.CUSTOM_MIN_LUMINANCE.get()
                        && live.editCooldownMillis() == cooldown,
                "亮度下限与冷却 (天数换算成毫秒) 必须取自配置, 实为 " + live);
        helper.assertTrue(live.selfServiceEnabled() == TitleConfig.CUSTOM_SELF_SERVICE_ENABLED.get(),
                "自助提交开关必须取自配置, 实为 " + live);

        // 生产构造器不注入规则与时钟: 预览 / 提交时的校验阈值、自助开关与冷却都经 fromConfig 实时读取。
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = null;
        try {
            TitleService service = new TitleService(new SqliteTitleRepository(connection), definitionsWithGold(), server);
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            service.grantSponsor(player.getUUID(), null, "op");
            CustomTitleResult tooMany = service.previewCustomTitle(player.getUUID(),
                    draft("字".repeat(live.maxContentChars() + 1), false, "#FFFFFF"));
            helper.assertTrue(tooMany.status() == CustomTitleResult.Status.INVALID
                            && ruleSet(tooMany.violations()).contains(Rule.CONTENT_TOO_MANY),
                    "超过配置的内容字符上限必须被拒, 实为 " + tooMany);
            helper.assertTrue(service.customSelfServiceEnabled() == live.selfServiceEnabled(),
                    "门面报告的自助开关必须与配置一致");
            long before = System.currentTimeMillis();
            CustomTitleResult submitted = service.setCustomTitle(player, draft("字", false, "#FFFFFF"));
            long after = System.currentTimeMillis();
            if (live.selfServiceEnabled()) {
                helper.assertTrue(submitted.status() == CustomTitleResult.Status.APPLIED
                                && submitted.nextEditAt() >= before + cooldown
                                && submitted.nextEditAt() <= after + cooldown,
                        "生产环境的冷却应为配置的天数, 实为 " + submitted);
            } else {
                helper.assertTrue(submitted.status() == CustomTitleResult.Status.SELF_SERVICE_DISABLED
                                && service.customTitleInfo(player.getUUID()).custom() == null,
                        "自助提交关闭时玩家提交必须被拒且不落库, 实为 " + submitted);
            }
        } finally {
            if (player != null) {
                server.getPlayerList().remove(player);
            }
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 17. 佩戴与提交修改时自己先做到期检查 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void equipAndSetRunTheExpiryCheckWithoutWaitingForThePeriodicOne(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = null;
        try {
            AtomicLong clock = new AtomicLong(T0);
            TitleService service = newService(helper, connection, clock);
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID uuid = player.getUUID();
            ResourceLocation customId = CustomTitle.idOf(uuid);
            EmbeddedChannel channel = channelOf(player);
            service.grant(player, GOLD, TitleSource.ADMIN, null);
            service.grantSponsor(uuid, 1, "op");
            service.setCustomTitle(player, draft("【矿工】", false, "#FFD23F"));
            service.equip(player, customId);
            systemChatKeys(channel);

            // 到期后不等巡检、直接提交修改: 先做到期检查 (当场卸下并提示), 再以没有资格拒绝。
            clock.set(T0 + DAY);
            helper.assertTrue(service.setCustomTitle(player, draft("新称号", false, "#FFFFFF")).status()
                    == CustomTitleResult.Status.NOT_SPONSOR, "到期后提交修改必须被拒");
            helper.assertTrue(service.equipped(uuid).isEmpty() && equippedRow(connection, uuid) == null
                            && service.displayPrefix(uuid) == null,
                    "提交修改时的到期检查必须当场卸下专属称号 (缓存、库与显示)");
            helper.assertTrue(systemChatKeys(channel).equals(List.of("title.miningdim.sponsor.lapsed_unequipped")),
                    "提交修改时当场卸下必须提示本人");

            // 续期后再戴上; 再次到期后直接换戴别的称号: 佩戴前同样先做到期检查。
            service.grantSponsor(uuid, 1, "op");
            service.equip(player, customId);
            systemChatKeys(channel);
            clock.set(T0 + 2 * DAY);
            helper.assertTrue(service.equip(player, GOLD) == EquipResult.EQUIPPED
                    && service.equipped(uuid).equals(Optional.of(GOLD)), "到期后仍可换戴自己拥有的普通称号");
            helper.assertTrue(systemChatKeys(channel).equals(List.of("title.miningdim.sponsor.lapsed_unequipped")),
                    "佩戴时的到期检查发现正戴着失效的专属称号, 必须先卸下并提示本人");
        } finally {
            if (player != null) {
                server.getPlayerList().remove(player);
            }
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 18. 登录时没卸成的失效专属称号由巡检重试 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lapsedTitleLeftOnByAFailedLoginUnequipIsRetriedByThePeriodicCheck(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = null;
        try {
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), "title-retry"));
            UUID uuid = player.getUUID();
            SqliteTitleRepository repository = new SqliteTitleRepository(connection);
            repository.upsertSponsor(uuid, "op", T0 - 30 * DAY, T0 - DAY);
            repository.saveCustomTitle(uuid, new CustomTitleStyle("【旧】", List.of(0xFFFFFF), false), T0 - 30 * DAY);
            repository.setEquipped(uuid, CustomTitle.idOf(uuid));
            TitleService service = new TitleService(repository, definitionsWithGold(), server, () -> T0,
                    CustomTitleGameTests::specRules);
            EmbeddedChannel channel = channelOf(player);
            systemChatKeys(channel);

            // 登录时的到期检查要写库卸下; 把连接切成只读, 让这一步像 SQLITE_BUSY、磁盘写满那样失败 (读照常)。
            // 登录钩子 (TitleSystem#onPlayerLoggedIn) 对这种失败只记日志、照常放玩家进服, 这里同样吞掉异常继续。
            exec(connection, "PRAGMA query_only=ON");
            boolean loginUnequipFailed = false;
            try {
                service.loadPlayer(player);
            } catch (TitleStoreException expected) {
                loginUnequipFailed = true;
            } finally {
                exec(connection, "PRAGMA query_only=OFF");
            }
            helper.assertTrue(loginUnequipFailed, "前置: 只读连接上登录时的卸下必须写库失败");
            helper.assertTrue(service.displayPrefix(uuid) != null && equippedRow(connection, uuid) != null,
                    "前置: 卸下失败后缓存与库里仍挂着失效的专属称号");

            service.checkSponsorExpiry(List.of(player));
            helper.assertTrue(service.equipped(uuid).isEmpty() && equippedRow(connection, uuid) == null
                            && service.displayPrefix(uuid) == null,
                    "巡检必须重试登录时没卸成的失效专属称号 (缓存、库与显示)");
            helper.assertTrue(systemChatKeys(channel).equals(List.of("title.miningdim.sponsor.lapsed_unequipped")),
                    "重试卸下成功时提示本人一次");
            service.checkSponsorExpiry(List.of(player));
            helper.assertTrue(systemChatKeys(channel).isEmpty(), "卸下之后巡检不再理会, 也不再提示");
        } finally {
            if (player != null) {
                server.getPlayerList().remove(player);
            }
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 19. 玩家命令既不泄露他人的专属称号, 也不点名执行处置的管理员 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerCommandsRevealNeitherOtherPlayersTitlesNorStaffNames(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ITitleService previous = currentFacade();
        ServerPlayer player = null;
        try {
            TitleService service = newService(helper, connection, new AtomicLong(T0));
            TitleServices.registerTitleService(service);
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), "title-privacy"));
            UUID uuid = player.getUUID();
            // 管理员预先备好记录、资格还没生效的离线玩家, 以及从没有过专属称号的离线玩家。
            UUID prepared = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            service.grantSponsor(uuid, null, "op");
            service.setCustomTitle(player, draft("【矿工】", false, "#FFFFFF"));
            service.adminSetCustomTitle(prepared, draft("【秘密】", false, "#FFFFFF"), "op");
            service.setCustomTitleLocked(uuid, true, "Mod_Alice");

            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            CapturingSource playerOut = new CapturingSource();
            CommandSourceStack asPlayer = player.createCommandSourceStack().withSource(playerOut).withPermission(0);
            List<String> problems = new ArrayList<>();

            run(dispatcher, asPlayer, "mtitle custom info");
            List<Component> info = playerOut.drain();
            if (!keysOf(info).contains("title.miningdim.custom.info.locked_self")
                    || keysOf(info).contains("title.miningdim.custom.info.locked") || textsOf(info).contains("Mod_Alice")) {
                problems.add("custom info 只应告诉玩家已被管理员锁定, 不点名执行者: " + keysOf(info) + " " + textsOf(info));
            }

            for (UUID other : List.of(prepared, stranger)) {
                ResourceLocation otherId = CustomTitle.idOf(other);
                EquipResult equip = service.equip(player, otherId);
                Integer code = run(dispatcher, asPlayer, "mtitle wear " + otherId);
                List<Component> wear = playerOut.drain();
                if (equip != EquipResult.NOT_OWNED || code == null || code != 0
                        || !keysOf(wear).equals(List.of("title.miningdim.command.wear.not_owned"))
                        || textsOf(wear).stream().anyMatch(text -> text.contains("秘密"))) {
                    problems.add("wear 他人的专属称号必须一律答未拥有, 既不渲染对方的称号, 也不暴露记录在不在: equip="
                            + equip + " 返回=" + code + " 反馈=" + keysOf(wear) + " " + textsOf(wear));
                }
            }
            if (service.equipped(uuid).isPresent()) {
                problems.add("佩戴他人的专属称号不得改动自己的佩戴, 实为 " + service.equipped(uuid));
            }

            // 管理员视角照常看得到是谁锁定的。
            CapturingSource adminOut = new CapturingSource();
            run(dispatcher, server.createCommandSourceStack().withSource(adminOut),
                    "mtitle sponsor info @a[name=" + player.getGameProfile().getName() + "]");
            List<Component> adminInfo = adminOut.drain();
            if (!keysOf(adminInfo).contains("title.miningdim.custom.info.locked")
                    || !textsOf(adminInfo).contains("Mod_Alice")) {
                problems.add("管理员的 sponsor info 应写明锁定的执行者: " + keysOf(adminInfo) + " " + textsOf(adminInfo));
            }
            helper.assertTrue(problems.isEmpty(), "玩家命令的可见范围不对: " + problems);
        } finally {
            if (player != null) {
                server.getPlayerList().remove(player);
            }
            restoreFacade(previous);
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 20. 自助提交关闭 (默认口径): 玩家只能预览, 专属称号由管理员代设置 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selfServiceOffLetsSponsorsPreviewButOnlyStaffSet(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("titles.db"));
        MinecraftServer server = helper.getLevel().getServer();
        ITitleService previous = currentFacade();
        ServerPlayer player = null;
        try {
            CustomTitleRules staffOnly = new CustomTitleRules(10, 8, CustomTitleRules.symbols(SPEC_SYMBOLS),
                    SPEC_BANNED, 0.18D, WEEK, false);
            AtomicLong clock = new AtomicLong(T0);
            TitleService service = new TitleService(new SqliteTitleRepository(connection), definitionsWithGold(),
                    server, clock::get, () -> staffOnly);
            TitleServices.registerTitleService(service);
            String name = "title-staffonly";
            player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), name));
            UUID uuid = player.getUUID();
            CustomTitleDraft wanted = draft("【矿工】", false, "#FFD23F");
            List<String> problems = new ArrayList<>();

            if (service.setCustomTitle(player, wanted).status() != CustomTitleResult.Status.NOT_SPONSOR) {
                problems.add("没有资格时仍应先答 NOT_SPONSOR");
            }
            EmbeddedChannel channel = channelOf(player);
            systemChatKeys(channel);
            service.grantSponsor(uuid, null, "op");
            List<String> grantKeys = systemChatKeys(channel);
            if (!grantKeys.contains("title.miningdim.sponsor.granted_permanent_notice_staff")
                    || grantKeys.contains("title.miningdim.sponsor.granted_permanent_notice")) {
                problems.add("发放资格的提示应让玩家预览后找管理员, 不能指向会被拒的 custom set, 实为 " + grantKeys);
            }
            if (service.customSelfServiceEnabled()) {
                problems.add("门面应报告自助提交已关闭");
            }
            CustomTitleResult refused = service.setCustomTitle(player, wanted);
            if (refused.status() != CustomTitleResult.Status.SELF_SERVICE_DISABLED
                    || service.customTitleInfo(uuid).custom() != null) {
                problems.add("自助提交关闭时赞助玩家的提交必须被拒且不落库, 实为 " + refused);
            }
            if (service.previewCustomTitle(uuid, wanted).status() != CustomTitleResult.Status.VALID) {
                problems.add("自助提交关闭时预览仍应可用");
            }

            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            CapturingSource playerOut = new CapturingSource();
            CommandSourceStack asPlayer = player.createCommandSourceStack().withSource(playerOut).withPermission(0);
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom set #FFD23F false 【矿工】", 0,
                    "title.miningdim.custom.self_service_disabled");
            List<String> previewKeys = expectCommand(helper, dispatcher, asPlayer, playerOut,
                    "mtitle custom preview #FFD23F false 【矿工】", 1, "title.miningdim.custom.preview_admin_hint");
            if (previewKeys.contains("title.miningdim.custom.preview_cooldown")) {
                problems.add("自助提交关闭时预览不该再提修改冷却, 实为 " + previewKeys);
            }
            expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle wear " + CustomTitle.idOf(uuid), 0,
                    "title.miningdim.custom.not_set_staff");
            List<String> infoKeys = expectCommand(helper, dispatcher, asPlayer, playerOut, "mtitle custom info", 1,
                    "title.miningdim.custom.info.staff_managed");
            if (infoKeys.contains("title.miningdim.custom.info.next_edit")) {
                problems.add("自助提交关闭时玩家的 custom info 不该再报下次可修改时间, 实为 " + infoKeys);
            }

            // 管理员代设置照常生效, 有资格时不附带提醒, 玩家随即可以佩戴。
            CapturingSource adminOut = new CapturingSource();
            CommandSourceStack asAdmin = server.createCommandSourceStack().withSource(adminOut);
            String adminSet = "mtitle custom admin set @a[name=" + name + "] #FFD23F false 【矿工】";
            List<String> setKeys = expectCommand(helper, dispatcher, asAdmin, adminOut, adminSet, 1,
                    "title.miningdim.command.custom.admin.set");
            if (setKeys.contains("title.miningdim.command.custom.admin.not_sponsor_warning")) {
                problems.add("对有资格的玩家代设置不该提醒补发资格, 实为 " + setKeys);
            }
            if (service.equip(player, CustomTitle.idOf(uuid)) != EquipResult.EQUIPPED) {
                problems.add("管理员代设置后赞助玩家应能佩戴专属称号");
            }

            // 资格失效后再代设置: 照样保存, 但提醒管理员称号暂不生效。
            service.revokeSponsor(uuid, "op");
            List<String> warnedKeys = expectCommand(helper, dispatcher, asAdmin, adminOut, adminSet, 1,
                    "title.miningdim.command.custom.admin.set");
            if (!warnedKeys.contains("title.miningdim.command.custom.admin.not_sponsor_warning")) {
                problems.add("对没有有效资格的玩家代设置应提醒补发资格, 实为 " + warnedKeys);
            }
            helper.assertTrue(problems.isEmpty(), "自助提交关闭口径不对: " + problems);
        } finally {
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

    /** 设计文档 13.3 / 13.4 的默认阈值。 */
    private static CustomTitleRules specRules() {
        return new CustomTitleRules(10, 8, CustomTitleRules.symbols(SPEC_SYMBOLS), SPEC_BANNED, 0.18D, WEEK, true);
    }

    /** 由码点拼出字符串: 不可见字符、私用区字符与 emoji 不直接写进源码, 一律按码点构造。 */
    private static String codePoints(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static CustomTitleDraft draft(String text, boolean bold, String... colors) {
        return new CustomTitleDraft(text, List.of(colors), bold);
    }

    private static List<CustomTitleViolation> violations(CustomTitleRules rules, String text, String... colors) {
        return CustomTitleValidator.validate(draft(text, false, colors), rules, true).violations();
    }

    private static CustomTitleViolation firstViolation(CustomTitleRules rules, String text) {
        List<CustomTitleViolation> found = violations(rules, text, "#FFFFFF");
        if (found.isEmpty()) {
            throw new AssertionError("预期 '" + text + "' 不合格, 却通过了校验");
        }
        return found.get(0);
    }

    private static Set<Rule> ruleSet(List<CustomTitleViolation> violations) {
        Set<Rule> rules = EnumSet.noneOf(Rule.class);
        violations.forEach(violation -> rules.add(violation.rule()));
        return rules;
    }

    private static void expectValid(GameTestHelper helper, CustomTitleRules rules, String text) {
        List<CustomTitleViolation> found = violations(rules, text, "#FFFFFF");
        helper.assertTrue(found.isEmpty(), "'" + text + "' 应通过校验, 实际不合格项 " + found);
    }

    private static void expectExactly(GameTestHelper helper, CustomTitleRules rules, String text, Rule... expected) {
        Set<Rule> found = ruleSet(violations(rules, text, "#FFFFFF"));
        Set<Rule> wanted = EnumSet.noneOf(Rule.class);
        wanted.addAll(List.of(expected));
        helper.assertTrue(found.equals(wanted), "'" + text + "' 应恰好违反 " + wanted + ", 实为 " + found);
    }

    private static void expectColors(GameTestHelper helper, CustomTitleRules rules, List<String> colors,
                                     Rule... expected) {
        Set<Rule> found = ruleSet(violations(rules, "字", colors.toArray(String[]::new)));
        Set<Rule> wanted = EnumSet.noneOf(Rule.class);
        wanted.addAll(List.of(expected));
        helper.assertTrue(found.equals(wanted), "颜色 " + colors + " 应恰好违反 " + wanted + ", 实为 " + found);
    }

    private static TitleDefinitions definitionsWithGold() {
        TitleDefinitions definitions = new TitleDefinitions();
        definitions.install(Map.of(GOLD, new TitleDefinition(GOLD, Component.literal("金色"), TierPalette.GOLD,
                List.of(), null, null, TierPalette.GOLD.defaultSort())));
        return definitions;
    }

    private static TitleService newService(GameTestHelper helper, Connection connection, AtomicLong clock) {
        return new TitleService(new SqliteTitleRepository(connection), definitionsWithGold(),
                helper.getLevel().getServer(), clock::get, CustomTitleGameTests::specRules);
    }

    @Nullable
    private static ITitleService currentFacade() {
        return TitleServices.isRegistered() ? TitleServices.titleService() : null;
    }

    private static void restoreFacade(@Nullable ITitleService previous) {
        if (previous == null) {
            TitleServices.reset();
        } else {
            TitleServices.registerTitleService(previous);
        }
    }

    /** 在外层事务体内调用一个写操作, 没按约定抛 IllegalStateException 时记下它的名字。 */
    private static void expectRejectedInTransaction(List<String> joined, String name, Runnable operation) {
        try {
            operation.run();
            joined.add(name);
        } catch (IllegalStateException expected) {
            // 按约定拒绝。
        }
    }

    /** 经服务端真实的命令分发器执行一条命令 (不带斜杠); 解析或权限不通过时返回 null。 */
    @Nullable
    private static Integer run(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                               String command) {
        try {
            return dispatcher.execute(command, source);
        } catch (CommandSyntaxException rejected) {
            return null;
        }
    }

    /**
     * 该命令源能否把整条命令解析到一个可执行节点: 任何一层 requires 不放行, 解析都会停在那一层 (剩余输入没读完、
     * 没有可执行的命令)。只解析不执行, 所以不受执行期检查 (如普通玩家用选择器被原版拒绝) 的干扰。
     */
    private static boolean parsesToCommand(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                                           String command) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
        return !parse.getReader().canRead() && parse.getContext().getCommand() != null;
    }

    /** 执行命令并断言返回值与反馈里出现的翻译键; 返回这条命令产生的全部翻译键。 */
    private static List<String> expectCommand(GameTestHelper helper, CommandDispatcher<CommandSourceStack> dispatcher,
                                              CommandSourceStack source, CapturingSource output, String command,
                                              int expectedResult, String expectedKey) {
        output.messages.clear();
        Integer result = run(dispatcher, source, command);
        List<String> keys = output.drainKeys();
        helper.assertTrue(result != null && result == expectedResult,
                "/" + command + " 应返回 " + expectedResult + ", 实为 " + result + " (反馈 " + keys + ")");
        helper.assertTrue(keys.contains(expectedKey), "/" + command + " 的反馈应含 " + expectedKey + ", 实为 " + keys);
        return keys;
    }

    private static EmbeddedChannel channelOf(ServerPlayer player) {
        return (EmbeddedChannel) player.connection.connection.channel();
    }

    /** 读空 mock 连接的出站队列, 按顺序返回其中系统聊天消息的翻译键 (关键步骤前也用它丢弃积压的包)。 */
    private static List<String> systemChatKeys(EmbeddedChannel channel) {
        List<String> keys = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet
                        && packet.content().getContents() instanceof TranslatableContents translatable) {
                    keys.add(translatable.getKey());
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return keys;
    }

    private static List<String> keysOf(List<Component> messages) {
        List<String> keys = new ArrayList<>();
        messages.forEach(message -> collectKeys(message, keys));
        return keys;
    }

    /** 消息里出现的全部字面文字 (字面量片段与翻译参数, 含嵌套); 用来断言某段文字没有发给玩家。 */
    private static List<String> textsOf(List<Component> messages) {
        List<String> texts = new ArrayList<>();
        messages.forEach(message -> collectTexts(message, texts));
        return texts;
    }

    private static void collectTexts(Component component, List<String> texts) {
        if (component.getContents() instanceof LiteralContents literal) {
            texts.add(literal.text());
        } else if (component.getContents() instanceof TranslatableContents translatable) {
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested) {
                    collectTexts(nested, texts);
                } else {
                    texts.add(String.valueOf(argument));
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            collectTexts(sibling, texts);
        }
    }

    private static void collectKeys(Component component, List<String> keys) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            keys.add(translatable.getKey());
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested) {
                    collectKeys(nested, keys);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            collectKeys(sibling, keys);
        }
    }

    private static int colorOf(Component piece) {
        TextColor color = piece.getStyle().getColor();
        return color == null ? -1 : color.getValue();
    }

    private static String hex(int color) {
        return String.format("#%06X", color);
    }

    @Nullable
    private static String customText(Connection connection, UUID player) {
        return column(connection, "title_custom", "text", player);
    }

    @Nullable
    private static String updatedAt(Connection connection, UUID player) {
        return column(connection, "title_custom", "updated_at", player);
    }

    @Nullable
    private static String equippedRow(Connection connection, UUID player) {
        return column(connection, "title_equipped", "title_id", player);
    }

    /** 直接读库: 某张称号表里该玩家那一行的某一列 (文本形式); 没有该行或值为 NULL 时为 null。 */
    @Nullable
    private static String column(Connection connection, String table, String column, UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void exec(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int countRows(Connection connection, String table) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : -1;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Set<String> titleKeys(JsonObject lang) {
        return lang.keySet().stream().filter(key -> key.startsWith("title.miningdim."))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> symmetricDifference(Set<String> left, Set<String> right) {
        Set<String> difference = new TreeSet<>(left);
        difference.addAll(right);
        Set<String> common = new TreeSet<>(left);
        common.retainAll(right);
        difference.removeAll(common);
        return difference;
    }

    private static JsonObject readLang(String language) throws IOException {
        String path = "assets/" + MiningConstants.MODID + "/lang/" + language + ".json";
        InputStream stream = CustomTitleGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        }
    }

    /** 收集命令反馈的命令源: 成功与失败都收, 不向管理员广播。 */
    private static final class CapturingSource implements CommandSource {

        private final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        /** 取出已收到的全部消息, 并清空。 */
        private List<Component> drain() {
            List<Component> drained = List.copyOf(messages);
            messages.clear();
            return drained;
        }

        /** 取出已收到消息里出现的全部翻译键 (含嵌套的参数与子片段), 并清空。 */
        private List<String> drainKeys() {
            return keysOf(drain());
        }
    }
}
