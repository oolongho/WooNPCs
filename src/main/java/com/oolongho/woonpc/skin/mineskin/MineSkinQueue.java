package com.oolongho.woonpc.skin.mineskin;

import com.oolongho.woonpc.skin.SkinData;
import com.oolongho.woonpc.skin.json.Json;
import org.jetbrains.annotations.ApiStatus;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * MineSkin 皮肤获取队列（兜底，用于图片 URL/文件标识符）。
 *
 * <p>当皮肤标识符为 {@code mineskin:<url>} 形式时使用本队列——MineSkin 从皮肤图片 URL
 * 生成可签名的 textures property。<b>注意：MineSkin 无法根据玩家名获取正版皮肤</b>，
 * 故仅处理图片 URL 标识符；玩家名/UUID 由 {@link com.oolongho.woonpc.skin.mojang.MojangQueue} 处理。</p>
 *
 * <h2>限速</h2>
 * <ul>
 *   <li>无 API key：默认间隔 8s/请求（MineSkin 无 key 限速约 5-10s/req，取保守值）</li>
 *   <li>有 API key：默认间隔 3s/请求（更宽松）</li>
 *   <li>响应若含 {@code nextRequest}（毫秒时间戳）则取 max(默认间隔, nextRequest - now)</li>
 * </ul>
 *
 * <h2>回调线程</h2>
 * <p>{@code Consumer<SkinData>} 在异步执行器线程触发；失败回调 {@link SkinData#defaultSkin()}。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class MineSkinQueue {

    private static final String GENERATE_URL_API = "https://api.mineskin.org/generate/url";

    /** 无 key 时两次请求的最小间隔（毫秒）。 */
    private static final long DELAY_NO_KEY_MS = 8_000L;
    /** 有 key 时两次请求的最小间隔（毫秒）。 */
    private static final long DELAY_WITH_KEY_MS = 3_000L;

    private final Logger logger;
    private final HttpClient http;
    private final ScheduledExecutorService executor;
    private final long timeoutMs;
    private final boolean hasKey;
    private final String apiKey;
    private final long defaultDelayMs;

    private final ConcurrentLinkedQueue<Request> queue = new ConcurrentLinkedQueue<>();
    private volatile long nextRequestTime = 0L;
    private volatile ScheduledFuture<?> poller;

    private record Request(String url, Consumer<SkinData> callback) {
    }

    public MineSkinQueue(Logger logger, ScheduledExecutorService executor, String apiKey, long timeoutMs) {
        this.logger = logger;
        this.executor = executor;
        this.timeoutMs = timeoutMs;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.hasKey = !this.apiKey.isEmpty();
        this.defaultDelayMs = hasKey ? DELAY_WITH_KEY_MS : DELAY_NO_KEY_MS;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 3000)))
                .build();
    }

    /** 启动轮询器。 */
    public void start() {
        if (poller == null) {
            poller = executor.scheduleWithFixedDelay(this::poll, 2, 1, TimeUnit.SECONDS);
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

    /** 入队一个 URL 皮肤生成请求。 */
    public void enqueue(String url, Consumer<SkinData> callback) {
        queue.add(new Request(url, callback));
    }

    private void poll() {
        if (System.currentTimeMillis() < nextRequestTime) {
            return; // 限速冷却中
        }
        Request req = queue.poll();
        if (req == null) {
            return;
        }
        executor.execute(() -> process(req));
    }

    private void process(Request req) {
        try {
            String body = "url=" + URLEncoder.encode(req.url(), StandardCharsets.UTF_8);
            String target = hasKey ? GENERATE_URL_API + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    : GENERATE_URL_API;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "WooNPCs")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long delay = computeDelay(resp);
            nextRequestTime = System.currentTimeMillis() + delay;

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                logger.warning("MineSkin generate failed (status " + resp.statusCode() + ") for: " + req.url());
                req.callback().accept(SkinData.defaultSkin());
                return;
            }

            SkinData skin = parseSkin(resp.body());
            if (skin == null) {
                logger.warning("MineSkin response missing texture for: " + req.url());
                req.callback().accept(SkinData.defaultSkin());
                return;
            }
            req.callback().accept(skin);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            req.callback().accept(SkinData.defaultSkin());
        } catch (Exception e) {
            nextRequestTime = System.currentTimeMillis() + defaultDelayMs;
            logger.warning("MineSkin generate error for '" + req.url() + "': " + e);
            req.callback().accept(SkinData.defaultSkin());
        }
    }

    /** 解析响应计算下次请求间隔：响应含 nextRequest 则取 max(默认, nextRequest - now)。 */
    private long computeDelay(HttpResponse<String> resp) {
        long delay = defaultDelayMs;
        try {
            Json.Obj obj = Json.parse(resp.body());
            Long nextRequest = obj.lng("nextRequest");
            if (nextRequest != null) {
                long fromResp = nextRequest - System.currentTimeMillis();
                if (fromResp > delay) {
                    delay = fromResp;
                }
            }
        } catch (Exception ignored) {
            // 响应非 JSON 或无 nextRequest：使用默认间隔
        }
        return delay;
    }

    /**
     * 解析 MineSkin 响应提取 textures.property。
     *
     * <p>兼容两种布局：{@code data.texture.{value,signature}}（标准）与顶层 {@code texture.{value,signature}}。</p>
     */
    private SkinData parseSkin(String body) {
        try {
            Json.Obj obj = Json.parse(body);
            Json.Obj texture = null;
            Json.Obj data = obj.obj("data");
            if (data != null) {
                texture = data.obj("texture");
            }
            if (texture == null) {
                texture = obj.obj("texture");
            }
            if (texture == null) {
                return null;
            }
            String value = texture.str("value");
            String signature = texture.str("signature");
            if (value == null) {
                return null;
            }
            return new SkinData(value, signature == null ? "" : signature);
        } catch (Exception e) {
            return null;
        }
    }
}
