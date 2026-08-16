package com.miningdim.webui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * WebUI 页面 URL 的归一化与比对 (F007)。
 *
 * 客户端桥 {@code WebUiBridge} 登记的"允许页面"来自运维在 {@code webui.url} 里手打的字面量, 而
 * onQuery 里比对的是 CEF/Chromium 回读的实时文档 URL —— 两者字符串上等价但字面不同 (尾斜杠、大小写、
 * 显式写出的默认端口、百分号编码大小写差异) 时, 旧的整串精确匹配会把合法页面误判为不可信, 导致全部
 * action 被拒。本类把两侧都过同一套归一化再比较, 消除这类误判, 同时保留对真正换文档 (换 host/换路径)
 * 的拒绝能力。
 *
 * 本类刻意只依赖 JDK (java.net.URI / java.util.Locale): 服务端 GameTest 进程会直接 classload 它
 * (用于验证配置读取口径), 一旦引入 net.minecraft.* / org.cef.* 等客户端专属类型, GameTest 进程会
 * 在 classload 阶段抛 NoClassDefFoundError。
 */
public final class WebUiPageUrl {

    private WebUiPageUrl() {
    }

    /**
     * 把一个 WebUI 页面 URL 归一化成规范形式, 用于登记 (setAllowedPage) 与比对 (onQuery) 双方
     * 统一口径。规则见类顶注与下方逐步实现。
     *
     * @throws IllegalArgumentException url 不是合法 URI, 或不满足 WebUI 页面 URL 的最低约束
     *         (scheme 必须是 http/https, 必须带 host, 不得带凭据)。
     */
    public static String normalize(String url) {
        if (url.startsWith("data:")) {
            // data: 是不可分层的 opaque URI: 它的 scheme-specific-part 就是完整的 base64 负载,
            // 不存在 host/path/query 这些可归一化的分量。宿主自己 base64 编出来的字符串与 CEF
            // 回读的必须逐字节相同, 对它做任何加工只会引入新的不匹配, 因此原样放行。
            return url;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("malformed WebUI page URL: " + url, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("WebUI page URL is missing a scheme: " + url);
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
            throw new IllegalArgumentException(
                    "WebUI page URL scheme must be http or https, got \"" + scheme + "\": " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("WebUI page URL is missing a host: " + url);
        }
        if (uri.getRawUserInfo() != null) {
            // 页面 URL 带凭据 (user:pass@host) 是配置错误: WebUI 前端不走 HTTP Basic 鉴权,
            // 静默接受只会把凭据悄悄嵌进以后每一次 URL 比较里。
            throw new IllegalArgumentException("WebUI page URL must not carry userinfo/credentials: " + url);
        }

        // URI.getHost() 对 IPv6 字面量返回带方括号的形式 (如 "[::1]"), 直接转小写即可, 方括号不受影响。
        String lowerHost = host.toLowerCase(Locale.ROOT);

        int port = uri.getPort();
        int defaultPort = "https".equals(lowerScheme) ? 443 : 80;
        // 端口省略或等于该 scheme 的默认端口时不写出: 运维把默认端口显式打出来 (如 ":80") 与不写
        // 端口在语义上是同一个地址, 保留差异只会制造第二种"看起来不同但其实相同"的误判来源。
        String portSegment = (port == -1 || port == defaultPort) ? "" : (":" + port);

        // 先对整条 URI 做 dot-segment 归一化 (消除 . 与 .. 段), 再取归一化后的路径, 否则
        // "/a/../b" 与 "/b" 会被判定为两个不同页面。
        URI dotNormalized = uri.normalize();
        String rawPath = dotNormalized.getRawPath();
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : normalizePercentEncoding(rawPath);

        // query 保留而不是丢弃: 页面路由走前端自己的 hash router (架构决策 J4), query 串一旦变化
        // 就是运维明确换了一个查询参数不同的文档实例, 丢掉它等于放宽这道门。
        String rawQuery = dotNormalized.getRawQuery();
        String querySegment = rawQuery == null ? "" : ("?" + normalizePercentEncoding(rawQuery));

        // fragment 一律丢弃: hash router 每次翻页/切面板都在改 URL 的 fragment 部分, 这是最先
        // 咬人的一条差异 —— 如果参与比较, 玩家在 SPA 内切一次面板就会被判定成"换了页面"而被拒。

        return lowerScheme + "://" + lowerHost + portSegment + path + querySegment;
    }

    /**
     * 把已经归一化过的允许页面 (normalizedAllowed) 与实时候选 URL (candidateRaw, 未归一化) 比较。
     *
     * @param normalizedAllowed 必须是已经过 {@link #normalize} 处理的结果 (由调用方保证, 本方法不重复归一化它)。
     * @param candidateRaw 未归一化的候选 URL, 典型来源是 CEF 实时回读的 {@code cefBrowser.getURL()}。
     */
    public static boolean matchesNormalized(String normalizedAllowed, String candidateRaw) {
        if (normalizedAllowed == null || candidateRaw == null) {
            return false;
        }
        String candidateNormalized;
        try {
            candidateNormalized = normalize(candidateRaw);
        } catch (IllegalArgumentException e) {
            // 这个 catch 是判据, 不是生吞: 被比较的是 CEF 实时回读的文档 URL, 它可能是
            // about:blank / chrome-error:// 这类根本不该被信任的地址, 解析不了就等于不匹配。
            // 不能让这里的异常抛进 CEF 的回调线程 —— 那条线程没有为业务异常准备的顶层边界。
            return false;
        }
        return normalizedAllowed.equals(candidateNormalized);
    }

    /**
     * 对路径/query 分量做百分号编码规范化: 校验每个 % 转义都完整合法, 十六进制字母统一转大写,
     * 若该转义解码出的单字节落在 RFC 3986 unreserved 集合内则还原成该字符本身 (与 "直接写这个字符"
     * 语义等价, 消除 "%2E" 与 "." 之类同一路径的两种写法互相不匹配的问题)。
     */
    private static String normalizePercentEncoding(String s) {
        StringBuilder result = new StringBuilder(s.length());
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c != '%') {
                result.append(c);
                i++;
                continue;
            }
            if (i + 2 >= len) {
                throw new IllegalArgumentException("dangling percent-encoding near index " + i + " in \"" + s + "\"");
            }
            char h1 = s.charAt(i + 1);
            char h2 = s.charAt(i + 2);
            int d1 = Character.digit(h1, 16);
            int d2 = Character.digit(h2, 16);
            if (d1 < 0 || d2 < 0) {
                throw new IllegalArgumentException(
                        "invalid percent-encoding \"%" + h1 + h2 + "\" in \"" + s + "\"");
            }
            int decoded = (d1 << 4) | d2;
            if (isUnreserved(decoded)) {
                result.append((char) decoded);
            } else {
                result.append('%').append(Character.toUpperCase(h1)).append(Character.toUpperCase(h2));
            }
            i += 3;
        }
        return result.toString();
    }

    /** RFC 3986 unreserved 集合: A-Z a-z 0-9 - . _ ~ */
    private static boolean isUnreserved(int codeUnit) {
        return (codeUnit >= 'A' && codeUnit <= 'Z')
                || (codeUnit >= 'a' && codeUnit <= 'z')
                || (codeUnit >= '0' && codeUnit <= '9')
                || codeUnit == '-' || codeUnit == '.' || codeUnit == '_' || codeUnit == '~';
    }
}
