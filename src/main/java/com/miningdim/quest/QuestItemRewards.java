package com.miningdim.quest;

import com.miningdim.enchant.ModEnchantments;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务的物品奖励: 每次领奖必掉一份材料, 外加一次独立的附魔书掷骰。
 *
 * <b>为什么给矿和作物而不是只给钱</b> (主控 2026-08-17): 小量的矿与作物本质就是"换个形式给钱", 但开出实物
 * 与数字变大是完全不同的手感。而且它其实比等值现金<b>更保守</b> —— 任务的信用点不过衰减主闸直接到账, 而
 * 玩家把矿卖给系统走的是 {@code settleOreSale}, 要过主闸; 同等价值下给矿的实际入账反而更低。
 *
 * <b>只给锭, 不给矿石方块。</b> 可放置的矿石方块会让"挖掘某某矿石 xN"的任务被"放下去再挖掉"直接完成, 那是
 * 奖励帮玩家完成任务的循环。
 *
 * <b>农作物只给原版的, 不给农夫的 mod 小麦。</b> 原版作物的用途是吃与繁殖, 几乎不进经济, 还顺带给厨师送
 * 材料; 而 mod 小麦是农夫唯一的变现出口, 任务一发就是直接砸他的市场。
 *
 * 档位: 日常/特殊最高到铁, 周常/隐藏最高到钻石。
 */
public final class QuestItemRewards {

    /**
     * 一条材料掉落。
     *
     * @param minCount 最少个数 (含)
     * @param maxCount 最多个数 (含); 与 min 相等即定量
     * @param weight   权重 (相对值, 不必归一)
     */
    public record Drop(Item item, int minCount, int maxCount, int weight) {

        public Drop {
            if (item == null) {
                throw new IllegalArgumentException("drop item must not be null");
            }
            if (minCount < 1 || maxCount < minCount) {
                throw new IllegalArgumentException("invalid drop count range for " + item);
            }
            if (weight < 1) {
                throw new IllegalArgumentException("drop weight must be >= 1 for " + item);
            }
        }
    }

    /** 一条附魔书掉落 (附魔 + 等级 + 权重)。 */
    public record BookDrop(Enchantment enchantment, int level, int weight) {

        public BookDrop {
            if (enchantment == null) {
                throw new IllegalArgumentException("book enchantment must not be null");
            }
            if (level < 1 || weight < 1) {
                throw new IllegalArgumentException("invalid book drop for " + enchantment);
            }
        }
    }

    /** 日常/特殊档: 最高到铁。装备一律全新 (主控 2026-08-17: 不掉半损的)。 */
    private static final List<Drop> DAILY_POOL = List.of(
            new Drop(Items.COAL, 8, 16, 12),
            new Drop(Items.COPPER_INGOT, 6, 12, 12),
            new Drop(Items.IRON_INGOT, 4, 8, 10),
            new Drop(Items.WHEAT, 12, 24, 10),
            new Drop(Items.CARROT, 12, 24, 10),
            new Drop(Items.POTATO, 12, 24, 10),
            new Drop(Items.BEETROOT, 8, 16, 8),
            new Drop(Items.PUMPKIN, 4, 8, 6),
            new Drop(Items.MELON_SLICE, 8, 16, 6),
            new Drop(Items.IRON_PICKAXE, 1, 1, 4),
            new Drop(Items.IRON_SWORD, 1, 1, 4),
            new Drop(Items.IRON_SHOVEL, 1, 1, 3),
            new Drop(Items.IRON_HELMET, 1, 1, 3),
            new Drop(Items.IRON_CHESTPLATE, 1, 1, 2),
            new Drop(Items.IRON_LEGGINGS, 1, 1, 2),
            new Drop(Items.IRON_BOOTS, 1, 1, 3));

