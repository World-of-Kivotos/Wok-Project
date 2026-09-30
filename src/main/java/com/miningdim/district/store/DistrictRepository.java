package com.miningdim.district.store;

import com.miningdim.district.core.Academy;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.NoticeRecord;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.core.SeenPlayer;
import com.miningdim.district.core.TombstoneRecord;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 自管区仓储 (设计文档第五章), 按聚合分组。唯一的生产实现是 {@link SqliteDistrictRepository}。
 *
 * 所有写方法都不自己开事务: 由服务层用 {@link #inTransaction} 把一个动作的全部写入裹成一个事务。失败一律抛
 * {@link DistrictStoreException}。读取遇到 UUID 解析不出的畸形行时记 WARN 后跳过, 不让整次读取失败。
 */
public interface DistrictRepository {

    /** 一个学院名单按生效状态的计数 (district.state syncIssues)。 */
    record SyncCounts(int pending, int failed) {
    }

    // ---- 事务 ----

    /** 在事务中执行; 已在外层事务中时并入 (StoreTx 语义)。 */
    <T> T inTransaction(Supplier<T> body);

    /** 登记提交后执行的动作; 不在事务中时立即执行 (StoreTx.afterCommit)。 */
    void afterCommit(Runnable action);

    /** 连接当前是否在事务中。 */
    boolean inOpenTransaction();

    /** 仓储所在的连接; 只供测试夹具断言"和账本是同一条连接"与写 StoreMeta 标记。 */
    Connection connection();

    /**
     * 登记"区与地块的几何变了"的监听者 (设计文档 22.3, 只有一个, 后登记的替换先前的)。改几何的六个写方法 (insertDistrict、
     * setBounds、unbind、insertPlot、setPlotBounds、deletePlot) 每次执行成功都经 {@link #afterCommit} 登记一次:
     * 提交之后调它 (不在事务里时立即调), 回滚则丢弃。同一事务里的多次登记只触发一次。
     */
    void onLayoutChanged(Runnable listener);

    // ---- 学院 ----

    /** 按顺序 INSERT OR IGNORE, 不覆盖已有行; 返回新插入的行数。 */
    int ensureAcademies(List<Academy> academies, long at);

    List<Academy> academies();

    Optional<Academy> academy(String academyId);

    // ---- 自管区 ----

    /** 新建一个自管区。行里带区务长时抛 IllegalArgumentException (任命只走 {@link #setWarden}, 那里有触发器把关)。 */
    void insertDistrict(DistrictRecord district);

    /** 全部未解绑的自管区, 按学院 sort_order。 */
    List<DistrictRecord> liveDistricts();

    Optional<DistrictRecord> liveDistrict(String districtId);

    /** 含已解绑的。 */
    Optional<DistrictRecord> anyDistrict(String districtId);

    Optional<DistrictRecord> liveDistrictOfAcademy(String academyId);

    /** 某学院的全部绑定 (含已解绑), 按创建时间。 */
    List<DistrictRecord> districtsOfAcademy(String academyId);

    /** 已解绑的自管区, 解绑时间新的在前。 */
    List<DistrictRecord> archivedDistricts(int limit);

    /** 设置或清空 (uuid 与 name 都为 null) 区务长。 */
    void setWarden(String districtId, @Nullable UUID uuid, @Nullable String name);

    void setPricing(String districtId, int unitPrice, int minSide, int maxSide);

    void setPurchaseOpen(String districtId, boolean open);

    void setBounds(String districtId, DistrictBounds bounds);

    void setRules(String districtId, List<String> rules);

    void setDistrictClaimId(String districtId, @Nullable UUID claimId);

    /** 取下一块地的编号并把计数 +1 (编号永不复用); 必须在调用方的事务里。 */
    int takeNextPlotNo(String districtId);

    /** 解绑: 写入解绑时间、操作人与当时的人数、地块数, 并清空区务长。 */
    void unbind(String districtId, long at, String byName, int memberCount, int plotCount);

    void markNeedsReconcile(String districtId);

    /** 对账整轮对完、且没有修不了的差异时清掉标记 (设计文档 20.4)。 */
    void clearNeedsReconcile(String districtId);

    // ---- 学院名单 ----

    /** 返回自增 id (入学顺序)。 */
    long insertMember(MemberRecord member);

    Optional<MemberRecord> memberByUuid(UUID uuid);

    Optional<MemberRecord> memberByNameLower(String nameLower);

    /** 按入学顺序。 */
    List<MemberRecord> membersOf(String academyId);

    boolean deleteMember(UUID uuid);

    void setMemberSync(UUID uuid, ResidentSyncStatus status, @Nullable String error);

    /** 首次登录换键: 名单行的 UUID 与名字改成登录者的。 */
    void rekeyMember(long memberId, UUID newUuid, String newName);

    int countMembers(String academyId);

    SyncCounts countMembersBySync(String academyId);

    // ---- 公共区域开关 ----

    PermissionCells districtCells(String districtId);

    void setDistrictCell(String districtId, String permissionId, String audience, boolean enabled);

    /** 把目录里的每一格按默认值 INSERT OR IGNORE; 返回新插入的格数。 */
    int insertDistrictDefaults(String districtId);

    // ---- 地块 ----

    /** 新地块必须空置 (库上有触发器)。 */
    void insertPlot(PlotRecord plot);

    Optional<PlotRecord> plot(String plotId);

    /** 按编号。 */
    List<PlotRecord> plotsOf(String districtId);

    /** 某人现在拥有的地块 (全库最多一块, 含已解绑自管区的地块)。 */
    Optional<PlotRecord> plotOwnedBy(UUID owner);

    /** 以某人为原户主、冻结中的地块。 */
    List<PlotRecord> frozenPlotsOf(UUID formerOwner);

    /** 条件 UPDATE: 只有当前无户主且未冻结时才过户; 返回是否恰好更新了 1 行 (先到先得的第二道保险)。 */
    boolean assignOwner(String plotId, UUID owner, String ownerName);

    /** 冻结: 户主移到 frozen_*, 朋友与三列不动。 */
    void freeze(String plotId, UUID formerOwner, String formerName, long at);

    /** 解冻: 原户主回到户主字段, 冻结信息清空。 */
    void unfreeze(String plotId, UUID owner, String ownerName);

    /** 收回: 任期 +1, 户主与冻结信息一并清空 (朋友与三列由调用方另行清空与重置)。 */
    void vacate(String plotId);

    /** 只清空户主, 不动任期 (把原户主移出已解绑学院时用)。 */
    void releaseOwner(String plotId);

    /** 首次登录换键时, 把以旧 UUID 为户主的地块改到新 UUID。 */
    void rekeyPlotOwner(UUID oldUuid, UUID newUuid, String newName);

    void setPlotBounds(String plotId, PlotArea area);

    void setPlotSync(String plotId, PlotSyncStatus status, @Nullable String error);

    void setPlotClaimId(String plotId, @Nullable UUID claimId);

    /** 删除地块行, 三列与朋友随之级联删除; 地块记录不删。 */
    void deletePlot(String plotId);

    /** 未解绑自管区里冻结时刻不晚于给定值的地块 (districtId 为 null 时全服), 按冻结时刻。 */
    List<PlotRecord> expiredFrozenPlots(long frozenAtOrBefore, @Nullable String districtId);

    // ---- 地块开关 ----

    PermissionCells plotCells(String plotId);

    void setPlotCell(String plotId, String permissionId, String audience, boolean enabled);

    /** 三列整张写回地块默认值。 */
    void resetPlotCells(String plotId);

    /** 把目录里每一个 member 项的三列按默认值 INSERT OR IGNORE; 返回新插入的格数。 */
    int insertPlotDefaults(String plotId);

    /** 开服回填: 每个未解绑自管区的公共区域开关与每块地的三列, 按默认值补齐缺格; 返回补了几格。 */
    int backfillCells();

    // ---- 朋友 ----

    /** 按存储顺序。 */
    List<FriendRecord> friendsOf(String plotId);

    /** 某个自管区全部地块的朋友行, 按地块编号再按存储顺序。 */
    List<FriendRecord> friendsInDistrict(String districtId);

    /**
     * 某人在全部地块上的朋友行: UUID 相同的行, 加上小写名相同、且还只有名字 (待生效) 的行。已生效的行带着真 UUID,
     * 名字相同而 UUID 不同的是另一个人, 不算。
     */
    List<FriendRecord> friendshipsOf(UUID player, String nameLower);

    /** 某人待生效的朋友行 (UUID 或小写名匹配)。 */
    List<FriendRecord> pendingFriendsFor(UUID player, String nameLower);

    /** 返回自增 id。 */
    long insertFriend(FriendRecord friend);

    void deleteFriend(long friendId);

    void deleteFriends(String plotId);

    /** 暂停 (at 非 null) 或恢复 (null)。 */
    void setFriendSuspended(long friendId, @Nullable Long at);

    /** 首次登录: 朋友行换键到登录者并改为已生效。 */
    void activateFriend(long friendId, UUID uuid, String name);

    // ---- 墓碑 ----

    void insertTombstone(TombstoneRecord tombstone);

    /** 删除时间新的在前。 */
    List<TombstoneRecord> tombstones(String districtId, int limit);

    // ---- 记录 ----

    /** 返回自增 id。 */
    long insertDistrictLog(DistrictLogEntry entry);

    /** 新的在前 (at DESC, id DESC)。 */
    List<DistrictLogEntry> districtLog(String districtId, int limit);

    /** 返回自增 id。 */
    long insertPlotLog(PlotLogEntry entry);

    /** 任期在 [minTenure, maxTenure] 内的地块记录, 任期新的在前, 同一任期内新的在前。 */
    List<PlotLogEntry> plotLog(String plotId, int minTenure, int maxTenure, int limit);

    // ---- 见过的玩家 ----

    /** 登录: 已有这一行就更新名字与最后在线时间, 没有就插入 (source = login)。 */
    void upsertSeenOnLogin(UUID uuid, String name, long at);

    void touchLastSeen(UUID uuid, long at);

    Optional<SeenPlayer> seenByUuid(UUID uuid);

    /** 有多行时取最后在线时间最新的那一行。 */
    Optional<SeenPlayer> seenByNameLower(String nameLower);

    /** INSERT OR IGNORE 一行 source = backfill; 返回是否插入。 */
    boolean insertSeenBackfill(UUID uuid, String name, long at);

    // ---- 通知队列 (设计文档 22.11) ----

    /** 返回自增 id。行的 id 被忽略。 */
    long insertNotice(NoticeRecord notice);

    /** 某人的全部通知, 按 id 升序 (= 发生顺序)。 */
    List<NoticeRecord> noticesFor(UUID recipient);

    /** 按 id 删除 (投递之后); 不存在的 id 忽略。返回删了几行。 */
    int deleteNotices(Collection<Long> ids);

    int countNotices(UUID recipient);

    /**
     * 只留某人 keep 条, 其余删掉; 返回删了几行。先删 kind 在 expendableKinds 里的 (最旧的先删), 不够再删别的 (最旧的先删):
     * 谁都能触发的朋友通知不能挤掉冻结、移出、收回这些要紧的 (设计文档 22.19)。
     */
    int deleteOldestNotices(UUID recipient, int keep, Collection<String> expendableKinds);

    /** 删掉 created_at 早于给定时刻的行 (保留期); 返回删了几行。 */
    int pruneNoticesBefore(long at);

    /** 首次登录换键: 收件人是旧 UUID 的行改到新 UUID; 返回改了几行。 */
    int rekeyNotices(UUID oldUuid, UUID newUuid);
}
