package com.miningdim.market;

import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.webui.server.WebUiErrorCodes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 市场挂单标的白名单裁决 (纯静态谓词, 服务端权威; 形状仿
 * {@link com.miningdim.marriage.SharedBackpackWhitelist})。
 *
 * 唯一真源纪律: {@link MarketEngine#place} 的挂单拒绝与 {@code market.tradable} 的只读预判**共用本类的
 * {@link #judge}**, 不允许任何第二处再写一遍品质判断。只在 tradable 里判而 place 不判, 等于前端灰掉了按钮、
 * 玩家却能用命令行或自造请求挂上去 —— 那时页面显示的规则是假的, 比不做更糟。
 *
 * 当前唯一规则 (用户拍板): 塔罗牌只有最低品质 R 可以挂单, 其余 (SR/SSR/UR/闪耀) 一律禁止。产品意图是保留低品质
 * 牌的流通给新手入门渠道, 高品质必须自己合成, 不让合成玩法的价值被倒卖稀释。与既有的 ownerUUID 绑定 (倒卖来的
 * 牌打不出效果) 并存: 绑定管的是"买到手能不能用", 本规则管的是"能不能挂上去"。
 *
 * 依赖代价 (必须记一笔): market 核心交易逻辑从此对 {@link TarotQuality} 枚举产生编译期依赖。将来给塔罗加档位、
 * 改成员顺序或改最低档语义, 必须同步排查 market 包 —— 否则市场会静默按旧的"最低档"放行。
 *
 * 容器下钻 (规则的完整性前提): 只看顶层 {@code stack.getItem()} 的话, 27 张 UR 塔罗塞进一个潜影盒整包就能过关 ——
 * 规则被绕开的代价不止"买家打不出效果"(ownerUUID 绑定只管使用), 而是买来的高品质牌能直接当合成材料, 把"高品质
 * 必须自己合成"变成"买 UR 直接合闪耀"。故 {@link #judge} 对容器内容物递归判一层, 见 {@link #MAX_CONTAINER_DEPTH}。
 *
 * 不写分支的两个标的 (刻意, 非遗漏):
 *  - 青辉石: 经济层的 AZURE 是纯账本余额, 没有对应注册物品, 规则永远匹配不到真实 ItemStack;
 *  - 婚戒: 用户本轮只点名塔罗牌; 婚戒的转移限制归共享背包黑名单管, 市场侧未获授权, 不擅自加。
 */
public final class MarketTradeWhitelist {

    private MarketTradeWhitelist() {
    }

    /** 品质高于最低档 R (含闪耀) 的塔罗牌被拒。{@link Verdict#rule()} 取值, 前端据此分句。 */
    public static final String RULE_TAROT_QUALITY_ABOVE_R = "TAROT_QUALITY_ABOVE_R";

    /** 塔罗牌身份 NBT 缺失/越界 (创造模式直给的裸牌), 无法证明是 R, 故被拒。{@link Verdict#rule()} 取值。 */
    public static final String RULE_TAROT_IDENTITY_UNREADABLE = "TAROT_IDENTITY_UNREADABLE";

    /**
     * 一次裁决结果。可交易时 reasonCode/reason/rule 三者均为 null (前端据 tradable 判分支, 不靠字符串判空)。
     *
     * @param tradable   是否允许挂上市场
     * @param reasonCode 拒绝的稳定机器码 ({@link WebUiErrorCodes#ITEM_NOT_TRADABLE}); 与 place 拒绝时抛出的
     *                   errorCode 是同一个值, 故前端一条文案同时服务"灰按钮提示"与"硬提交被拒"
     * @param reason     写给玩家看的中文原因
     * @param rule       命中的规则名 (业务错误的 params.rule), 供前端把一条码分成两句话
     */
    public record Verdict(boolean tradable, String reasonCode, String reason, String rule) {
    }

    private static final Verdict ALLOWED = new Verdict(true, null, null, null);

    /**
     * 容器下钻的深度上限: 容器本体判一次 + 内容物判一次。原版拿不到"潜影盒装潜影盒"(潜影盒被禁止放进潜影盒),
     * 所以一层足以覆盖真实可达的嵌套; 定成常量而不是写死 if, 是为了让"为什么只钻一层"有个能被读到的落点。
     */
    private static final int MAX_CONTAINER_DEPTH = 1;

    /**
     * 该物品栈能否挂上市场。deny-by-default 只作用于已知受管标的: 非塔罗牌 (且容器内也无受管标的) 一律放行。
     */
    public static Verdict judge(ItemStack stack) {
        return judge(stack, MAX_CONTAINER_DEPTH);
    }

    /**
     * 单层裁决 + 有界下钻。
     *
     * 判定链顺序固定, 且第二步的守卫是第三步不抛的前提 —— {@link TarotCardItem#quality} 对缺键抛
     * IllegalStateException、对越界序号经 {@link TarotQuality#byOrdinal} 抛 IllegalArgumentException,
     * 必须先用非抛探针 {@link TarotCardItem#hasReadableCardIdentity} 过一道。
     *
     * @param remainingDepth 还允许下钻的层数; 0 表示只判本层, 不再看内容物
     */
    private static Verdict judge(ItemStack stack, int remainingDepth) {
        if (stack.getItem() instanceof TarotCardItem) {
            if (!TarotCardItem.hasReadableCardIdentity(stack)) {
                // 拒绝而不是放行: 无法证明它是 R 品质, 就不满足"只有最低品质可挂"的放行条件。
                return new Verdict(false, WebUiErrorCodes.ITEM_NOT_TRADABLE,
                        "这张塔罗牌的数据不完整, 无法上架", RULE_TAROT_IDENTITY_UNREADABLE);
            }
            // 必须是枚举常量比较: SHINY 的 tierIndex() 是 -1 而 ordinal() 是 4, 拿 tierIndex 判"最低"会把闪耀误放行。
            if (TarotCardItem.quality(stack) == TarotQuality.R) {
                return ALLOWED;
            }
            return new Verdict(false, WebUiErrorCodes.ITEM_NOT_TRADABLE,
                    "只有最低品质(R)的塔罗牌可以在市场挂单, 更高品质请自行合成", RULE_TAROT_QUALITY_ABOVE_R);
        }
        if (remainingDepth > 0) {
            Verdict contents = judgeContents(stack, remainingDepth);
            if (!contents.tradable()) {
                return contents;
            }
        }
        return ALLOWED;
    }

    /**
     * 判容器内容物 (方块实体形态的容器: 潜影盒/箱子等把内容物存在物品 NBT 的 BlockEntityTag.Items 里)。
     * 任一内容物被拒则整包被拒, 且沿用内层的 {@link Verdict#rule()} —— 前端按 rule 分句的那套文案不必为容器再加一套,
     * 只是外层 reason 说清楚被拒的是"包里的东西"。
     *
     * NBT 全用类型化读取 (getCompound/getList 带类型参数, 类型不符即返回空), 脏 NBT 走到这里必须是"当作没装东西"
     * 而不是抛 —— 本方法同时服务 market.tradable 的只读预判, 抛出去就是整块面板打不开。
     * {@link ItemStack#of} 对坏行内部已兜成 EMPTY (它自己 catch RuntimeException), 空栈跳过。
     */
    private static Verdict judgeContents(ItemStack container, int remainingDepth) {
        CompoundTag tag = container.getTag();
        if (tag == null) {
            return ALLOWED;
        }
        ListTag items = tag.getCompound("BlockEntityTag").getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            ItemStack inner = ItemStack.of(items.getCompound(i));
            if (inner.isEmpty()) {
                continue;
            }
            Verdict verdict = judge(inner, remainingDepth - 1);
            if (!verdict.tradable()) {
                return new Verdict(false, verdict.reasonCode(),
                        "这个容器里装着不能上架的物品: " + verdict.reason(), verdict.rule());
            }
        }
        return ALLOWED;
    }
}
