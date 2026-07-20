package com.oolongho.woonpc.nms;

import com.oolongho.woonpc.nms.util.WooNPCsException;
import com.oolongho.woonpc.util.MinecraftVersion;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * NMS 适配器工厂。
 *
 * <p>按 {@link MinecraftVersion} 选择对应的 {@link NmsAdapter} 实现。
 * 采用 <b>策略 B：Class.forName 延迟加载</b> —— 工厂引用实现类全限定名
 * （{@code Nms_1_21} / {@code Nms_1_21_5} / {@code Nms_1_21_11}），
 * 新增版本只需新增实现类即可，无需修改工厂。</p>
 *
 * <h2>版本映射</h2>
 * <ul>
 *   <li>1.21.0-1.21.4 → {@code Nms_1_21}（PacketFactory 内置版本自适应：
 *       1.21.0-1.21.1 走 STREAM_CODEC.decode 路径，1.21.2+ 走 PositionMoveRotation 4 参构造器）</li>
 *   <li>1.21.5-1.21.8 → {@code Nms_1_21_5}</li>
 *   <li>1.21.9-1.21.11+ → {@code Nms_1_21_11}（候选回退 {@code Nms_1_21_5}）</li>
 *   <li>1.22+ → {@code Nms_1_21_11}（候选回退 {@code Nms_1_21_5}，假设协议未变）</li>
 *   <li>26.x+ → {@code Nms_1_21_11}（候选回退 {@code Nms_1_21_5}）</li>
 * </ul>
 *
 * <h2>回退机制</h2>
 * <p>每个版本区间返回一个<b>候选列表</b>，工厂依次尝试加载：
 * 第一个成功加载的实现类即被实例化。1.21.9+ 区间保留 {@code Nms_1_21_5} 作为
 * 回退候选，确保即使 {@code Nms_1_21_11} 因运行时类不存在（如 1.21.9 早期 authlib
 * PropertyMap 构造器签名差异）也能继续工作。</p>
 *
 * <p>回退触发条件包括两类：</p>
 * <ul>
 *   <li>{@link ClassNotFoundException}：实现类或其依赖类不存在</li>
 *   <li>{@link LinkageError}：实现类静态初始化失败（{@code ExceptionInInitializerError}）
 *       或类定义链接失败（{@code NoClassDefFoundError}）。此类错误的典型场景是
 *       适配器静态字段初始化时通过反射加载了目标版本不存在的 NMS 类成员</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NmsAdapterFactory {

    /** 实现类包前缀 */
    private static final String IMPL_PACKAGE = "com.oolongho.woonpc.nms.versions.";

    private NmsAdapterFactory() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * 按服务端版本创建对应的 NMS 适配器。
     *
     * @param version Minecraft 服务端版本
     * @return 适配器实例
     * @throws WooNPCsException 如果该版本不支持或所有候选实现类均加载/实例化失败
     */
    public static NmsAdapter createAdapter(MinecraftVersion version) {
        List<String> candidates = resolveImplCandidates(version);
        if (candidates.isEmpty()) {
            throw new WooNPCsException("Unsupported Minecraft version: " + version
                    + " (no adapter mapping defined)");
        }

        Throwable lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            String className = candidates.get(i);
            try {
                Class<?> implClass = Class.forName(className);
                Constructor<?> ctor = implClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                NmsAdapter adapter = (NmsAdapter) ctor.newInstance();
                if (i > 0) {
                    Bukkit.getLogger().warning("[WooNPCs] NMS adapter " + candidates.get(0)
                            + " failed to load, using fallback: " + className);
                }
                return adapter;
            } catch (ClassNotFoundException e) {
                // 实现类尚未创建，记录后继续尝试下一个候选
                lastError = e;
            } catch (LinkageError e) {
                // 实现类静态初始化失败（ExceptionInInitializerError）或类定义链接失败
                // （NoClassDefFoundError），通常是适配器反射加载了目标版本不存在的成员
                lastError = e;
                Bukkit.getLogger().warning("[WooNPCs] NMS adapter " + className
                        + " failed to initialize (" + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + ")");
            } catch (ReflectiveOperationException e) {
                throw new WooNPCsException("Failed to instantiate NMS adapter " + className
                        + " (constructor error)", e);
            }
        }

        throw new WooNPCsException("No NMS adapter available for version " + version
                + " (tried: " + candidates + ").",
                lastError);
    }

    /**
     * 按版本解析候选实现类全限定名列表（有序，前者优先）。
     *
     * <p>1.21.x 系列按 patch 切分：0-4 用 {@code Nms_1_21}（1.21.0-1.21.1 由
     * PacketFactory 内部 PositionMoveRotation 容错+STREAM_CODEC.decode 自适应）、
     * 5-8 用 {@code Nms_1_21_5}、9+ 用 {@code Nms_1_21_11}（带回退候选）。
     * 1.22+ 与 26.x+ 视为与 1.21.11+ 协议兼容，使用同一适配器。</p>
     *
     * @param version Minecraft 版本
     * @return 候选实现类列表，空列表表示版本不支持
     */
    private static List<String> resolveImplCandidates(MinecraftVersion version) {
        int major = version.major();
        int minor = version.minor();
        int patch = version.patch();

        // 旧格式 1.21.x 系列：按 patch 切分
        if (major == 1 && minor == 21) {
            if (patch <= 4) {
                // 1.21.0-1.21.4：使用 Nms_1_21
                // 1.21.0-1.21.1 的 NMS 差异（PositionMoveRotation 不存在、
                // AddEntityPacket 第 11 参为 double、PlayerInfoEntry 7 参构造器、
                // TeleportPacket 无 4 参构造器）由 PacketFactory 内置版本自适应处理
                return List.of(IMPL_PACKAGE + "Nms_1_21");
            }
            if (patch <= 8) {
                // 1.21.5-1.21.8：使用 Nms_1_21_5（GameProfile 仍为普通类）
                return List.of(IMPL_PACKAGE + "Nms_1_21_5");
            }
            // 1.21.9+：GameProfile 变为 record，使用 Nms_1_21_11；回退 Nms_1_21_5
            // （fallback 仅在 Nms_1_21_11 类加载或反射初始化失败时生效）
            return List.of(
                    IMPL_PACKAGE + "Nms_1_21_11",
                    IMPL_PACKAGE + "Nms_1_21_5"
            );
        }

        // 1.22+：假设协议未变（GameProfile record 化已稳定），使用 Nms_1_21_11
        if (major == 1 && minor > 21) {
            return List.of(
                    IMPL_PACKAGE + "Nms_1_21_11",
                    IMPL_PACKAGE + "Nms_1_21_5"
            );
        }

        // 新格式年份制 26.x+：与 1.21.11+ 协议兼容，使用 Nms_1_21_11
        if (major >= 26) {
            return List.of(
                    IMPL_PACKAGE + "Nms_1_21_11",
                    IMPL_PACKAGE + "Nms_1_21_5"
            );
        }

        // 其余版本暂不支持
        return List.of();
    }
}
