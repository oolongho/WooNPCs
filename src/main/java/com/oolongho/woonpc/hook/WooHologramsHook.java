package com.oolongho.woonpc.hook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WooHolograms 软依赖 Hook（基于纯反射）。
 *
 * <p>检测 WooHolograms 是否加载，若加载则通过 {@code WooHologramsAPI} 在 NPC 位置
 * 创建可点击多行全息，并绑定 NPC 生命周期（spawn/despawn/move/remove 同步）。</p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>纯反射</b>：不引入编译期依赖，运行时通过 {@link Class#forName} 加载 WooHolograms API。
 *       与 {@code PacketFactory} / {@code ReflectUtil} 风格一致。</li>
 *   <li><b>独立存储 lines</b>：与 {@code ActionManager} 模式一致，lines 不存入 {@code NpcData} record，
 *       由本 Hook 维护 {@code Map<UUID, List<String>>}，避免污染不可变数据快照。</li>
 *   <li><b>幂等生命周期</b>：所有 onXxx 方法幂等，重复调用安全。</li>
 *   <li><b>优雅降级</b>：WooHolograms 未加载时所有操作静默返回，不影响 NPC 主流程。</li>
 * </ul>
 *
 * <h2>Hologram ID 命名</h2>
 * <p>格式 {@code woonpc_<uuid>}，避免与用户手动创建的全息冲突。创建时调用
 * {@code Hologram.setSaveToFile(false)} 避免被 WooHolograms 持久化到磁盘
 * （NPC 临时全息的生命周期由本 Hook 管理）。</p>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>{@link #linesStore} / {@link #hologramIdStore} 使用 {@link ConcurrentHashMap} 支持并发读写</li>
 *   <li>反射元数据（{@code Method} 对象）在类初始化时一次性加载，之后只读</li>
 *   <li>所有方法应在主线程调用（WooHolograms API 非线程安全）</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class WooHologramsHook {

    /** 单例 */
    private static volatile WooHologramsHook instance;

    // ==================== 反射元数据（类初始化时加载，之后只读） ====================

    /** WooHolograms 是否成功加载（false 表示类不存在或方法签名不匹配） */
    private static volatile boolean reflectionInitialized;

    /** WooHologramsAPI.isLoaded() */
    private static Method apiIsLoadedMethod;

    /** WooHologramsAPI.createHologram(String, Location) → Optional<Hologram> */
    private static Method apiCreateHologramMethod;

    /** WooHologramsAPI.deleteHologram(String) → boolean */
    private static Method apiDeleteHologramMethod;

    /** WooHologramsAPI.getHologram(String) → Optional<Hologram> */
    private static Method apiGetHologramMethod;

    /** Hologram.setSaveToFile(boolean) */
    private static Method holoSetSaveToFileMethod;

    /** Hologram.addPage(List<String>) → HologramPage */
    private static Method holoAddPageMethod;

    /** Hologram.teleport(Location) */
    private static Method holoTeleportMethod;

    /** Hologram ID 前缀，避免与用户全息冲突 */
    private static final String HOLOGRAM_ID_PREFIX = "woonpc_";

    // ==================== 业务状态 ====================

    /** NPC → 全息行内容（跨 spawn/despawn 持久，仅 onNpcRemove 清除） */
    private final Map<UUID, List<String>> linesStore = new ConcurrentHashMap<>();

    /** NPC → 当前活跃的全息 ID（仅在 hologram 存在期间有值） */
    private final Map<UUID, String> hologramIdStore = new ConcurrentHashMap<>();

    /** 私有构造，通过 {@link #getInstance()} 获取单例 */
    private WooHologramsHook() {
        initializeReflection();
    }

    /**
     * 获取单例实例。
     *
     * @return Hook 实例（永不为 null）
     */
    public static WooHologramsHook getInstance() {
        if (instance == null) {
            synchronized (WooHologramsHook.class) {
                if (instance == null) {
                    instance = new WooHologramsHook();
                }
            }
        }
        return instance;
    }

    /**
     * 关闭 Hook：销毁所有已创建的全息并清空存储。
     *
     * <p>由插件 {@code onDisable} 调用，确保服务器关闭时 WooHolograms 不会残留 NPC 全息。</p>
     */
    public static void shutdown() {
        if (instance == null) {
            return;
        }
        for (UUID npcId : new ArrayList<>(instance.hologramIdStore.keySet())) {
            instance.onNpcRemove(npcId);
        }
        instance.linesStore.clear();
        instance = null;
    }

    // ==================== 反射初始化 ====================

    /**
     * 一次性加载 WooHolograms API 的类与方法引用。
     *
     * <p>任何类缺失或方法签名不匹配均视为 WooHolograms 不可用，
     * 后续所有操作静默返回 false/null。失败原因通过 {@code Bukkit.getLogger} 记录为 INFO 级别
     * （软依赖缺失属正常情况，不应当作 WARNING）。</p>
     */
    private static void initializeReflection() {
        if (reflectionInitialized) {
            return;
        }
        synchronized (WooHologramsHook.class) {
            if (reflectionInitialized) {
                return;
            }
            try {
                Class<?> apiClass = Class.forName("com.oolonghoo.holograms.api.WooHologramsAPI");
                Class<?> hologramClass = Class.forName("com.oolonghoo.holograms.hologram.Hologram");

                apiIsLoadedMethod = apiClass.getMethod("isLoaded");
                apiCreateHologramMethod = apiClass.getMethod("createHologram", String.class, Location.class);
                apiDeleteHologramMethod = apiClass.getMethod("deleteHologram", String.class);
                apiGetHologramMethod = apiClass.getMethod("getHologram", String.class);

                holoSetSaveToFileMethod = hologramClass.getMethod("setSaveToFile", boolean.class);
                holoAddPageMethod = hologramClass.getMethod("addPage", List.class);
                holoTeleportMethod = hologramClass.getMethod("teleport", Location.class);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                // WooHolograms 未安装或 API 签名变更：静默降级
                Bukkit.getLogger().info(() -> "[WooNPCs] WooHolograms not detected, hologram hook disabled.");
            }
            reflectionInitialized = true;
        }
    }

    /**
     * 判断 WooHolograms 是否已加载且 API 可用。
     *
     * @return 可用返回 true，否则 false
     */
    public boolean isAvailable() {
        if (!reflectionInitialized || apiIsLoadedMethod == null) {
            return false;
        }
        try {
            Object result = apiIsLoadedMethod.invoke(null);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    // ==================== Lines 管理 ====================

    /**
     * 设置 NPC 的全息行内容。
     *
     * <p>仅更新存储，不自动刷新已存在的全息。如需立即生效，
     * 调用方应在 setLines 后调用 {@link #onNpcDespawn(UUID)} + {@link #onNpcSpawn(UUID, Location)}
     * 触发重建。</p>
     *
     * @param npcId NPC UUID
     * @param lines 行内容列表，null 或空列表表示清除
     */
    public void setLines(UUID npcId, @Nullable List<String> lines) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        if (lines == null || lines.isEmpty()) {
            linesStore.remove(npcId);
            return;
        }
        linesStore.put(npcId, List.copyOf(lines));
    }

    /**
     * 获取 NPC 的全息行内容（不可变副本）。
     *
     * @param npcId NPC UUID
     * @return 行内容列表，未设置返回空列表
     */
    public List<String> getLines(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        List<String> lines = linesStore.get(npcId);
        return lines != null ? List.copyOf(lines) : Collections.emptyList();
    }

    /**
     * 清除 NPC 的全息行存储（不影响已存在的全息实体）。
     *
     * @param npcId NPC UUID
     */
    public void clearLines(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        linesStore.remove(npcId);
    }

    // ==================== NPC 生命周期钩子 ====================

    /**
     * NPC spawn 时调用：若 NPC 已配置 lines 且 WooHolograms 可用，创建全息实体。
     *
     * <p>幂等：若已存在该 NPC 的全息，先销毁旧的再创建新的。</p>
     *
     * @param npcId NPC UUID
     * @param loc   NPC 当前位置（全息将创建在此位置）
     */
    public void onNpcSpawn(UUID npcId, Location loc) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        Objects.requireNonNull(loc, "loc cannot be null");
        if (!isAvailable()) {
            return;
        }
        List<String> lines = linesStore.get(npcId);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        // 清理同 ID 的旧全息（防止遗留）
        String hologramId = HOLOGRAM_ID_PREFIX + npcId;
        deleteHologramQuietly(hologramId);

        Object hologram = createHologramQuietly(hologramId, loc);
        if (hologram == null) {
            return;
        }
        // 禁用持久化（NPC 临时全息不应被 WooHolograms 写入文件）
        invokeQuietly(holoSetSaveToFileMethod, hologram, false);
        // 添加首页（含所有行）
        invokeQuietly(holoAddPageMethod, hologram, lines);
        hologramIdStore.put(npcId, hologramId);
    }

    /**
     * NPC despawn 时调用：销毁全息实体（保留 lines 存储）。
     *
     * <p>幂等：若该 NPC 未创建全息，直接返回。</p>
     *
     * @param npcId NPC UUID
     */
    public void onNpcDespawn(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        String hologramId = hologramIdStore.remove(npcId);
        if (hologramId != null) {
            deleteHologramQuietly(hologramId);
        }
    }

    /**
     * NPC 移动时调用：传送全息到新位置。
     *
     * <p>幂等：若该 NPC 未创建全息，直接返回。</p>
     *
     * @param npcId NPC UUID
     * @param loc   NPC 新位置
     */
    public void onNpcMove(UUID npcId, Location loc) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        Objects.requireNonNull(loc, "loc cannot be null");
        String hologramId = hologramIdStore.get(npcId);
        if (hologramId == null || !isAvailable()) {
            return;
        }
        // 通过 API 获取 Hologram 实例后调用 teleport
        Object hologram = getHologramQuietly(hologramId);
        if (hologram != null) {
            invokeQuietly(holoTeleportMethod, hologram, loc.clone());
        }
    }

    /**
     * NPC 移除时调用：销毁全息 + 清空 lines 存储。
     *
     * <p>幂等：若该 NPC 未配置或未创建全息，相关操作均安全跳过。</p>
     *
     * @param npcId NPC UUID
     */
    public void onNpcRemove(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId cannot be null");
        onNpcDespawn(npcId);
        linesStore.remove(npcId);
    }

    // ==================== 反射调用辅助（统一异常吞噬） ====================

    /**
     * 调用 {@code WooHologramsAPI.createHologram}，返回 Hologram 实例或 null。
     *
     * <p>API 返回 {@code Optional<Hologram>}，本方法展开 Optional 并提取实例。</p>
     */
    @Nullable
    private static Object createHologramQuietly(String id, Location loc) {
        try {
            Object optional = apiCreateHologramMethod.invoke(null, id, loc.clone());
            if (optional == null) {
                return null;
            }
            // Optional.orElse(null) 或反射 isEmpty
            Method orElse = optional.getClass().getMethod("orElse", Object.class);
            return orElse.invoke(optional, (Object) null);
        } catch (ReflectiveOperationException e) {
            Bukkit.getLogger().warning(() -> "[WooNPCs] createHologram failed for " + id + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 调用 {@code WooHologramsAPI.deleteHologram}，静默忽略异常。
     */
    private static void deleteHologramQuietly(String id) {
        try {
            apiDeleteHologramMethod.invoke(null, id);
        } catch (ReflectiveOperationException e) {
            Bukkit.getLogger().warning(() -> "[WooNPCs] deleteHologram failed for " + id + ": " + e.getMessage());
        }
    }

    /**
     * 调用 {@code WooHologramsAPI.getHologram}，返回 Hologram 实例或 null。
     */
    @Nullable
    private static Object getHologramQuietly(String id) {
        try {
            Object optional = apiGetHologramMethod.invoke(null, id);
            if (optional == null) {
                return null;
            }
            Method orElse = optional.getClass().getMethod("orElse", Object.class);
            return orElse.invoke(optional, (Object) null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * 调用指定方法，吞掉所有反射异常（仅记录 WARNING）。
     */
    private static void invokeQuietly(@Nullable Method method, Object instance, Object... args) {
        if (method == null) {
            return;
        }
        try {
            method.invoke(instance, args);
        } catch (ReflectiveOperationException e) {
            Bukkit.getLogger().warning(() -> "[WooNPCs] Hologram API invoke '"
                    + method.getName() + "' failed: " + e.getMessage());
        }
    }
}
