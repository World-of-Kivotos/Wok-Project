package com.miningdim.webui;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * {@link WebUiPageUrl} 的归一化 / 比对契约 GameTest (F007)。
 *
 * 服务端进程纪律: 本类只触碰 {@link WebUiPageUrl} 本身, 严禁 classload 任何 com.miningdim.client.webui.*
 * 渲染类或 MCEF —— GameTest 是服务端进程, 一旦触链即 NoClassDefFoundError (与被测类类注释的口径一致)。
 *
 * 全部断言用字面量期望值逐字比对 (不拿被测方法自己的输出当期望), 覆盖:
 *  - normalize 的每一条归一化步骤 (host 小写化 / 默认端口省略 / 非默认端口保留 / dot-segment 消解 /
 *    fragment 丢弃 / query 保留 / unreserved 转义还原 / 保留字符十六进制大写 / data: 直通);
 *  - normalize 对不可用页面 URL 的拒绝 (非 http(s) scheme / 缺 host / 带凭据 / 畸形百分号转义);
 *  - matchesNormalized 对等价写法的放行与对换 host/换 scheme/换 query 的拒绝, 以及对不可解析候选
 *    (如 about:blank) 和 null 入参的静默拒绝 (不抛异常进 CEF 回调线程)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WebUiPageUrlGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui";

    /**
     * normalize 的每一条归一化规则各锁一条字面量期望。删掉任意一条归一化步骤 (host 转小写 / 补尾斜杠 /
     * 去默认端口 / 丢 fragment / dot-segment 消解 / 转义还原) 本方法至少挂一条断言。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void normalizeCanonicalisesHostPortPathAndDropsFragment(GameTestHelper helper) {
        assertNormalizesTo(helper, "https://UI.Example.COM", "https://ui.example.com/",
                "host 必须转小写, 且空路径必须补尾斜杠 (host-only 地址是 F007 里必然踩中的那条)");

        assertNormalizesTo(helper, "https://ui.example.com:443/app/", "https://ui.example.com/app/",
                "https 的默认端口 443 显式写出时必须被省略");

        assertNormalizesTo(helper, "http://ui.example.com:80/app", "http://ui.example.com/app",
                "http 的默认端口 80 显式写出时必须被省略");

        assertNormalizesTo(helper, "http://ui.example.com:5173/", "http://ui.example.com:5173/",
                "非默认端口 (5173, 典型 Vite dev server 端口) 必须原样保留, 不得被误当默认端口丢弃");

        assertNormalizesTo(helper, "HTTPS://ui.example.com/app", "https://ui.example.com/app",
                "scheme 必须转小写");

        assertNormalizesTo(helper, "https://ui.example.com/app/#/market/list", "https://ui.example.com/app/",
                "fragment 必须被丢弃 (hash router 每次翻页都在改它, 参与比较会把切面板误判成换页面)");

        assertNormalizesTo(helper, "https://ui.example.com/a/./b/../c", "https://ui.example.com/a/c",
                "dot-segment (. 与 ..) 必须被消解, 否则 /a/../b 与 /b 会被判定为两个不同页面");

        assertNormalizesTo(helper, "https://ui.example.com/%7euser", "https://ui.example.com/~user",
                "落在 RFC 3986 unreserved 集合内的百分号转义必须还原成字符本身 (%7e -> ~)");

        assertNormalizesTo(helper, "https://ui.example.com/a%2fb", "https://ui.example.com/a%2Fb",
                "保留字符 (如 /) 的百分号转义不得还原, 但十六进制字母必须统一转大写 (%2f -> %2F)");

        assertNormalizesTo(helper, "https://ui.example.com/app?v=2", "https://ui.example.com/app?v=2",
                "query 串必须原样保留 (架构决策 J4: 前端走 hash router, query 变化才是真的换文档实例)");

        assertNormalizesTo(helper, "http://webui_host:5173/", "http://webui_host:5173/",
                "host 带下划线 (docker-compose 服务名的典型形态) 必须被接受并保留非默认端口, 而不是因为 "
                        + "java.net.URI.getHost() 对下划线的严格校验退化成 null 就整条判非法 (F007 复核修正)");

        assertNormalizesTo(helper, "http://Webui_Host/app", "http://webui_host/app",
                "host 带下划线时同样要转小写并省略默认端口 80, 与非下划线 host 的归一化规则一致");

        String dataUri = "data:text/html;base64,PGh0bWw+";
        helper.assertTrue(WebUiPageUrl.normalize(dataUri).equals(dataUri),
                "data: URI 必须原样直通不做任何加工 (jar 内置开箱页走这条, 加工即破), got "
                        + WebUiPageUrl.normalize(dataUri));

        helper.succeed();
    }

    /**
     * normalize 对不可用页面 URL 的拒绝: 每个入参各自 try/catch 转 boolean 断言 (判据不是生吞)。
     * 删掉任意一条前置校验 (scheme 白名单 / host 非空校验 / userinfo 拒绝 / 百分号转义合法性校验)
     * 本方法至少挂一条断言。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void normalizeRejectsUnusablePageUrls(GameTestHelper helper) {
        assertNormalizeThrows(helper, "ftp://ui.example.com/",
                "非 http(s) scheme (ftp) 必须被拒绝");
        assertNormalizeThrows(helper, "/app/index.html",
                "无 scheme 的相对地址必须被拒绝");
        assertNormalizeThrows(helper, "https://",
                "无 host 的 URL 必须被拒绝");
        assertNormalizeThrows(helper, "https://ops:secret@ui.example.com/",
                "带凭据 (userinfo) 的 URL 必须被拒绝, 防止凭据悄悄嵌进后续每一次比较");
        assertNormalizeThrows(helper, "https://ops:secret@webui_host/",
                "host 带下划线时 java.net.URI 会把凭据校验一起退化掉 (getRawUserInfo() 也变 null), "
                        + "resolveHostAndPort 的手工兜底必须重新执行同一条凭据禁令, 不能因为退化到这条分支就放宽");
        assertNormalizeThrows(helper, "https://ui.example.com/%zz",
                "畸形百分号转义 (%zz 不是合法十六进制) 必须被拒绝");

        helper.succeed();
    }

    /**
     * matchesNormalized 的放行/拒绝契约。删掉任意一条真实比较逻辑 (整段改成无脑 return true, 或
     * normalize 内任一归一化步骤被删) 本方法至少挂一条断言。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void matchesNormalizedAcceptsEquivalentAndRejectsForeignUrls(GameTestHelper helper) {
        String allowed = WebUiPageUrl.normalize("https://ui.example.com");

        helper.assertTrue(WebUiPageUrl.matchesNormalized(allowed, "https://ui.example.com:443/#/hub"),
                "显式默认端口 + fragment 的等价写法必须放行 (F007 的三类等价写法之一)");

        helper.assertTrue(WebUiPageUrl.matchesNormalized(allowed, "https://UI.EXAMPLE.COM/"),
                "host 大小写不同的等价写法必须放行");

        helper.assertTrue(!WebUiPageUrl.matchesNormalized(allowed, "https://evil.example.com/"),
                "换 host 必须被拒绝 (这道门的本职: 防止被指向一个陌生文档)");

        helper.assertTrue(!WebUiPageUrl.matchesNormalized(allowed, "http://ui.example.com/"),
                "换 scheme (https -> http) 必须被拒绝");

        helper.assertTrue(!WebUiPageUrl.matchesNormalized(allowed, "https://ui.example.com/?a=1"),
                "query 参与比较: 附加 query 串必须被判定为不同页面");

        helper.assertTrue(!WebUiPageUrl.matchesNormalized(allowed, "about:blank"),
                "无法解析的候选地址 (about:blank, CEF 实时回读可能给出) 必须判定为不匹配, "
                        + "且不得把异常抛进调用方 (CEF 回调线程没有为业务异常准备的顶层边界)");

        helper.assertTrue(!WebUiPageUrl.matchesNormalized(allowed, null),
                "candidateRaw 为 null 必须判定为不匹配, 不得抛出");

        helper.assertTrue(!WebUiPageUrl.matchesNormalized(null, "https://ui.example.com/"),
                "normalizedAllowed 为 null 必须判定为不匹配, 不得抛出");

        helper.succeed();
    }

    private static void assertNormalizesTo(GameTestHelper helper, String input, String expected, String reason) {
        String actual = WebUiPageUrl.normalize(input);
        helper.assertTrue(actual.equals(expected),
                reason + " -- expected \"" + expected + "\" but got \"" + actual + "\" (input \"" + input + "\")");
    }

    private static void assertNormalizeThrows(GameTestHelper helper, String input, String reason) {
        boolean threw = false;
        try {
            WebUiPageUrl.normalize(input);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        helper.assertTrue(threw, reason + " -- normalize(\"" + input + "\") must throw IllegalArgumentException");
    }
}
