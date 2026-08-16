package com.miningdim.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跳蚤市场分类树构建 (market.categories / market.categoryItems 的纯逻辑核心)。服务端枚举物品注册表
 * ({@link ForgeRegistries#ITEMS}), 把每个已注册物品按其 id 路径归入一棵 {@code CategoryNode} 骨架, 供前端左栏
 * 递归渲染; 叶子改由 {@link #leavesOf} 按需分页取回, 不再随骨架一次性下发。
 *
 * 骨架与叶子分离的原因: {@link com.miningdim.webui.server.WebUiServerDispatcher#respond} 对下行 resultJson 有
 * {@code FriendlyByteBuf.MAX_STRING_LENGTH = 32767} 字符的硬闸, 成功回执同样受它约束。原版 1.20.1 物品注册表
 * 1300+ 条, 每条叶子约 100 字符, 把全部叶子塞进一条 market.categories 回执恒在 12 万字符量级 —— 这不是"负载偶尔
 * 过大", 是这条 action 在任何真服上永远超限失败。故 {@link #buildSkeleton} 只出六个固定顶层与 ores 的三个固定
 * 子分类, 每个分支节点带 leafCount (自身及全部后代的物品数, 供前端渲染"共 N 件"而不必先拉叶子), 前端按需对某个
 * 分支节点调 market.categoryItems 分页取叶子。
 *
 * 归类口径 (与前端 World-of-Kivotos_GameUI/src/lib/categories.ts 的 categoryIdsOf 同一启发式, 保证 mock 与真桥
 * 把同一物品归入同一分类): 矿物与材料 (ores) 下分 原矿/锭/宝石; 武器/弹药/装备/食物各一类; 其余落"其他"。叶子 label
 * 用翻译键 (descriptionId), 中文名由客户端 i18n 桥解析 (专用服务器不加载 lang, 同 {@link MarketAdminActions} 纪律);
 * 分支 label 是固定中文。
 *
 * {@link #leavesOf} 的分页与排序口径: 枚举注册表取属于该分类节点 (含其后代) 的全部物品, 按 itemId 字典序排 (枚举
 * 顺序无关, 结果对同一注册表内容唯一确定), 从 offset 起最多 limit 条; total 恒为该分类的真实物品数, 与 items 是
 * 否为空无关 (offset 越界时 items 为空列表但 total 仍如实回报, 前端才能算出正确的总页数)。
 *
 * 确定性: 顶层/子分类按固定声明序; 叶子按 item_id 字典序排, 故骨架与任一分类的叶子集合都对同一注册表内容唯一确定
 * (可断言)。leafCount==0 的分支整个不输出 (含其空的子分类), 避免前端渲染空类目。
 */
public final class MarketCategoryTree {

    private MarketCategoryTree() {
    }

    /** 叶子归属的 (顶层分类, 子分类) 路径; 子分类为 null 表示直接挂在顶层下 (该顶层不再分子类)。
     *  包级可见 (非 private): 同包的 {@link MarketBridgeGameTests} 直测 bucketOf 归类需读 topId/subId 访问器。 */
    record Bucket(String topId, String topLabel, String subId, String subLabel) {
    }

    /**
     * 把一个物品 id 路径归入一个桶 (顶层 + 可选子分类)。与前端 categoryIdsOf 同序判定 (锭 -> 原矿 -> 宝石 -> 武器
     * -> 弹药 -> 装备 -> 食物 -> 其他), 命中即返 (互斥单归属), 故同一物品在 mock 与真桥落同一类。
     *
     * @param itemId 完整注册 id (含命名空间, 如 minecraft:iron_ingot)
     * @return 该物品的归属桶 (恒非 null; 无命中落 other)
     */
    static Bucket bucketOf(String itemId) {
        // 取无命名空间的路径段做形态判定 (与前端一致: n = id 去命名空间), 武器/弹药用完整 id (含 tacz: 等模组前缀)。
        String n = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String low = n.toLowerCase(Locale.ROOT);
        String full = itemId.toLowerCase(Locale.ROOT);

        if (low.endsWith("_ingot") || low.equals("netherite_scrap")) {
            return new Bucket("ores", "矿物与材料", "ingot", "锭与材料");
        }
        if (low.startsWith("raw_") || low.endsWith("_ore") || low.equals("coal") || low.startsWith("quartz")) {
            return new Bucket("ores", "矿物与材料", "ore", "原矿与矿石");
        }
        if (low.equals("diamond") || low.equals("emerald") || low.equals("lapis_lazuli")
                || low.equals("redstone") || low.startsWith("amethyst")) {
            return new Bucket("ores", "矿物与材料", "gem", "宝石");
        }
        if (full.contains("gun") || full.contains("rifle") || full.contains("pistol") || full.contains("smg")
                || full.contains("shotgun") || full.contains("sniper") || full.contains("weapon")) {
            return new Bucket("weapons", "武器", null, null);
        }
        if (full.contains("ammo") || full.contains("bullet") || full.contains("cartridge")
                || full.contains("round") || full.contains("magazine")) {
            return new Bucket("ammo", "弹药", null, null);
        }
        if (low.contains("helmet") || low.contains("chestplate") || low.contains("leggings")
                || low.contains("boots") || low.contains("armor") || low.contains("plate")) {
            return new Bucket("gear", "装备", null, null);
        }
        if (low.contains("bread") || low.contains("apple") || low.contains("stew") || low.contains("cooked")
                || low.contains("cake") || low.contains("pie") || low.contains("carrot")
                || low.contains("potato") || low.contains("food")) {
            return new Bucket("food", "食物", null, null);
        }
        return new Bucket("other", "其他", null, null);
    }

    /** 固定顶层声明序 (与前端 CATEGORY_TREE 同序): 矿物与材料 -> 武器 -> 弹药 -> 装备 -> 食物 -> 其他。
     *  {@link #buildSkeleton}/{@link #isKnownCategory}/{@link #leavesOf} 共享这一份声明, 不各自手抄字面量。 */
    private static final Map<String, String> TOP_LABELS = topLabels();
    /** ores 子分类固定声明序: 原矿 -> 锭 -> 宝石。 */
    private static final Map<String, String> ORES_SUB_LABELS = oresSubLabels();

    private static Map<String, String> topLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ores", "矿物与材料");
        m.put("weapons", "武器");
        m.put("ammo", "弹药");
        m.put("gear", "装备");
        m.put("food", "食物");
        m.put("other", "其他");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> oresSubLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ore", "原矿与矿石");
        m.put("ingot", "锭与材料");
        m.put("gem", "宝石");
        return Collections.unmodifiableMap(m);
    }

    /** 树构建中的可变分类节点 (分支): 持子分类与直挂本节点的物品计数 (count), 输出时转 JsonObject。 */
    private static final class Node {
        final String id;
        final String label;
        final Map<String, Node> children = new LinkedHashMap<>();
        int count = 0;

        Node(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /**
     * 构建分类骨架 (market.categories 回执): 只出固定的六个顶层与 ores 的三个固定子分类, 每个分支节点带
     * leafCount (自身及全部后代的物品数), 不含任何叶子 —— 叶子改由 {@link #leavesOf} 按需分页取回, 理由见类注释。
     *
     * @return 顶层分类节点数组; 每个分支节点 {id,label,leafCount,children:[...]} (children 只含非空子分支)
     */
    public static JsonArray buildSkeleton() {
        Map<String, Node> top = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : TOP_LABELS.entrySet()) {
            top.put(e.getKey(), new Node(e.getKey(), e.getValue()));
        }
        Node ores = top.get("ores");
        for (Map.Entry<String, String> e : ORES_SUB_LABELS.entrySet()) {
            ores.children.put(e.getKey(), new Node(e.getKey(), e.getValue()));
        }

        for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
            Bucket b = bucketOf(key.toString());
            Node topNode = top.get(b.topId());
            Node target = b.subId() == null ? topNode : topNode.children.get(b.subId());
            target.count++;
        }

        JsonArray out = new JsonArray();
        for (Node topNode : top.values()) {
            JsonObject node = renderSkeletonNode(topNode);
            if (node != null) {
                out.add(node);
            }
        }
        return out;
    }

    /**
     * 把可变 Node 渲染成骨架 JsonObject: 递归子分类求 leafCount 之和 (加本节点直挂计数), 全空 (leafCount==0)
     * 返回 null 由调用方剔除。children 键恒保留 (前端靠 'children' in node 区分分支与叶子), 无非空子分支时为空数组。
     */
    private static JsonObject renderSkeletonNode(Node node) {
        JsonArray children = new JsonArray();
        int leafCount = node.count;
        for (Node child : node.children.values()) {
            JsonObject rendered = renderSkeletonNode(child);
            if (rendered != null) {
                leafCount += rendered.get("leafCount").getAsInt();
                children.add(rendered);
            }
        }
        if (leafCount == 0) {
            return null;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("id", node.id);
        obj.addProperty("label", node.label);
        obj.addProperty("leafCount", leafCount);
        obj.add("children", children);
        return obj;
    }

    /** 一页叶子 (market.categoryItems 回执核心): total 是该分类叶子的真实总数, 与 items 是否为空无关。 */
    public record LeafPage(List<JsonObject> items, int total) {
    }

    /**
     * 按分类节点分页取叶子。categoryId 是六个顶层 id 或三个 ores 子 id 之一 (先经 {@link #isKnownCategory} 校验);
     * 匹配 bucketOf(itemId) 的 topId 或 subId 等于 categoryId 的全部物品 (即该节点及其后代), 按 itemId 字典序排。
     *
     * @param offset 起始下标 (>= total 时 items 为空列表, total 仍如实回报)
     * @param limit  本页最多条数
     */
    public static LeafPage leavesOf(String categoryId, int offset, int limit) {
        List<JsonObject> matched = new ArrayList<>();
        for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
            String itemId = key.toString();
            Bucket b = bucketOf(itemId);
            if (!categoryId.equals(b.topId()) && !categoryId.equals(b.subId())) {
                continue;
            }
            Item item = ForgeRegistries.ITEMS.getValue(key);
            JsonObject leaf = new JsonObject();
            leaf.addProperty("id", "i_" + itemId.replace(':', '_'));
            // 叶子 label = 翻译键 (descriptionId); 中文名由客户端 i18n 桥解析 (同 admin 面板)。
            leaf.addProperty("label", item == null ? itemId : item.getDescriptionId());
            leaf.addProperty("itemId", itemId);
            matched.add(leaf);
        }
        matched.sort(Comparator.comparing(l -> l.get("itemId").getAsString()));

        int total = matched.size();
        if (offset >= total) {
            return new LeafPage(List.of(), total);
        }
        int to = Math.min(offset + limit, total);
        return new LeafPage(new ArrayList<>(matched.subList(offset, to)), total);
    }

    /** categoryId 是否是六个顶层 id 或三个 ores 子 id 之一 (与骨架/leavesOf 同一份声明, 不手抄字面量)。 */
    public static boolean isKnownCategory(String categoryId) {
        return TOP_LABELS.containsKey(categoryId) || ORES_SUB_LABELS.containsKey(categoryId);
    }
}