    /** 周常/隐藏档: 最高到钻石 (不含下界合金 —— 那是玩家自己该去挣的终局装备)。 */
    private static final List<Drop> WEEKLY_POOL = List.of(
            new Drop(Items.GOLD_INGOT, 8, 16, 12),
            new Drop(Items.REDSTONE, 16, 32, 12),
            new Drop(Items.LAPIS_LAZULI, 12, 24, 10),
            new Drop(Items.EMERALD, 4, 8, 8),
            new Drop(Items.DIAMOND, 2, 5, 6),
            new Drop(Items.NETHER_WART, 8, 16, 8),
            new Drop(Items.COCOA_BEANS, 12, 24, 8),
            new Drop(Items.SWEET_BERRIES, 12, 24, 8),
            new Drop(Items.GLISTERING_MELON_SLICE, 4, 8, 6),
            new Drop(Items.DIAMOND_PICKAXE, 1, 1, 4),
            new Drop(Items.DIAMOND_SWORD, 1, 1, 4),
            new Drop(Items.DIAMOND_SHOVEL, 1, 1, 3),
            new Drop(Items.DIAMOND_HELMET, 1, 1, 3),
            new Drop(Items.DIAMOND_CHESTPLATE, 1, 1, 2),
            new Drop(Items.DIAMOND_LEGGINGS, 1, 1, 2),
            new Drop(Items.DIAMOND_BOOTS, 1, 1, 3));

    /**
     * 附魔书池: 只留真正有用的, 剔掉废票 (主控 2026-08-17)。
     *
     * 剔掉的整类: 弓弩三件套 (力量/多重射击/快速装填等 —— 本服主战武器是 TaCZ 枪械, 开出来纯粹是被耍)、
     * 三叉戟系 (激流/忠诚/穿刺 —— 玩法里没有三叉戟)、钓鱼系 (空军职业尚未落地)、两条诅咒 (绑定/消失)、
     * 以及节肢杀手/亡灵杀手/击退/火焰附加这些鸡肋或反效果的。
     *
     * <b>两条经济乘数单列最低权重</b>: 时运与抢夺不是便利, 它们直接放大产出 —— 时运抬高挖矿这条最大 faucet,
     * 抢夺抬高怪物掉落。给它们和"效率"一样的权重等于用抽奖发经济倍率, 故压到常规档的一半以下。
     *
     * <b>头奖档两张等权</b>: 经济修补与金钱修补同权同档 (主控指定的恶趣味 —— 终于开出一本, 是哪张全看脸)。
     */
    private static volatile List<BookDrop> bookPoolCache;

    /**
     * 惰性构建而非静态常量: 表里含 {@code ModEnchantments.MONEY_MENDING.get()}, 而 {@link
     * net.minecraftforge.registries.RegistryObject#get()} 在注册表填充之前调用会抛。做成静态 final 字段的话,
     * 任何一次过早的类加载 (比如某个诊断命令或将来的图鉴页) 都会把它变成一次启动期崩溃, 而崩因看起来会像是
     * "任务系统的问题"。
     */
    private static List<BookDrop> buildBookPool() {
        return List.of(
            // 头奖档 (等权对半开)
            new BookDrop(Enchantments.MENDING, 1, 15),
            new BookDrop(ModEnchantments.MONEY_MENDING.get(), 1, 15),
            // 经济乘数档 (刻意压到最低)
            new BookDrop(Enchantments.BLOCK_FORTUNE, 3, 3),
            new BookDrop(Enchantments.MOB_LOOTING, 3, 3),
            // 常规实用档
            new BookDrop(Enchantments.BLOCK_EFFICIENCY, 4, 6),
            new BookDrop(Enchantments.UNBREAKING, 3, 6),
            new BookDrop(Enchantments.SILK_TOUCH, 1, 6),
            new BookDrop(Enchantments.ALL_DAMAGE_PROTECTION, 4, 6),
            new BookDrop(Enchantments.FALL_PROTECTION, 4, 6),
            new BookDrop(Enchantments.BLAST_PROTECTION, 4, 6),
            new BookDrop(Enchantments.FIRE_PROTECTION, 4, 6),
            new BookDrop(Enchantments.PROJECTILE_PROTECTION, 4, 6),
            new BookDrop(Enchantments.AQUA_AFFINITY, 1, 6),
            new BookDrop(Enchantments.RESPIRATION, 3, 6),
            new BookDrop(Enchantments.DEPTH_STRIDER, 3, 6),
            new BookDrop(Enchantments.SHARPNESS, 4, 6),
            new BookDrop(Enchantments.THORNS, 3, 6),
            new BookDrop(Enchantments.SWIFT_SNEAK, 3, 6));
    }

