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
 * 采用 <b>策略 B：Class.forName 延迟加载</b> —— 工厂引用实现类全限定名，
 * Task 4 创建实现类（{@code Nms_1_21} / {@code Nms_1_21_5} / {@code Nms_26_1}）后
 * 无需修改工厂即可自动生效。</p>
 *
 * <h2>版本映射</h2>
 * <ul>
 *   <li>1.21.0–1.21.4 → {@code Nms_1_21}</li>
 *   <li>1.21.5+ → {@code Nms_1_21_5}</li>
 *   <li>1.22+ → 回退 {@code Nms_1_21_5}（假设协议未变）</li>
 *   <li>26.x+ → 优先 {@code Nms_26_1}，若不存在则回退 {@code Nms_1_21_5}</li>
 * </ul>
 *
 * <h2>回退机制</h2>
 * <p>每个版本区间返回一个<b>候选列表</b>，工厂依次尝试加载：
 * 第一个成功加载的实现类即被实例化。这样 26.x 在协议未变动时可不创建
 * {@code Nms_26_1}，自动回退到 {@code Nms_1_21_5}。</p>
 *
 * <p>Task 2 阶段所有实现类均不存在，{@link #createAdapter} 会抛
 * {@link WooNPCsException} 提示实现待 Task 4 提供。这是预期行为，
 * 不影响编译。</p>
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
     * @throws WooNPCsException 如果该版本不支持或实现类缺失（Task 4 未完成时）
     */
    public static NmsAdapter createAdapter(MinecraftVersion version) {
        List<String> candidates = resolveImplCandidates(version);
        if (candidates.isEmpty()) {
            throw new WooNPCsException("Unsupported Minecraft version: " + version
                    + " (no adapter mapping defined)");
        }

        Exception lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            String className = candidates.get(i);
            try {
                Class<?> implClass = Class.forName(className);
                Constructor<?> ctor = implClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                NmsAdapter adapter = (NmsAdapter) ctor.newInstance();
                if (i > 0) {
                    Bukkit.getLogger().warning("[WooNPCs] NMS adapter " + candidates.get(0)
                            + " not found, using fallback: " + className);
                }
                return adapter;
            } catch (ClassNotFoundException e) {
                // 实现类尚未创建，记录后继续尝试下一个候选
                lastError = e;
            } catch (ReflectiveOperationException e) {
                throw new WooNPCsException("Failed to instantiate NMS adapter " + className
                        + " (constructor error)", e);
            }
        }

        throw new WooNPCsException("No NMS adapter available for version " + version
                + " (tried: " + candidates + "). Adapter implementations will be provided in Task 4.",
                lastError);
    }

    /**
     * 按版本解析候选实现类全限定名列表（有序，前者优先）。
     *
     * @param version Minecraft 版本
     * @return 候选实现类列表，空列表表示版本不支持
     */
    private static List<String> resolveImplCandidates(MinecraftVersion version) {
        int major = version.major();
        int minor = version.minor();
        int patch = version.patch();

        // 旧格式 1.21.x 系列
        if (major == 1 && minor == 21) {
            if (patch <= 4) {
                // 1.21.0–1.21.4：使用 Nms_1_21
                return List.of(IMPL_PACKAGE + "Nms_1_21");
            }
            // 1.21.5+：使用 Nms_1_21_5
            return List.of(IMPL_PACKAGE + "Nms_1_21_5");
        }

        // 1.22 及以上 1.x 版本：假设协议未变，回退 Nms_1_21_5
        if (major == 1 && minor > 21) {
            return List.of(IMPL_PACKAGE + "Nms_1_21_5");
        }

        // 新格式年份制 26.x+：优先 Nms_26_1，回退 Nms_1_21_5
        if (major >= 26) {
            return List.of(
                    IMPL_PACKAGE + "Nms_26_1",
                    IMPL_PACKAGE + "Nms_1_21_5"
            );
        }

        // 其余版本暂不支持
        return List.of();
    }
}
