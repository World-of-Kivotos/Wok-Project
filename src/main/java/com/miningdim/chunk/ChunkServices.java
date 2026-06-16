package com.miningdim.chunk;

/**
 * chunk 子系统内部静态定位器 (与 core.MiningServices 同构, 但限于 chunk/reset/entry 三个本期子系统协作)。
 * core 契约层不提供 chunk-ticket 门面且不可改, 故由 chunk 子系统自管单例: ChunkSystem 在矿山维度就绪时
 * (ServerStartedEvent) 注入 {@link IChunkTicketService} 实现, entry/reset 按接口取用。
 *
 * 异常契约 (C9): 未注入时 {@link #ticketService()} 抛 IllegalStateException 自然冒泡, 严禁返回 null
 * 掩盖 "矿山维度尚未加载 / 注入顺序错"。服务端停止时由 ChunkSystem 调 {@link #clear()} 清引用,
 * 防跨存档脏引用。
 */
public final class ChunkServices {

    private ChunkServices() {
    }

    private static volatile IChunkTicketService ticketService;

    /** ChunkSystem 在矿山维度就绪后注入。 */
    public static void registerTicketService(IChunkTicketService service) {
        if (service == null) {
            throw new IllegalArgumentException("Cannot register null IChunkTicketService");
        }
        ticketService = service;
    }

    /** entry/reset 按接口取用; 未注入 (维度未加载) 抛 IllegalStateException, 不返回 null。 */
    public static IChunkTicketService ticketService() {
        IChunkTicketService s = ticketService;
        if (s == null) {
            throw new IllegalStateException(
                    "ChunkServices: IChunkTicketService not registered (mining level not loaded yet)");
        }
        return s;
    }

    /** 是否已注入 (供 reset/entry 在维度未就绪时优雅短路, 而非触发异常)。 */
    public static boolean isReady() {
        return ticketService != null;
    }

    /** 服务端停止时清引用 (ServerStoppingEvent)。 */
    public static void clear() {
        ticketService = null;
    }
}
