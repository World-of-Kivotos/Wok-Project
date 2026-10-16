package com.miningdim.job.farmer.granary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 储粮囤的开放名单 (Spoilage_Granary_DesignSpec「合建: 入册与权限」「谁管什么」)。
 *
 * 每个囤**单独**设: 默认私有 (名单为空), 囤主可以开放给最多 {@link #MAX_TRUSTEES} 个人, 每人选
 * 「可存可取」或「只存」—— 只存就是原来的托管人。房主也不例外: 名单之外的人 (包括房主) 既存不进去
 * 也拿不出来 (反漏洞 #68「房主打不开、拆不了别人的囤」)。
 *
 * 这一片给的是**模型与判定** (纯函数, 不读世界) 加两条接线: 放进新囤时套用玩家的默认名单
 * ({@link GranarySavedData} 的 trustDefaults), 以及存入那一条路上的放行判定。取用要等 27 格囤界面
 * (囤按规格不对外开放物品接口, 界面做出来之前没有取的口子)。
 */
public final class GranaryAccess {

    /** 一个囤最多开放给几个人。 */
    public static final int MAX_TRUSTEES = 16;

    /** 名单里那个人的权限。 */
    public enum Permission {
        /** 只存 (原来的托管人): 能存进去, 拿不出来。 */
        STORE_ONLY("granary.access.store_only"),
        /** 可存可取 (规格里的「借囤」)。 */
        STORE_AND_TAKE("granary.access.store_take");

        private final String translationKey;

        Permission(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    /** 名单条目: 谁 + 什么权限。 */
    public record Trustee(UUID player, Permission permission) {
    }

    /** 改名单的结果码 (命令回执按它选文案)。 */
    public enum Status {
        /** 新加进去了。 */
        ADDED,
        /** 换了他的权限。 */
        UPDATED,
        /** 是囤主本人 —— 囤主不用进名单, 他本来就能存取。 */
        IS_OWNER,
        /** 名单满了。 */
        FULL
    }

    private GranaryAccess() {
    }

    // ============================================================
    // 判定
    // ============================================================

    /** 能不能往这个囤里存 (囤主本人或名单上的任何人都行)。 */
    public static boolean mayStore(UUID owner, List<Trustee> list, UUID player) {
        if (player == null) {
            return false;
        }
        if (player.equals(owner)) {
            return true;
        }
        return permissionOf(list, player) != null;
    }

    /** 能不能从这个囤里取 (囤主本人或「可存可取」的人; 「只存」的拿不出来)。 */
    public static boolean mayTake(UUID owner, List<Trustee> list, UUID player) {
        if (player == null) {
            return false;
        }
        if (player.equals(owner)) {
            return true;
        }
        return permissionOf(list, player) == Permission.STORE_AND_TAKE;
    }

    /** 这个人在名单上的权限 (不在名单上返回 null)。 */
    public static Permission permissionOf(List<Trustee> list, UUID player) {
        for (Trustee trustee : list) {
            if (trustee.player().equals(player)) {
                return trustee.permission();
            }
        }
        return null;
    }

    /** 是不是私有囤 (名单为空)。 */
    public static boolean isPrivate(List<Trustee> list) {
        return list.isEmpty();
    }

    /** 名单上「可存可取」的人数 (命令回执与盘点用)。 */
    public static int takeCount(List<Trustee> list) {
        int count = 0;
        for (Trustee trustee : list) {
            if (trustee.permission() == Permission.STORE_AND_TAKE) {
                count++;
            }
        }
        return count;
    }

    // ============================================================
    // 改
    // ============================================================

    /**
     * 加一个人 / 改他的权限。
     *
     * 囤主不进名单 (他本来就都能做); 名单满 16 人之后再加一律 {@link Status#FULL}。
     */
    public static Status add(List<Trustee> list, UUID owner, UUID player, Permission permission) {
        if (player.equals(owner)) {
            return Status.IS_OWNER;
        }
        if (permissionOf(list, player) != null) {
            // 改权限: 就地替换 (顺序不变, 免得名单看着乱跳)。
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).player().equals(player)) {
                    list.set(i, new Trustee(player, permission));
                    return Status.UPDATED;
                }
            }
        }
        if (list.size() >= MAX_TRUSTEES) {
            return Status.FULL;
        }
        list.add(new Trustee(player, permission));
        return Status.ADDED;
    }

    /** 移出一个人; 不在名单上返回 false。 */
    public static boolean remove(List<Trustee> list, UUID player) {
        return list.removeIf(trustee -> trustee.player().equals(player));
    }

    /** 清空名单 (回到私有)。 */
    public static void clear(List<Trustee> list) {
        list.clear();
    }

    // ============================================================
    // 存档 (囤的方块实体与账本的默认名单共用这一套形状)
    // ============================================================

    public static ListTag save(List<Trustee> list) {
        ListTag tag = new ListTag();
        for (Trustee trustee : list) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(GranaryConstants.KEY_TRUSTEE, trustee.player());
            entry.putString(GranaryConstants.KEY_PERM, trustee.permission().name());
            tag.add(entry);
        }
        return tag;
    }

    /**
     * 读一份名单。
     *
     * 认不出的权限、缺人、超过 16 人一律按坏档报错 (这是**囤主的授权数据**, 读成半份等于悄悄改了
     * 谁能拿他的东西); 重复的人只留第一个 (不是坏档, 去掉不影响语义)。
     */
    public static List<Trustee> load(ListTag tag) {
        List<Trustee> list = new ArrayList<>(tag.size());
        Set<UUID> seen = new LinkedHashSet<>();
        for (int i = 0; i < tag.size(); i++) {
            CompoundTag entry = tag.getCompound(i);
            if (!entry.hasUUID(GranaryConstants.KEY_TRUSTEE)) {
                throw new IllegalArgumentException("Granary trustee is missing its player");
            }
            UUID player = entry.getUUID(GranaryConstants.KEY_TRUSTEE);
            Permission permission = parsePermission(entry.getString(GranaryConstants.KEY_PERM));
            if (seen.add(player)) {
                list.add(new Trustee(player, permission));
            }
        }
        if (list.size() > MAX_TRUSTEES) {
            throw new IllegalArgumentException("Granary trustee list is too long: " + list.size());
        }
        return list;
    }

    private static Permission parsePermission(String name) {
        for (Permission candidate : Permission.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Invalid granary trustee permission: " + name);
    }
}
