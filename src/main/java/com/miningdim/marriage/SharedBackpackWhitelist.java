package com.miningdim.marriage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/**
 * 共享背包内容黑名单裁决 (结婚系统 spec 第四章: 杜绝把高危资产塞进双人共享容器形成定向转移/互借神装/复制面)。
 * 纯静态谓词, 服务端权威 (menu 的 {@code canPlaceItem} + 取放路径调用); 客户端 menu 只视图, 不信前端预过滤。
 *
 * 拒绝 (spec 第四章黑名单):
 *  - 高级矿物及其矿石方块: 钻石/绿宝石/下界合金锭/下界合金块/远古残骸及对应矿石方块 (定向洗矿)。信用点/青辉石是
 *    capability 余额非物品, 物理上进不来, 不在此列。
 *  - 皮肤凭证: 任何 item id 命名空间含 TACZ (tacz/cgm 等枪械皮肤凭证) 且 id 含 "skin" 的物品 (可上架皮肤 dupe
 *    会击穿皮肤市场)。本服皮肤经独立凭证物承载, 以 id 子串识别 (无硬 import TACZ, 与 compileOnly 铁律一致)。
 *  - 绑定装备: 任何带身份盖章 NBT (OwnerUUID / SpouseUUID / MarriageId) 的物品 —— 塔罗牌/结婚戒指及未来任何
 *    绑定装备 (互借神装/转移婚戒白嫖福利)。以 NBT 键识别, 不枚举具体物品类, 新增绑定物自动覆盖。
 *
 * 允许: 其余普通材料/消耗品/食物/任务道具/纪念物 (spec 第四章白名单口径: 黑名单未命中即放行)。
 *
 * 容器下钻: 只看顶层 {@code stack.getItem()} 的话, 三条黑名单都对"潜影盒"这个物品本身无效判定, 于是把钻石/绑定
 * 装备整包塞进潜影盒再放进共享背包即可绕过全部黑名单。故 {@link #isAllowed(ItemStack)} 对容器内容物递归判一层,
 * 深度上限见 {@link #MAX_CONTAINER_DEPTH}。这套下钻逻辑与 market 包
 * {@code com.miningdim.market.MarketTradeWhitelist#judgeContents} 是两份独立实现, 形状仿照后者但未抽公共工具
 * 类 —— market 包属另一分支的改动范围, 本轮不跨包收口, 收口成共用工具是后续项。
 */
public final class SharedBackpackWhitelist {

    private SharedBackpackWhitelist() {
    }

    /**
     * 容器下钻的深度上限: 容器本体判一次 + 内容物判一次。原版禁止潜影盒装潜影盒, 所以一层足以覆盖真实可达的嵌套,
     * 与 market 包 {@code MarketTradeWhitelist.MAX_CONTAINER_DEPTH} 同口径。
     */
    private static final int MAX_CONTAINER_DEPTH = 1;

    /** 身份盖章 NBT 键 (任一存在即视为绑定装备; 与 TarotCardItem/RingItem 的盖章键对齐)。 */
    private static final String NBT_OWNER_UUID = "OwnerUUID";
    private static final String NBT_SPOUSE_UUID = "SpouseUUID";
    private static final String NBT_MARRIAGE_ID = "MarriageId";

    /** 高级矿物成品/原料物品 (定向洗矿黑名单; 含锭/碎片/锭块, 原矿石方块走 {@link #BLOCKED_BLOCKS})。 */
    private static final Set<Item> BLOCKED_ITEMS = Set.of(
            Items.DIAMOND,
            Items.EMERALD,
            Items.NETHERITE_INGOT,
            Items.NETHERITE_SCRAP,
            Items.ANCIENT_DEBRIS,
            Items.NETHERITE_BLOCK,
            Items.DIAMOND_BLOCK,
            Items.EMERALD_BLOCK);

    /** 高级矿物矿石方块 (BlockItem 形态; 与 AbuseGuard 高价矿分类对齐 + 绿宝石)。 */
    private static final Set<Block> BLOCKED_BLOCKS = Set.of(
            Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.ANCIENT_DEBRIS);

    /**
     * 该物品栈是否允许放入共享背包 (true = 允许)。空栈视为允许 (取走/留空是合法操作)。
     * 命中任一黑名单维度即拒绝。
     */
    public static boolean isAllowed(ItemStack stack) {
        return isAllowed(stack, MAX_CONTAINER_DEPTH);
    }

    /**
     * 单层裁决 + 有界下钻。三条黑名单先判本层, 都不命中且还有下钻额度时再判容器内容物。
     *
     * @param remainingDepth 还允许下钻的层数; 0 表示只判本层, 不再看内容物
     */
    private static boolean isAllowed(ItemStack stack, int remainingDepth) {
        if (stack.isEmpty()) {
            return true;
        }
        if (isBoundEquipment(stack)) {
            return false;
        }
        if (isSkinCredential(stack)) {
            return false;
        }
        if (isHighValueOre(stack)) {
            return false;
        }
        if (remainingDepth <= 0) {
            return true;
        }
        return contentsAllowed(stack, remainingDepth);
    }

    /**
     * 判容器内容物 (方块实体形态的容器: 潜影盒/箱子等把内容物存在物品 NBT 的 BlockEntityTag.Items 里)。
     * 任一内容物被拒则整包被拒。
     *
     * NBT 全用类型化读取 (getCompound/getList 带类型参数, 类型不符即返回空), 脏 NBT 走到这里必须是"当作没装东西"
     * 而不是抛 —— 本谓词在 canPlaceItem 里每次拖放都会跑, 抛出去就是玩家放个物品直接崩服。
     * {@link ItemStack#of} 对坏行内部已兜成 EMPTY (它自己 catch RuntimeException), 空栈跳过。
     */
    private static boolean contentsAllowed(ItemStack container, int remainingDepth) {
        CompoundTag tag = container.getTag();
        if (tag == null) {
            return true;
        }
        ListTag items = tag.getCompound("BlockEntityTag").getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            ItemStack inner = ItemStack.of(items.getCompound(i));
            if (inner.isEmpty()) {
                continue;
            }
            if (!isAllowed(inner, remainingDepth - 1)) {
                return false;
            }
        }
        return true;
    }

    /** 带身份盖章 NBT (OwnerUUID/SpouseUUID/MarriageId) 的绑定装备 -> 拒绝 (互借神装/转移婚戒)。 */
    public static boolean isBoundEquipment(ItemStack stack) {
        if (!stack.hasTag()) {
            return false;
        }
        var tag = stack.getTag();
        return tag.hasUUID(NBT_OWNER_UUID) || tag.hasUUID(NBT_SPOUSE_UUID) || tag.contains(NBT_MARRIAGE_ID);
    }

    /** 高级矿物及其矿石方块 -> 拒绝 (定向洗矿)。 */
    public static boolean isHighValueOre(ItemStack stack) {
        Item item = stack.getItem();
        if (BLOCKED_ITEMS.contains(item)) {
            return true;
        }
        return item instanceof BlockItem blockItem && BLOCKED_BLOCKS.contains(blockItem.getBlock());
    }

    /** 皮肤凭证 (id 命名空间属枪械皮肤 mod 且 id 含 "skin") -> 拒绝 (可上架皮肤 dupe 击穿市场)。 */
    public static boolean isSkinCredential(ItemStack stack) {
        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String namespace = key.getNamespace();
        String path = key.getPath();
        boolean gunMod = namespace.equals("tacz") || namespace.equals("cgm") || namespace.equals("timeless");
        return gunMod && path.contains("skin");
    }
}
