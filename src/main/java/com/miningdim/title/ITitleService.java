package com.miningdim.title;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 称号系统对外门面 (Title_System_DesignSpec 第六章), 由 {@link TitleServices#titleService()} 取得。
 *
 * 称号是纯外观: 只提供发放、回收、佩戴与显示查询, 不提供任何战斗或经济收益。成就、成就点商店、婚姻、活动、
 * 管理员都是调用方; 本模块不引用任何玩法模块, "某件事发生后发称号"一律由那个玩法模块调用 {@link #grant}。
 * 赞助专属称号 (第十三章) 不经发放产生, 由赞助资格加玩家自己的设置决定, 相关入口在接口末尾一组。
 *
 * 全部方法只允许在服务端主线程调用。
 *
 * <p>事务: 只有 {@link #grantInTransaction} 可以并入调用方的事务。grant / revoke / equip 自带写穿缓存、聊天提示
 * 与显示刷新, 这些都是立即生效、无法随外层回滚撤销的, 所以共享连接上正开着事务时它们直接抛
 * IllegalStateException; 调用方应在事务里用 grantInTransaction, 提交后再 notifyGranted / equip / revoke。
 */
public interface ITitleService {

    /**
     * 给在线玩家发放称号。按主键幂等: 重复发放不报错, 也不覆盖最早的来源。新发放时给玩家本人发一条
     * "获得称号 [xxx]" 聊天提示 (悬停可看说明), 不做全服广播。专属称号 id ({@code miningdim:custom/...})
     * 一律返回 {@link GrantResult#NOT_GRANTABLE}。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务 (应改用 {@link #grantInTransaction})
     */
    GrantResult grant(ServerPlayer player, ResourceLocation titleId, TitleSource source, @Nullable String sourceRef);

    /**
     * 按 UUID 发放, 供离线玩家的批量补发使用: 玩家不在线时只写库、不经过缓存; 恰好在线时等价于
     * {@link #grant(ServerPlayer, ResourceLocation, TitleSource, String)}。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务 (应改用 {@link #grantInTransaction})
     */
    GrantResult grant(UUID player, ResourceLocation titleId, TitleSource source, @Nullable String sourceRef);

    /**
     * 在调用方已开启的事务里发放 (例如成就点商店的"扣成就点 + 发称号")。本方法不提交、不回滚, 也不发聊天提示
     * —— 事务是否最终提交只有调用方知道; 提交后如需提示, 调用 {@link #notifyGranted}。
     * tx 的校验先于其他一切检查 (包括称号定义是否存在), 没开事务的接线错误总会立刻抛出; 专属称号 id 在其后
     * 一律返回 {@link GrantResult#NOT_GRANTABLE}。
     *
     * @param tx 调用方正在使用的事务连接, 必须已关闭自动提交
     * @throws IllegalArgumentException tx 为 null
     * @throws IllegalStateException    tx 处于自动提交模式
     */
    GrantResult grantInTransaction(Connection tx, UUID player, ResourceLocation titleId, TitleSource source,
                                   @Nullable String sourceRef);

    /** 给玩家本人发"获得称号"聊天提示; 供 {@link #grantInTransaction} 的调用方在事务提交后使用。 */
    void notifyGranted(ServerPlayer player, ResourceLocation titleId);

    /**
     * 回收称号; 若正在佩戴则同时卸下。返回是否真的回收了一条持有记录。专属称号不在持有表里, 传入它的 id
     * 直接返回 false、什么都不改 (处置专属称号走 {@link #resetCustomTitle} 或 {@link #revokeSponsor})。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务 (应在提交之后调用)
     */
    boolean revoke(UUID player, ResourceLocation titleId);

    /**
     * 佩戴 (titleId 为 null 表示不佩戴)。服务端校验称号已拥有且定义存在; 成功后刷新聊天显示名、Tab 列表名,
     * 并把名牌称号同步给所有正在看着该玩家的客户端。佩戴前先做一次赞助到期检查 (13.5)。别人的专属称号 id 一律
     * 返回 {@link EquipResult#NOT_OWNED}, 不查对方的记录 (不暴露对方有没有专属称号)。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务 (应在提交之后调用)
     */
    EquipResult equip(ServerPlayer player, @Nullable ResourceLocation titleId);

    /**
     * 持有的全部称号 id, 含定义已被删除的 (持有记录保留不删)。有专属称号记录且赞助资格有效时, 专属称号
     * 排在最前; 资格失效的那一刻起它就不在这里, 记录本身保留。
     */
    Set<ResourceLocation> owned(UUID player);

    /** 当前佩戴的称号 id; 定义被删除时仍返回该 id, 只是不再显示。 */
    Optional<ResourceLocation> equipped(UUID player);

    /** 称号定义: 数据包称号取当前加载的定义, 专属称号按该玩家的记录动态生成 (没有稀有度)。 */
    Optional<TitleDefinition> definition(ResourceLocation titleId);

    /** 当前加载的全部数据包称号定义, 按展示顺序 (sort 降序、id 升序); 不含按玩家生成的专属称号。 */
    List<TitleDefinition> definitions();

    /** 渲染好的称号徽记 (数据包称号为 {@code [称号]}, 专属称号原样); 定义缺失为空。供提示消息、面板预览使用。 */
    Optional<Component> badge(ResourceLocation titleId);

    /**
     * 显示路径专用: 在线玩家当前佩戴称号的前缀 {@code [称号] }。只读内存缓存、不查库;
     * 未佩戴、不在线或定义缺失时返回 null。
     */
    @Nullable
    Component displayPrefix(UUID player);

    /** 显示路径专用: 同 {@link #displayPrefix}, 但不带尾随空格, 用于头顶名牌的单独一行。 */
    @Nullable
    Component displayBadge(UUID player);

    // ---- 赞助专属称号 (Title_System_DesignSpec 第十三章) ----
    // 下列写操作与 grant / revoke / equip 一样写穿缓存并立即刷新显示、发提示, 共享连接上正开着事务时抛
    // IllegalStateException; 每次变更都写审计日志 miningdim/title/custom。

    /**
     * 发放或续期赞助资格 (13.5)。days 为 null 发放永久资格; 带天数时从"当前到期时间与现在中较晚者"往后延长,
     * 已是永久资格的不会被带天数的续期降级。在线玩家立即重新拥有其专属称号 (不会自动佩戴) 并收到提示。
     *
     * @param days   有效天数 (≥ 1); null 为永久
     * @param issuer 执行者, 记入资格行与审计日志
     * @return 写入后的资格
     */
    SponsorStatus grantSponsor(UUID player, @Nullable Integer days, String issuer);

    /**
     * 撤销赞助资格, 效果等同到期: 专属称号记录保留; 在线且正佩戴专属称号时立即卸下并提示,
     * 不在线的在下次登录时卸下。返回是否真的删掉了一条资格。
     */
    boolean revokeSponsor(UUID player, String issuer);

    /** 一名玩家的赞助与专属称号状态。 */
    CustomTitleInfo customTitleInfo(UUID player);

    /** 全部赞助资格行 (含已过期的) 的状态, 按最近发放时间升序。 */
    List<CustomTitleInfo> sponsors();

    /** 玩家能否自己提交专属称号 (配置 selfServiceEnabled, 默认关闭, 13.1)。关闭时由管理员代设置。 */
    boolean customSelfServiceEnabled();

    /**
     * 预览 (13.4): 要求资格有效, 按玩家口径完整校验 (含违禁词); 不写库, 不消耗冷却。自助提交关闭时照常可用,
     * 玩家据此把想要的效果告诉管理员。
     */
    CustomTitleResult previewCustomTitle(UUID player, CustomTitleDraft draft);

    /**
     * 玩家提交专属称号: 先做到期检查, 再依次判资格、自助提交是否开启、锁定、冷却、校验, 通过即写库并立即生效
     * (正在佩戴时三处显示立即刷新)。第一次设置不受冷却限制; 被拒时不写库、不消耗冷却。
     */
    CustomTitleResult setCustomTitle(ServerPlayer player, CustomTitleDraft draft);

    /**
     * 管理员代为设置 (13.7): 不看资格、锁定与冷却, 也不查违禁词, 但字符集、长度、颜色与亮度照常校验。
     * 既不开始也不清除玩家的修改冷却 (沿用原记录的冷却起点, 新记录不带冷却, 玩家自己的第一次设置仍然免冷却),
     * 锁定状态保持不变; 在线玩家收到提示。要禁止玩家再改, 配合 {@link #setCustomTitleLocked}。
     */
    CustomTitleResult adminSetCustomTitle(UUID player, CustomTitleDraft draft, String issuer);

    /** 清空专属称号 (连同冷却一并清除, 正在佩戴则卸下); 锁定中的记录拒绝清空, 须先解锁。 */
    CustomAdminResult resetCustomTitle(UUID player, String issuer);

    /** 锁定或解锁该玩家修改专属称号的权利; 已有的专属称号保持不变。 */
    CustomAdminResult setCustomTitleLocked(UUID player, boolean locked, String issuer);

    /** 清除修改冷却, 玩家可以立即再改一次。 */
    CustomAdminResult clearCustomTitleCooldown(UUID player, String issuer);
}
