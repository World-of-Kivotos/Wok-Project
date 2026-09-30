package com.miningdim.district.flan.real;

import com.miningdim.district.flan.FlanGateway;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * 真网关的唯一入口 (设计文档 20.1 的加载顺序)。调用方 (GatewaySelector) 只在 ModList 报告 Flan 已加载、
 * {@link FlanCompat} 自检通过之后才第一次碰本类; 返回类型是 {@link FlanGateway}, 调用方因此不链接任何 Flan 类型,
 * 没装 Flan 的服务器从头到尾不会加载引用 Flan 的类。
 */
public final class FlanBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /** 备份目录 (相对世界根目录): 与各维度的 data/claims 平级, 绝不在任何 data/claims 之内 (20.5)。 */
    public static final String BACKUP_FOLDER = "miningdim/district-flan-backups";

    private FlanBridge() {
    }

    /** 建真网关 (自检通过之后)。反射字段解析失败时抛 ReflectiveOperationException, 调用方退回 Disabled。 */
    public static FlanGateway create(MinecraftServer server, LongSupplier clock) throws ReflectiveOperationException {
        Path root = server.getWorldPath(LevelResource.ROOT).resolve(BACKUP_FOLDER);
        return create(server, clock, root, ClaimBackupFiles.KEEP_START, ClaimBackupFiles.KEEP_OTHER);
    }

    /** 测试用: 注入备份目录与轮换上限。 */
    public static FlanGateway create(MinecraftServer server, LongSupplier clock, Path backupRoot, int keepStart,
                                     int keepOther) throws ReflectiveOperationException {
        FlanClaimGateway gateway = new FlanClaimGateway(server, FlanReflection.resolve(), new FlanPermissionTable(),
                new FlanClaimBackups(new ClaimBackupFiles(backupRoot, clock, keepStart, keepOther)), clock);
        LOGGER.info("[miningdim] district: Flan config relevant to districts: {}",
                FlanCompat.describeConfig(FlanBridge.class.getClassLoader()));
        Integer permissionLevel = FlanCompat.permissionLevel(FlanBridge.class.getClassLoader());
        if (permissionLevel != null && permissionLevel < 2) {
            LOGGER.warn("[miningdim] district: Flan permissionLevel is {} (below 2): level-1 operators can use Flan's "
                    + "admin commands (/flan add ... <dimension> <player>, setAdminClaim, bypass) to get around the "
                    + "personal claim limit of districts (docs/District_Backend_Design.md 22.20)", permissionLevel);
        }
        return gateway;
    }
}
