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
     * "获得称号 [xxx]" 聊天提示 (悬停可看说明), 不做全服广播。
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
     * tx 的校验先于其他一切检查 (包括称号定义是否存在), 没开事务的接线错误总会立刻抛出。
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
     * 回收称号; 若正在佩戴则同时卸下。返回是否真的回收了一条持有记录。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务 (应在提交之后调用)
     */
    boolean revoke(UUID player, ResourceLocation titleId);

    /**
     * 佩戴 (titleId 为 null 表示不佩戴)。服务端校验称号已拥有且定义存在; 成功后刷新聊天显示名、Tab 列表名,
     * 并把名牌称号同步给所有正在看着该玩家的客户端。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务 (应在提交之后调用)
     */
    EquipResult equip(ServerPlayer player, @Nullable ResourceLocation titleId);

    /** 持有的全部称号 id, 含定义已被删除的 (持有记录保留不删)。 */
    Set<ResourceLocation> owned(UUID player);

    /** 当前佩戴的称号 id; 定义被删除时仍返回该 id, 只是不再显示。 */
    Optional<ResourceLocation> equipped(UUID player);

    /** 当前加载的称号定义。 */
    Optional<TitleDefinition> definition(ResourceLocation titleId);

    /** 当前加载的全部称号定义, 按展示顺序 (sort 降序、id 升序)。 */
    List<TitleDefinition> definitions();

    /** 渲染好的称号徽记 {@code [称号]}; 定义缺失为空。供提示消息、面板预览使用。 */
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
}
