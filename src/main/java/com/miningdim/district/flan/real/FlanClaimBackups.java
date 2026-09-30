package com.miningdim.district.flan.real;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.flan.FlanResult;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimStorage;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Set;

/**
 * 管理员领地的逻辑备份 (设计文档 20.5), Flan 一侧: 在服务器线程上复制 {@code ClaimStorage.getClaims().get(null)}
 * (管理员领地的集合), 逐块 {@code toJson(new JsonObject())} 装进 JsonArray —— 与 Flan 存盘的 !AdminClaims.json 同构,
 * 地块嵌在各自父领地的 SubClaims 里。取内存里的现状而不是复制磁盘文件: 内存里的正好是"这一批改动之前", 连还没存盘
 * 的改动也在内。目录、命名、原子写、节流与轮换在 {@link ClaimBackupFiles}。
 */
final class FlanClaimBackups {

    private final ClaimBackupFiles files;

    FlanClaimBackups(ClaimBackupFiles files) {
        this.files = files;
    }

    ClaimBackupFiles files() {
        return files;
    }

    /** 每批改动之前: 这个维度 10 分钟内没有备份就先备一份 (原因 batch)。 */
    synchronized FlanResult<Void> ensureRecent(ServerLevel level, String dimension) {
        if (files.recent(dimension)) {
            return FlanResult.success();
        }
        FlanResult<String> made = backup(level, dimension, "batch");
        return made.ok() ? FlanResult.success() : FlanResult.failure(made.error() == null ? "" : made.error());
    }

    /** 不论节流, 立刻备份一个维度的管理员领地; 返回文件名。上一次失败还不到一分钟时直接失败, 不序列化。 */
    synchronized FlanResult<String> backup(ServerLevel level, String dimension, String reason) {
        if (files.coolingDown(dimension)) {
            return FlanResult.failure(DistrictTexts.FLAN_BACKUP_FAILED);
        }
        JsonArray claims = new JsonArray();
        Set<Claim> admin = ClaimStorage.get(level).getClaims().get(null);
        if (admin != null) {
            for (Claim claim : List.copyOf(admin)) {
                claims.add(claim.toJson(new JsonObject()));
            }
        }
        return files.write(dimension, reason, claims);
    }
}
