package com.oolongho.woonpc.nms.versions;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据包实体 ID 生成器。
 *
 * <p>数据包 NPC 不在服务端创建真实实体，但仍需一个客户端唯一 entityId 用于
 * {@code ClientboundAddPlayerPacket} 等包。本类通过独立 {@link AtomicInteger}
 * 自增分配，避免与服务端真实实体共享计数器。</p>
 *
 * <h2>起始值选择</h2>
 * <p>从 {@value #START_ENTITY_ID} 起始，避开服务端 Entity.ENTITY_COUNTER 的常规范围
 * （服务端从 0 开始递增，正常游戏实体 ID 远小于此阈值）。
 * 若服务端实体数量异常庞大可能产生冲突，可改为反射访问
 * {@code net.minecraft.world.entity.Entity#ENTITY_COUNTER} 复用服务端计数器。</p>
 *
 * <p>线程安全：{@link AtomicInteger} 保证多线程并发调用 {@link #nextEntityId()} 安全。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class EntityIdGenerator {

    /** entityId 起始值，远高于服务端常规实体 ID 范围，避免冲突 */
    private static final int START_ENTITY_ID = 1_000_000;

    /** 自增计数器 */
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(START_ENTITY_ID);

    private EntityIdGenerator() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * 获取下一个可用的数据包实体 ID。
     *
     * <p>线程安全，多线程并发调用返回值唯一递增。</p>
     *
     * @return 新的实体 ID（首次返回 {@value #START_ENTITY_ID}）
     */
    public static int nextEntityId() {
        return NEXT_ENTITY_ID.getAndIncrement();
    }

    /**
     * 重置计数器到起始值。
     *
     * <p>仅用于测试场景或插件重载时清理状态，生产环境慎用。</p>
     */
    static void reset() {
        NEXT_ENTITY_ID.set(START_ENTITY_ID);
    }
}
