package com.miningdim.title;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 一名玩家的专属称号记录 (title_custom 一行, Title_System_DesignSpec 13.2 / 13.6)。
 *
 * 专属称号的 id 固定为 {@code miningdim:custom/<玩家UUID小写>}, 定义不来自数据包, 而是按玩家从这条记录动态生成
 * ({@link #definition()})。赞助资格失效时记录保留, 只是不再出现在持有集合里; 续期后原样恢复。
 * {@code miningdim:custom/} 整个前缀都保留给本流程: 数据包里同名路径的定义会被加载器跳过, 任何 grant 入口一律拒发。
 *
 * @param player    玩家
 * @param style     文字、色标与粗体
 * @param updatedAt 玩家本人最近一次成功修改的时间 (毫秒), 即冷却起点; 管理员清除冷却时置 0, 管理员代设置沿用原值
 *                  (代设置新建的记录为 0)
 * @param locked    管理员是否禁止该玩家修改
 * @param lockedBy  锁定的执行者; 未锁定为 null
 */
public record CustomTitle(UUID player, CustomTitleStyle style, long updatedAt, boolean locked,
                          @Nullable String lockedBy) {

    /** 专属称号 id 的路径前缀 (命名空间固定为本模组)。 */
    public static final String ID_PREFIX = "custom/";

    public CustomTitle {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(style, "style");
    }

    /** 这条记录对应的称号 id。 */
    public ResourceLocation id() {
        return idOf(player);
    }

    /** 动态生成的称号定义: 没有稀有度, 原样显示, 排在一切数据包称号之前。 */
    public TitleDefinition definition() {
        return TitleDefinition.custom(id(), style);
    }

    /** 某名玩家的专属称号 id。 */
    public static ResourceLocation idOf(UUID player) {
        return new ResourceLocation(MiningConstants.MODID, ID_PREFIX + player);
    }

    /** 是否落在专属称号保留的 id 前缀里 (无论后缀是不是合法 UUID)。 */
    public static boolean isCustomId(ResourceLocation id) {
        return MiningConstants.MODID.equals(id.getNamespace()) && id.getPath().startsWith(ID_PREFIX);
    }

    /** 专属称号 id 的所有者; 不是专属称号 id、或后缀不是规范的小写 UUID 时为 null。 */
    @Nullable
    public static UUID ownerOf(ResourceLocation id) {
        if (!isCustomId(id)) {
            return null;
        }
        String suffix = id.getPath().substring(ID_PREFIX.length());
        try {
            UUID owner = UUID.fromString(suffix);
            // UUID.fromString 接受省略前导零等非规范写法; 只认 idOf 生成的那一种, 一名玩家只有一个专属称号 id。
            return owner.toString().equals(suffix) ? owner : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
