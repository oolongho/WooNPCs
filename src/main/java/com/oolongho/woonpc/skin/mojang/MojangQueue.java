package com.oolongho.woonpc.skin.mojang;

import com.oolongho.woonpc.skin.SkinData;
import com.oolongho.woonpc.skin.dto.AshconResponse;
import com.oolongho.woonpc.skin.dto.MojangSessionResponse;
import com.oolongho.woonpc.skin.dto.MojangUuidResponse;
import com.oolongho.woonpc.skin.json.Json;
import com.oolongho.woonpc.skin.uuidcache.UUIDCache;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Mojang 皮肤获取队列（异步 + 限速 + 重试 + Ashcon 兜底）。
 *
 * <h2>获取流程</h2>
 * <ol>
 *   <li>输入玩家名或 UUID（自动识别）</li>
 *   <li>若为玩家名：先查 {@link UUIDCache} → 未命中请求 Mojang username→UUID API → 缓存</li>
 *   <li>用 UUID 请求 Mojang session API 获取 textures property（value 即 Base64 纹理，signature 即签名）</li>
 *   <li>Mojang session 失败/限速时回退 Ashcon API（同源返回玩家正版皮肤）</li>
 *   <li>全部失败回调 {@link SkinData#defaultSkin()}</li>
 * </ol>
 *
 * <h2>限速与并发</h2>
 * <ul>
 *   <li>使用 {@link Semaphore}（permits=1）串行化请求，天然将速率控制在 ~1 req/s，
 *       远低于 Mojang 600 req/10min 限制</li>
 *   <li>轮询器每 1s 唤醒一次，tryAcquire 成功后提交到执行器异步处理</li>
 * </ul>
 *
 * <h2>重试</h2>
 * <p>每个 HTTP 请求最多重试 2 次（共 3 次尝试），间隔递增（1s、2s）。
 * 429/5xx/IO 异常视为可重试，4xx（非 429）视为终态。重试期间持有 Semaphore，
 * 顺带起到退避作用，不会触发更严格的限速。</p>
 *
 * <h2>回调线程</h2>
 * <p>{@code Consumer<SkinData>} 在异步执行器线程触发；调用方需自行用 Bukkit scheduler
 * 切回主线程更新 {@code NpcData.skin}。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class MojangQueue {

    private static final String USERNAME_API = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_API = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String ASHCON_API = "https://api.ashcon.app/mojang/v2/user/";

    private final Logger logger;
    private final HttpClient http;
    private final UUIDCache uuidCache;
    private final ScheduledExecutorService executor;
    private final long timeoutMs;

    /** 串行化 Mojang 请求，保证速率安全并满足"用 Semaphore 控制并发"要求。 */
    private final Semaphore concurrency = new Semaphore(1);

    private final ConcurrentLinkedQueue<Request> queue = new ConcurrentLinkedQueue<>();
    private volatile ScheduledFuture<?> poller;

    /** 待处理请求。 */
    private record Request(String identifier, Consumer<SkinData> callback) {
    }

    public MojangQueue(Logger logger, ScheduledExecutorService executor, UUIDCache uuidCache, long timeoutMs) {
        this.logger = logger;
        this.executor = executor;
        this.uuidCache = uuidCache;
        this.timeoutMs = timeoutMs;
        // 连接超时取 timeoutMs 与 3s 的较小值，避免连接阶段卡死
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 3000)))
                .build();
    }

    /** 启动轮询器。 */
    public void start() {
        if (poller == null) {
            poller = executor.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
        }
    }

    /** 停止轮询器。 */
    public void shutdown() {
        if (poller != null) {
            poller.cancel(false);
            poller = null;
        }
        queue.clear();
    }

    /** 入队一个皮肤获取请求。 */
    public void enqueue(String identifier, Consumer<SkinData> callback) {
        queue.add(new Request(identifier, callback));
    }

    private void poll() {
        if (!concurrency.tryAcquire()) {
            return; // 上一个请求仍在处理中
        }
        Request req = queue.poll();
        if (req == null) {
            concurrency.release();
            return;
        }
        executor.execute(() -> {
            try {
                process(req);
            } catch (Throwable t) {
                logger.warning("Mojang skin fetch failed for '" + req.identifier() + "': " + t);
                req.callback().accept(SkinData.defaultSkin());
            } finally {
                concurrency.release();
            }
        });
    }

    private void process(Request req) {
        String identifier = req.identifier();
        try {
            UUID uuid;
            if (isUuid(identifier)) {
                uuid = parseUuid(identifier);
            } else {
                uuid = resolveUsername(identifier);
                if (uuid == null) {
                    // 玩家名查不到 UUID：最后尝试 Ashcon（它接受玩家名并直接返回 textures）
                    SkinData ashcon = fetchAshcon(identifier);
                    req.callback().accept(ashcon != null ? ashcon : SkinData.defaultSkin());
                    return;
                }
            }

            SkinData skin = fetchSession(uuid);
            if (skin == null) {
                // Mojang session 失败：回退 Ashcon（接受 UUID 或玩家名）
                skin = fetchAshcon(identifier);
            }
            req.callback().accept(skin != null ? skin : SkinData.defaultSkin());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            req.callback().accept(SkinData.defaultSkin());
        }
    }

    /** 解析玩家名→UUID：先查缓存，未命中请求 Mojang username API 并回填缓存。 */
    private @Nullable UUID resolveUsername(String name) throws InterruptedException {
        UUID cached = uuidCache.getUUID(name);
        if (cached != null) {
            return cached;
        }
        String id = fetchMojangUuid(name);
        if (id == null) {
            return null;
        }
        UUID uuid = parseUuid(id);
        uuidCache.putUUID(name, uuid);
        return uuid;
    }

    /** 请求 Mojang username→UUID API，返回无连字符 id；404/204 返回 null。 */
    private @Nullable String fetchMojangUuid(String name) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(USERNAME_API + urlEncode(name)))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        HttpResponse<String> resp = sendWithRetry(request, 2);
        if (resp == null || resp.statusCode() == 404 || resp.statusCode() == 204) {
            return null;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return null;
        }
        try {
            MojangUuidResponse dto = MojangUuidResponse.fromJson(Json.parse(resp.body()));
            return dto.id();
        } catch (Exception e) {
            logger.warning("Failed to parse Mojang UUID response for '" + name + "': " + e);
            return null;
        }
    }

    /** 请求 Mojang session API，返回 SkinData（value=texture, signature=signature）；无 profile 返回 null。 */
    private @Nullable SkinData fetchSession(UUID uuid) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SESSION_API + uuid.toString() + "?unsigned=false"))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        HttpResponse<String> resp = sendWithRetry(request, 2);
        if (resp == null || resp.statusCode() == 404 || resp.statusCode() == 204) {
            return null;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return null;
        }
        try {
            MojangSessionResponse dto = MojangSessionResponse.fromJson(Json.parse(resp.body()));
            MojangSessionResponse.Property textures = dto.property("textures");
            if (textures == null || textures.value() == null) {
                return null;
            }
            return new SkinData(textures.value(), textures.signature() == null ? "" : textures.signature());
        } catch (Exception e) {
            logger.warning("Failed to parse Mojang session response for " + uuid + ": " + e);
            return null;
        }
    }

    /** 请求 Ashcon API（接受玩家名或 UUID），返回 SkinData 或 null。重试 1 次。 */
    private @Nullable SkinData fetchAshcon(String identifier) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ASHCON_API + urlEncode(identifier)))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        HttpResponse<String> resp = sendWithRetry(request, 1);
        if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return null;
        }
        try {
            AshconResponse dto = AshconResponse.fromJson(Json.parse(resp.body()));
            if (dto.textures() == null || dto.textures().value() == null) {
                return null;
            }
            return new SkinData(dto.textures().value(),
                    dto.textures().signature() == null ? "" : dto.textures().signature());
        } catch (Exception e) {
            logger.warning("Failed to parse Ashcon response for '" + identifier + "': " + e);
            return null;
        }
    }

    /**
     * 带 retry 的 HTTP GET：429/5xx/IO 异常重试，4xx（非 429）立即返回。
     *
     * @param maxRetries 额外重试次数（总尝试 = maxRetries + 1）
     * @return 最后的响应，或全部 IO 失败时 null
     */
    private @Nullable HttpResponse<String> sendWithRetry(HttpRequest request, int maxRetries) throws InterruptedException {
        HttpResponse<String> resp = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();
                if (code == 429 || code >= 500) {
                    if (attempt < maxRetries) {
                        sleep(backoff(attempt));
                        continue;
                    }
                }
                return resp;
            } catch (java.io.IOException e) {
                if (attempt < maxRetries) {
                    sleep(backoff(attempt));
                    continue;
                }
                logger.warning("HTTP IO error after " + (attempt + 1) + " attempts: " + e.getMessage());
                return null;
            }
        }
        return resp;
    }

    private long backoff(int attempt) {
        return 1000L * (attempt + 1); // 1s, 2s, 3s ...
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 判断是否为 UUID（带或不带连字符的 32/36 位十六进制）。 */
    private static boolean isUuid(String s) {
        return s.matches("[0-9a-fA-F]{32}")
                || s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /** 将 UUID 字符串解析为 {@link UUID}（支持带/不带连字符）。 */
    private static UUID parseUuid(String s) {
        if (s.contains("-")) {
            return UUID.fromString(s);
        }
        return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20, 32));
    }
}