    private QuestItemRewards() {
    }

    /** 某来源用哪张材料表 (特殊跟日常同档, 隐藏跟周常同档)。 */
    public static List<Drop> pool(QuestSource source) {
        return switch (source) {
            case DAILY, SPECIAL -> DAILY_POOL;
            case WEEKLY, HIDDEN -> WEEKLY_POOL;
        };
    }

    /** 某来源的附魔书掉率。 */
    public static double bookChance(QuestSource source) {
        return switch (source) {
            case DAILY, SPECIAL -> QuestConfig.DAILY_BOOK_CHANCE.get();
            case WEEKLY, HIDDEN -> QuestConfig.WEEKLY_BOOK_CHANCE.get();
        };
    }

    /** 附魔书池 (只读; 供测试与将来的图鉴展示)。首次调用时构建, 见 {@link #buildBookPool}。 */
    public static List<BookDrop> bookPool() {
        List<BookDrop> cached = bookPoolCache;
        if (cached == null) {
            cached = buildBookPool();
            bookPoolCache = cached;
        }
        return cached;
    }

    /**
     * 掷一次领奖掉落: 必得一份材料, 外加一次独立的附魔书掷骰。
     *
     * 材料与书<b>互不占位</b>: 书走自己的概率, 中了就是额外多一件。合成一张表会让"今天开出附魔书所以没拿到
     * 铁"成为可能, 那是白白把好运变成憋屈。
     */
    public static List<ItemStack> roll(QuestSource source, RandomSource random) {
        List<ItemStack> out = new ArrayList<>(2);
        out.add(rollMaterial(source, random));
        ItemStack book = rollBook(source, random);
        if (!book.isEmpty()) {
            out.add(book);
        }
        return out;
    }

    /** 按权重抽一份材料 (装备类恒为全新: 新建的 ItemStack 损伤本就是 0, 此处不做任何做旧)。 */
    public static ItemStack rollMaterial(QuestSource source, RandomSource random) {
        List<Drop> drops = pool(source);
        int totalWeight = 0;
        for (Drop drop : drops) {
            totalWeight += drop.weight();
        }
        int roll = random.nextInt(totalWeight);
        for (Drop drop : drops) {
            roll -= drop.weight();
            if (roll < 0) {
                int span = drop.maxCount() - drop.minCount() + 1;
                return new ItemStack(drop.item(), drop.minCount() + random.nextInt(span));
            }
        }
        // 权重累加与逐条相减用的是同一份表, 走到这里说明表在两次遍历之间被改过 —— 让它痛。
        throw new IllegalStateException("weighted material roll fell through for source " + source);
    }

    /**
     * 掷附魔书。未中返回空栈。
     *
     * @return 附魔书 (单一附魔), 或 {@link ItemStack#EMPTY}
     */
    public static ItemStack rollBook(QuestSource source, RandomSource random) {
        if (random.nextDouble() >= bookChance(source)) {
            return ItemStack.EMPTY;
        }
        List<BookDrop> books = bookPool();
        int totalWeight = 0;
        for (BookDrop book : books) {
            totalWeight += book.weight();
        }
        int roll = random.nextInt(totalWeight);
        for (BookDrop book : books) {
            roll -= book.weight();
            if (roll < 0) {
                return EnchantedBookItem.createForEnchantment(
                        new EnchantmentInstance(book.enchantment(), book.level()));
            }
        }
        throw new IllegalStateException("weighted book roll fell through");
    }
}
