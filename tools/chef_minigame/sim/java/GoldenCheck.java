package com.miningdim.job.chef.zhangshao;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不依赖任何库的 golden.json 对拍程序（JDK 17）。
 * 用法：javac -encoding UTF-8 -d out java/*.java && java -cp out com.miningdim.job.chef.zhangshao.GoldenCheck golden.json
 * simVersion 2：用例可带 configOverride（覆盖配置字段）和 ext.gold（服务端私下抽的瓶种类），都要原样传进去。
 * 正式工程里可改成 JUnit / GameTest，用 Gson 读同一份文件，断言逻辑照抄 checkCase。
 */
public final class GoldenCheck {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        String text = Files.readString(Path.of(args.length > 0 ? args[0] : "golden.json"), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) new Json(text).value();
        expect("simVersion", ZhangshaoKernel.SIM_VERSION, i(g.get("simVersion")));
        checkVectors(m(g.get("vectors")));
        int n = 0;
        for (Object c : l(g.get("cases"))) { checkCase(m(c)); n++; }
        if (failures > 0) { System.out.println("FAILED: " + failures + " mismatch(es)"); System.exit(1); }
        System.out.println("OK: vectors + " + n + " cases match golden.json");
    }

    static void checkVectors(Map<String, Object> v) {
        for (Object o : l(v.get("mulberry32"))) {
            Map<String, Object> e = m(o);
            ZhangshaoKernel.Mulberry32 r = new ZhangshaoKernel.Mulberry32(i(e.get("state")));
            for (Object x : l(e.get("next"))) expect("mulberry32.next", i(x), r.next());
            expect("mulberry32.stateAfter", i(e.get("stateAfter")), r.a);
        }
        Map<String, Object> rr = m(v.get("rndRange"));
        ZhangshaoKernel.Mulberry32 r = new ZhangshaoKernel.Mulberry32(i(rr.get("state")));
        for (Object o : l(rr.get("ops"))) {
            List<Object> op = l(o);
            String kind = (String) op.get(0);
            switch (kind) {
                case "rnd": expect("rnd", i(op.get(2)), r.rnd(i(op.get(1)))); break;
                case "range": expect("range", i(op.get(3)), r.range(i(op.get(1)), i(op.get(2)))); break;
                default: expect("next", i(op.get(1)), r.next());
            }
        }
        for (Object o : l(v.get("fmix32"))) { List<Object> p = l(o); expect("fmix32", i(p.get(1)), ZhangshaoKernel.fmix32(i(p.get(0)))); }
        for (Object o : l(v.get("streams"))) {
            Map<String, Object> e = m(o);
            int seed = i(e.get("seed"));
            expect("stream T", i(e.get("rT")), ZhangshaoKernel.fmix32(seed ^ ZhangshaoKernel.SALT_T));
            expect("stream B", i(e.get("rB")), ZhangshaoKernel.fmix32(seed ^ ZhangshaoKernel.SALT_B));
            expect("stream S", i(e.get("rS")), ZhangshaoKernel.fmix32(seed ^ ZhangshaoKernel.SALT_S));
        }
        Map<String, Object> f = m(v.get("fnv1a"));
        expect("fnv1a", (String) f.get("hash"), ZhangshaoKernel.hex32(ZhangshaoKernel.fnv1a(ints(l(f.get("ints"))))));
    }

    static void checkCase(Map<String, Object> c) {
        String name = (String) c.get("name");
        int seed = i(c.get("seed"));
        int[] params = ints(l(c.get("params")));
        // 1 参数块构造（服务端）
        Map<String, Object> cfg = m(c.get("config"));
        ZhangshaoConfig.Options o = new ZhangshaoConfig.Options();
        o.station = i(cfg.get("station")); o.star = i(cfg.get("star")); o.level = i(cfg.get("chefLevel"));
        if (cfg.containsKey("motion")) o.motion = i(cfg.get("motion"));
        if (cfg.containsKey("mastery")) o.mastery = i(cfg.get("mastery"));
        if (cfg.containsKey("batch")) o.batch = i(cfg.get("batch"));
        if (cfg.containsKey("flips")) o.flips = i(cfg.get("flips"));
        if (cfg.containsKey("beats")) o.beats = i(cfg.get("beats"));
        if (cfg.containsKey("drink")) o.drink = Boolean.TRUE.equals(cfg.get("drink"));
        if (cfg.containsKey("goldPm")) o.goldPm = i(cfg.get("goldPm"));
        if (cfg.containsKey("normalPm")) o.normalPm = i(cfg.get("normalPm"));
        ZhangshaoConfig zc = new ZhangshaoConfig();
        if (c.containsKey("configOverride")) applyOverride(zc, m(c.get("configOverride")));
        int[] built = zc.buildParams(o);
        for (int k = 0; k < params.length; k++) expect(name + " params[" + k + "]", params[k], built[k]);
        ZhangshaoKernel.Params p = new ZhangshaoKernel.Params(params);
        expect(name + " paramsHash", (String) c.get("paramsHash"), ZhangshaoKernel.hex32(p.hash()));
        // 2 开局
        expect(name + " initHash", (String) c.get("initHash"), ZhangshaoKernel.hex32(ZhangshaoKernel.hash(ZhangshaoKernel.init(p, seed))));
        // 3 回放
        List<Integer> in = new ArrayList<>();
        for (Object run : l(c.get("inputsRle"))) { List<Object> rl = l(run); for (int k = 0; k < i(rl.get(0)); k++) in.add(i(rl.get(1))); }
        expect(name + " inputCount", i(c.get("inputCount")), in.size());
        int[] inputs = in.stream().mapToInt(Integer::intValue).toArray();
        ZhangshaoKernel.Ext ext = new ZhangshaoKernel.Ext();
        if (c.containsKey("ext")) { Map<String, Object> e = m(c.get("ext")); if (e.containsKey("gold")) ext.gold = i(e.get("gold")); }
        ZhangshaoKernel.Trace tr = ZhangshaoKernel.replay(p, seed, inputs, ext);
        List<Object> hs = l(c.get("hashes"));
        expect(name + " hashes.size", hs.size(), tr.hashes.size());
        for (int k = 0; k < Math.min(hs.size(), tr.hashes.size()); k++) expect(name + " hash@" + (k + 1) * 20, (String) hs.get(k), tr.hashes.get(k));
        expect(name + " finalHash", (String) c.get("finalHash"), tr.finalHash);
        expect(name + " consumed", inputs.length, tr.consumed);
        // 4 结算：逐键
        Map<String, Object> want = m(c.get("result"));
        Map<String, Object> got = tr.result.toMap();
        expect(name + " result keys", String.join(",", want.keySet()), String.join(",", got.keySet()));
        for (Map.Entry<String, Object> e : want.entrySet()) {
            Object gv = got.get(e.getKey());
            Object wv = e.getValue();
            String ws, gs;
            if (wv instanceof List) { ws = ints(l(wv)).length + ":" + java.util.Arrays.toString(ints(l(wv))); gs = ((int[]) gv).length + ":" + java.util.Arrays.toString((int[]) gv); }
            else if (wv instanceof String) { ws = (String) wv; gs = String.valueOf(gv); }
            else { ws = String.valueOf(i(wv)); gs = String.valueOf(gv); }
            expect(name + " result." + e.getKey(), ws, gs);
        }
        System.out.println("  " + name + "  q=" + tr.result.quality + " end=" + tr.result.end + " hash=" + tr.finalHash);
    }

    /** golden 用例的 configOverride：按字段名覆盖 ZhangshaoConfig（int 或 int[]）。正式工程里对应「服务器改了配置」。 */
    static void applyOverride(ZhangshaoConfig zc, Map<String, Object> ov) {
        for (Map.Entry<String, Object> e : ov.entrySet()) {
            try {
                java.lang.reflect.Field f = ZhangshaoConfig.class.getField(e.getKey());
                if (e.getValue() instanceof List) f.set(zc, ints(l(e.getValue())));
                else f.setInt(zc, i(e.getValue()));
            } catch (ReflectiveOperationException ex) { throw new IllegalArgumentException("configOverride 字段 " + e.getKey(), ex); }
        }
    }

    // ---- 小工具 ----
    static void expect(String what, Object want, Object got) {
        if (!want.equals(got)) { failures++; if (failures <= 30) System.out.println("MISMATCH " + what + ": want " + want + ", got " + got); }
    }
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Object> l(Object o) { return (List<Object>) o; }
    static int i(Object o) { return Math.toIntExact((Long) o); }
    static int[] ints(List<Object> a) { int[] r = new int[a.size()]; for (int k = 0; k < r.length; k++) r[k] = i(a.get(k)); return r; }

    /** 最小 JSON 解析器（对象 → LinkedHashMap，数组 → ArrayList，整数 → Long）。 */
    static final class Json {
        private final String s; private int p = 0;
        Json(String s) { this.s = s; }
        Object value() {
            ws();
            char c = s.charAt(p);
            if (c == '{') { p++; Map<String, Object> o = new LinkedHashMap<>(); ws(); if (s.charAt(p) == '}') { p++; return o; }
                while (true) { ws(); String k = str(); ws(); p++; o.put(k, value()); ws(); if (s.charAt(p++) == '}') return o; } }
            if (c == '[') { p++; List<Object> a = new ArrayList<>(); ws(); if (s.charAt(p) == ']') { p++; return a; }
                while (true) { a.add(value()); ws(); if (s.charAt(p++) == ']') return a; } }
            if (c == '"') return str();
            if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
            if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
            if (s.startsWith("null", p)) { p += 4; return null; }
            int st = p; while (p < s.length() && "-+0123456789".indexOf(s.charAt(p)) >= 0) p++;
            return Long.parseLong(s.substring(st, p));
        }
        private String str() {
            StringBuilder b = new StringBuilder(); p++;
            while (true) {
                char c = s.charAt(p++);
                if (c == '"') return b.toString();
                if (c == '\\') { char e = s.charAt(p++); if (e == 'u') { b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; } else b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e); }
                else b.append(c);
            }
        }
        private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
    }
}
