package com.miningdim.title.store;

import com.miningdim.title.CustomTitle;
import com.miningdim.title.CustomTitleStyle;
import com.miningdim.title.SponsorStatus;
import com.miningdim.title.TitleSource;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 称号持久化边界。业务代码只经本接口访问数据库, 当前实现为 {@link SqliteTitleRepository};
 * 将来改为多子服时新增共享数据库 (如 MySQL) 的实现即可, 业务代码不改 (Title_System_DesignSpec 第四章)。
 *
 * 全部方法在服务端主线程同步调用, 不引入异步线程。
 */
public interface TitleRepository {

    /**
     * 按主键幂等插入一条持有记录; 已存在时不覆盖最早的来源。
     *
     * @return true 表示本次新插入, false 表示此前已拥有
     */
    boolean insertOwned(UUID player, ResourceLocation titleId, TitleSource source,
                        @Nullable String sourceRef, long grantedAt);

    /**
     * 与 {@link #insertOwned(UUID, ResourceLocation, TitleSource, String, long)} 相同, 但写在调用方已开启的事务
     * 连接上, 本方法不提交也不回滚 —— 提交权归调用方 (例如"扣成就点 + 发称号"同事务)。
     * 写入前先做 {@link #requireOpenTransaction} 的校验。
     */
    boolean insertOwnedInTransaction(Connection tx, UUID player, ResourceLocation titleId, TitleSource source,
                                     @Nullable String sourceRef, long grantedAt);

    /**
     * 校验调用方传入的连接确实开着尚未提交的事务。门面在做任何业务判断 (如称号定义是否存在) 之前先调它,
     * 让"调用方忘了开事务"这种接线错误总是立刻暴露, 而不是被一个看似正常的拒发结果盖过去。
     *
     * @throws IllegalArgumentException tx 为 null
     * @throws IllegalStateException    连接处于自动提交模式 (调用方并没有真的开事务)
     */
    void requireOpenTransaction(Connection tx);

    /** 删除一条持有记录; 返回是否真的删掉了一行。 */
    boolean deleteOwned(UUID player, ResourceLocation titleId);

    /** 玩家持有的全部称号 id (含定义已被删除的, 持有记录保留不删)。 */
    Set<ResourceLocation> owned(UUID player);

    /** 当前佩戴; 不佩戴为空。 */
    Optional<ResourceLocation> equipped(UUID player);

    /** 设置佩戴 (覆盖旧值)。 */
    void setEquipped(UUID player, ResourceLocation titleId);

    /** 卸下; 返回是否真的删掉了一行。 */
    boolean clearEquipped(UUID player);

    // ---- 赞助资格 (title_sponsor, Title_System_DesignSpec 13.5 / 13.6) ----

    /** 该玩家的赞助资格 (含已过期的); 从未发放或已撤销为空。 */
    Optional<SponsorStatus> sponsor(UUID player);

    /** 全部赞助资格行 (含已过期的), 按最近发放时间升序。 */
    List<SponsorStatus> sponsors();

    /** 写入或覆盖一名玩家的赞助资格 (expiresAt 为 null 表示永久)。 */
    void upsertSponsor(UUID player, String grantedBy, long grantedAt, @Nullable Long expiresAt);

    /** 删除赞助资格; 返回是否真的删掉了一行。 */
    boolean deleteSponsor(UUID player);

    // ---- 专属称号 (title_custom) ----

    /** 该玩家的专属称号记录; 未设置为空。 */
    Optional<CustomTitle> customTitle(UUID player);

    /** 写入或覆盖专属称号的文字、颜色、粗体与修改时间; 已有记录的锁定状态保持不变。 */
    void saveCustomTitle(UUID player, CustomTitleStyle style, long updatedAt);

    /** 删除专属称号记录; 返回是否真的删掉了一行。 */
    boolean deleteCustomTitle(UUID player);

    /** 设置锁定状态 (解锁时 lockedBy 为 null); 返回是否有记录被更新。 */
    boolean setCustomTitleLocked(UUID player, boolean locked, @Nullable String lockedBy);

    /** 改写修改冷却的起点; 返回是否有记录被更新。 */
    boolean setCustomTitleUpdatedAt(UUID player, long updatedAt);

    /** 在一个事务里执行; 已处于外层事务时并入外层, 不提前提交。 */
    <T> T inTransaction(Supplier<T> body);

    /** 当前是否处于尚未提交的事务中 (读到的可能是未提交数据, 调用方据此决定能否缓存读取结果)。 */
    boolean inOpenTransaction();
}
