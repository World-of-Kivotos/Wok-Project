package com.miningdim.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跳蚤市场分类树构建 (market.categories 的纯逻辑核心)。服务端枚举物品注册表 ({@link ForgeRegistries#ITEMS}),
 * 把每个已注册物品按其 id 路径归入一棵 {@code CategoryNode} 树, 下钻到带 itemId 的叶子, 供前端左栏递归渲染 + 按节点筛选。
 *
 * 归类口径 (与前端 World-of-Kivotos_GameUI/src/lib/categories.ts 的 categoryIdsOf 同一启发式, 保证 mock 与真桥
 * 把同一物品归入同一分类): 矿物与材料 (ores) 下分 原矿/锭/宝石; 武器/弹药/装备/食物各一类; 其余落"其他"。同一棵树
 * 既是分支 (分类节点无 itemId) 又有叶子 (物品节点带 itemId), 叶子 label 用翻译键 (descriptionId), 中文名由客户端
 * i18n 桥解析 (专用服务器不加载 lang, 同 {@link MarketAdminActions} 纪律); 分支 label 是固定中文。
 *
 * 确定性: 顶层/子分类按固定声明序; 叶子按 item_id 字典序排 (枚举顺序无关), 故树结构对同一注册表内容唯一确定 (可断言)。
 * 空分支 (无任何物品归入) 不输出, 避免前端渲染空类目。
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

    /** 树构建中的可变分类节点 (分支): 持子分类与叶子, 输出时转 JsonObject。叶子在 {@link #leaves} 累积后字典序排。 */
    private static final class Node {
        final String id;
        final String label;
        final Map<String, Node> children = new LinkedHashMap<>();
        final List<JsonObject> leaves = new ArrayList<>();

        Node(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /**
     * 构建完整分类树 (market.categories 回执)。枚举全注册物品 -&gt; bucketOf 归类 -&gt; 建顶层/子分类骨架 -&gt; 叶子挂入,
     * 叶子按 item_id 字典序排, 空分支剔除。返回顶层有序 JsonArray (前端直接 JSON.parse 拿到 CategoryNode[])。
     *
     * @return 顶层分类节点数组 (CategoryNode[]); 每个分支节点 {id,label,children}, 叶子 {id,label,itemId}
     */
    public static JsonArray build() {
        // 固定顶层声明序 (与前端 CATEGORY_TREE 同序): 矿物与材料 -> 武器 -> 弹药 -> 装备 -> 食物 -> 其他。
        Map<String, Node> top = new LinkedHashMap<>();
        top.put("ores", new Node("ores", "矿物与材料"));
        top.put("weapons", new Node("weapons", "武器"));
        top.put("ammo", new Node("ammo", "弹药"));
        top.put("gear", new Node("gear", "装备"));
        top.put("food", new Node("food", "食物"));
        top.put("other", new Node("other", "其他"));
        // ores 子分类固定声明序: 原矿 -> 锭 -> 宝石。
        Node ores = top.get("ores");
        ores.children.put("ore", new Node("ore", "原矿与矿石"));
        ores.children.put("ingot", new Node("ingot", "锭与材料"));
        ores.children.put("gem", new Node("gem", "宝石"));

        for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
            String itemId = key.toString();
            Bucket b = bucketOf(itemId);
            Node topNode = top.get(b.topId());
            Node target = b.subId() == null ? topNode : topNode.children.get(b.subId());

            Item item = ForgeRegistries.ITEMS.getValue(key);
            JsonObject leaf = new JsonObject();
            leaf.addProperty("id", "i_" + itemId.replace(':', '_'));
            // 叶子 label = 翻译键 (descriptionId); 中文名由客户端 i18n 桥解析 (同 admin 面板)。
            leaf.addProperty("label", item == null ? itemId : item.getDescriptionId());
            leaf.addProperty("itemId", itemId);
            target.leaves.add(leaf);
        }

        JsonArray out = new JsonArray();
        for (Node topNode : top.values()) {
            JsonObject node = renderNode(topNode);
            // 空分支 (无子分类叶子且无直挂叶子) 不输出。
            if (node != null) {
                out.add(node);
            }
        }
        return out;
    }

    /**
     * 把可变 Node 渲染成 CategoryNode JsonObject: 先排子分类的叶子 + 本节点直挂叶子 (字典序), 再递归子分类。
     * 全空 (无任何叶子、无非空子分类) 返回 null 由调用方剔除。
     */
    private static JsonObject renderNode(Node node) {
        JsonArray children = new JsonArray();
        // 子分类 (分支): 递归渲染, 非空才加。
        for (Node child : node.children.values()) {
            JsonObject rendered = renderNode(child);
            if (rendered != null) {
                children.add(rendered);
            }
        }
        // 本节点直挂叶子 (无子分类的顶层如武器/弹药): 字典序后追加。
        node.leaves.sort(Comparator.comparing(l -> l.get("itemId").getAsString()));
        for (JsonObject leaf : node.leaves) {
            children.add(leaf);
        }
        if (children.isEmpty()) {
            return null;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("id", node.id);
        obj.addProperty("label", node.label);
        obj.add("children", children);
        return obj;
    }
}
