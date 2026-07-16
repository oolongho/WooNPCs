package com.oolongho.woonpc.skin;

import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.SkinManager;
import com.oolongho.woonpc.skin.cache.SkinCacheFile;
import com.oolongho.woonpc.skin.cache.SkinCacheMemory;
import com.oolongho.woonpc.skin.mineskin.MineSkinQueue;
import com.oolongho.woonpc.skin.mojang.MojangQueue;
import com.oolongho.woonpc.skin.uuidcache.UUIDCache;
import com.oolongho.woonpc.skin.uuidcache.UUIDFileCache;
import com.oolongho.woonpc.skin.uuidcache.UUIDMemoryCache;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 皮肤系统编排实现：双层缓存 + Mojang 优先（Ashcon 兜底）+ MineSkin（图片 URL）。
 *
 * <h2>获取流程</h2>
 * <ol>
 *   <li>内存缓存命中 → 异步回调</li>
 *   <li>文件缓存命中 → 回填内存 → 异步回调</li>
 *   <li>未命中异步请求：
 *     <ul>
 *       <li>玩家名/UUID → {@link MojangQueue}（内部 Ashcon 兜底）</li>
 *       <li>{@code mineskin:<url>} → {@link MineSkinQueue}</li>
 *     </ul>
 *   </li>
 *   <li>成功后写入双层缓存（默认皮肤不写缓存）→ 异步回调</li>
 * </ol>
 *
 * <h2>回调线程</h2>
 * <p>所有回调统一在皮肤执行器线程触发（含缓存命中场景），保证调用方契约一致：
 * 回调<b>总是异步</b>。调用方需用 Bukkit scheduler 切回主线程更新 {@code NpcData.skin}。</p>
 *
 * <h2>装配方式</h2>
 * <p>由于 Task 6 不允许修改 Task 1 的 {@code WooNPCs.java} 主类装配逻辑，本类采用懒加载单例：
 * 首次 {@link #getInstance()} 时读取 {@code config.yml} 的 {@code skin.*} 配置并初始化全部组件。
 * 提供 {@link #shutdown()} 供后续 {@code onDisable} 调用（TODO: 待允许修改主类时接入）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class SkinManagerImpl implements SkinManager {

    private static final String MINESKIN_PREFIX = "mineskin:";

    private static volatile SkinManagerImpl instance;

    private final WooNPCs plugin;
    private final Logger logger;
    private final ScheduledExecutorService executor;
    private final SkinCacheMemory memCache;
    private final SkinCacheFile fileCache;
    private final UUIDCache uuidCache;
    private final MojangQueue mojangQueue;
    private final MineSkinQueue mineSkinQueue;

    private SkinManagerImpl() {
        this.plugin = WooNPCs.getInstance();
        this.logger = plugin.getLogger();

        int cacheSize = plugin.getConfig().getInt("skin.cache-memory-size", 1000);
        long mojangTimeout = plugin.getConfig().getLong("skin.mojang-timeout", 5000L);
        String mineskinKey = plugin.getConfig().getString("skin.mineskin-api-key", "");

        // 4 线程：Mojang（Semaphore 串行）+ MineSkin（串行）+ 回调/文件 IO 并行
        this.executor = Executors.newScheduledThreadPool(4, Thread.ofPlatform().name("WooNPCs-Skin-", 0).factory());
        this.memCache = new SkinCacheMemory(cacheSize);
        this.fileCache = new SkinCacheFile(plugin.getDataFolder().toPath().resolve("skin-cache"));
        this.uuidCache = new CompositeUUIDCache(
                new UUIDMemoryCache(500),
                new UUIDFileCache(plugin.getDataFolder().toPath().resolve("uuid-cache")));
        this.mojangQueue = new MojangQueue(logger, executor, uuidCache, mojangTimeout);
        this.mineSkinQueue = new MineSkinQueue(logger, executor, mineskinKey, mojangTimeout);

        mojangQueue.start();
        mineSkinQueue.start();
        logger.info("SkinManager initialized (cache-size=" + cacheSize + ", mojang-timeout=" + mojangTimeout + "ms)");
    }

    /**
     * 获取 SkinManager 单例（首次调用触发初始化）。
     *
     * @return 单例实例
     */
    public static SkinManagerImpl getInstance() {
        if (instance == null) {
            synchronized (SkinManagerImpl.class) {
                if (instance == null) {
                    instance = new SkinManagerImpl();
                }
            }
        }
        return instance;
    }

    /** 关闭皮肤系统：停止队列轮询并关闭执行器。应由插件 onDisable 调用。 */
    public void shutdown() {
        if (instance == null) {
            return;
        }
        mojangQueue.shutdown();
        mineSkinQueue.shutdown();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void getSkin(String identifier, Consumer<SkinData> callback) {
        // 内存命中：统一异步回调，保证回调线程契约一致
        SkinData memHit = memCache.get(identifier);
        if (memHit != null) {
            executor.execute(() -> callback.accept(memHit));
            return;
        }
        // 未命中：文件缓存 → 网络，全部在异步线程
        executor.execute(() -> {
            SkinData fileHit = fileCache.get(identifier);
            if (fileHit != null) {
                memCache.put(identifier, fileHit);
                callback.accept(fileHit);
                return;
            }
            fetchAsync(identifier, callback);
        });
    }

    @Override
    public @Nullable SkinData getSkinSync(String identifier) {
        return memCache.get(identifier);
    }

    /** 异步获取：按标识符类型分发到 Mojang 队列或 MineSkin 队列。 */
    private void fetchAsync(String identifier, Consumer<SkinData> callback) {
        if (identifier.startsWith(MINESKIN_PREFIX)) {
            String url = identifier.substring(MINESKIN_PREFIX.length());
            mineSkinQueue.enqueue(url, skin -> onFetched(identifier, skin, callback));
        } else {
            // 玩家名 / UUID 统一交给 MojangQueue（内部自动识别 + Ashcon 兜底）
            mojangQueue.enqueue(identifier, skin -> onFetched(identifier, skin, callback));
        }
    }

    /** 获取完成：非默认皮肤写入双层缓存，再回调。 */
    private void onFetched(String identifier, SkinData skin, Consumer<SkinData> callback) {
        if (skin != null && !skin.isDefault()) {
            memCache.put(identifier, skin);
            fileCache.put(identifier, skin);
        }
        callback.accept(skin != null ? skin : SkinData.defaultSkin());
    }

    /** 内存 + 文件复合 UUID 缓存：读时内存→文件（命中回填内存），写时双写。 */
    private static final class CompositeUUIDCache implements UUIDCache {
        private final UUIDCache memory;
        private final UUIDCache file;

        CompositeUUIDCache(UUIDCache memory, UUIDCache file) {
            this.memory = memory;
            this.file = file;
        }

        @Override
        public @Nullable UUID getUUID(String name) {
            UUID id = memory.getUUID(name);
            if (id != null) {
                return id;
            }
            id = file.getUUID(name);
            if (id != null) {
                memory.putUUID(name, id); // 回填内存
            }
            return id;
        }

        @Override
        public void putUUID(String name, UUID uuid) {
            memory.putUUID(name, uuid);
            file.putUUID(name, uuid);
        }

        @Override
        public void invalidate(String name) {
            memory.invalidate(name);
            file.invalidate(name);
        }

        @Override
        public void clear() {
            memory.clear();
            file.clear();
        }
    }
}
