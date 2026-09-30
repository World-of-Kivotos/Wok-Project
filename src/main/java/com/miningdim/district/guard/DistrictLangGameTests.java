package com.miningdim.district.guard;

import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.district.notice.DistrictNoticeKind;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自管区的语言键中英成对 (设计文档 22.13): 别的模块都有这条, 自管区还没有。district.miningdim.* 的键在 zh_cn 与 en_us 里
 * 一一对应, 守卫的提示键都在, 值不为空; 每一种聊天通知都有键, 占位符个数与参数个数一致 (22.10)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictLangGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_lang";
    private static final String PREFIX = "district.miningdim.";

    private DistrictLangGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyDistrictKeyExistsInBothLanguages(GameTestHelper helper) throws IOException {
        JsonObject zh = readJson("assets/" + MiningConstants.MODID + "/lang/zh_cn.json");
        JsonObject en = readJson("assets/" + MiningConstants.MODID + "/lang/en_us.json");
        Set<String> zhKeys = districtKeys(zh);
        Set<String> enKeys = districtKeys(en);
        Set<String> onlyZh = new TreeSet<>(zhKeys);
        onlyZh.removeAll(enKeys);
        Set<String> onlyEn = new TreeSet<>(enKeys);
        onlyEn.removeAll(zhKeys);
        helper.assertTrue(onlyZh.isEmpty() && onlyEn.isEmpty(),
                "自管区的键中英要成对: 只有中文的 " + onlyZh + ", 只有英文的 " + onlyEn);
        for (String key : GuardEvents.keys()) {
            helper.assertTrue(zhKeys.contains(key), "守卫的提示键 " + key + " 要在语言文件里");
        }
        for (String key : PersonalClaimGuard.keys()) {
            helper.assertTrue(zhKeys.contains(key), "个人圈地限制的提示键 " + key + " 要在语言文件里");
        }
        Set<String> blank = new TreeSet<>();
        for (String key : zhKeys) {
            if (GsonHelper.getAsString(zh, key).isBlank() || GsonHelper.getAsString(en, key).isBlank()) {
                blank.add(key);
            }
        }
        helper.assertTrue(blank.isEmpty(), "值不能为空: " + blank);
        helper.succeed();
    }

    /**
     * 每一种通知 (22.10) 与上线补发的头一行在两种语言里都有键, 占位符恰好是 %1$s … %N$s (N = 参数个数): 少一个参数会
     * 原样显示成 "%3$s", 多一个会被丢掉。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyNoticeKindHasAKey(GameTestHelper helper) throws IOException {
        JsonObject zh = readJson("assets/" + MiningConstants.MODID + "/lang/zh_cn.json");
        JsonObject en = readJson("assets/" + MiningConstants.MODID + "/lang/en_us.json");
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put(DistrictNoticeKind.HEADER_KEY, 1);
        for (DistrictNoticeKind kind : DistrictNoticeKind.values()) {
            helper.assertTrue(DistrictNoticeKind.fromWire(kind.wire()) == kind, "wire 值要能读回: " + kind);
            expected.put(kind.key(), kind.arity());
        }
        helper.assertTrue(expected.size() == 25, "22.10: 24 个 kind 加头一行共 25 个键, 实为 " + expected.size());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            for (Map.Entry<String, JsonObject> lang : Map.of("zh_cn", zh, "en_us", en).entrySet()) {
                if (!lang.getValue().has(entry.getKey())) {
                    problems.add(lang.getKey() + " 缺 " + entry.getKey());
                    continue;
                }
                Set<Integer> indexes = placeholderIndexes(GsonHelper.getAsString(lang.getValue(), entry.getKey()));
                Set<Integer> want = new TreeSet<>();
                for (int i = 1; i <= entry.getValue(); i++) {
                    want.add(i);
                }
                if (!indexes.equals(want)) {
                    problems.add(lang.getKey() + " " + entry.getKey() + " 的占位符 " + indexes + ", 应为 " + want);
                }
            }
        }
        Set<String> stray = new TreeSet<>();
        for (String key : districtKeys(zh)) {
            if (key.startsWith(DistrictNoticeKind.KEY_PREFIX) && !expected.containsKey(key)) {
                stray.add(key);
            }
        }
        helper.assertTrue(problems.isEmpty(), "通知键的问题: " + problems);
        helper.assertTrue(stray.isEmpty(), "语言文件里有代码不认识的通知键: " + stray);
        helper.succeed();
    }

    /** 值里的占位符编号: %N$s 取 N, 不带编号的 %s 按出现顺序编号。 */
    private static Set<Integer> placeholderIndexes(String value) {
        Set<Integer> indexes = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        int sequential = 0;
        while (matcher.find()) {
            indexes.add(matcher.group(1) == null ? ++sequential : Integer.parseInt(matcher.group(1)));
        }
        return indexes;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?s");

    private static Set<String> districtKeys(JsonObject lang) {
        Set<String> keys = new TreeSet<>();
        for (String key : lang.keySet()) {
            if (key.startsWith(PREFIX)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static JsonObject readJson(String path) throws IOException {
        InputStream stream = DistrictLangGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        }
    }
}
