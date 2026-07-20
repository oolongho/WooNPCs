package com.oolongho.woonpc.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Bukkit {@link YamlConfiguration} 的动作存储实现。
 *
 * <p>单一文件 {@code plugins/WooNPCs/actions.yml}，结构：
 * <pre>{@code
 * actions:
 *   <npc-uuid>:
 *     LEFT_CLICK:
 *       - { type: "console_command", command: "say hi" }
 *       - { type: "message", message: "<green>Done" }
 *     RIGHT_CLICK:
 *       - { type: "play_sound", sound: "BLOCK_NOTE_BLOCK_HAT", volume: "1.0", pitch: "1.0" }
 * }</pre>
 *
 * <h2>线程安全</h2>
 * <p>{@link #saveAll} / {@link #loadAll} 必须在主线程调用（{@link YamlConfiguration} 非线程安全）。
 * 与 {@link YamlNpcStorage} 一致，由 {@code AutoSaveTask} 在主线程周期触发。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class YamlActionStorage implements ActionStorage {

    /** 动作数据文件名 */
    private static final String FILE_NAME = "actions.yml";

    /** 顶层 section key（与配置文件结构对应） */
    private static final String ROOT_KEY = "actions";

    private final Plugin plugin;

    /**
     * 创建动作存储实例。
     *
     * @param plugin 插件实例，用于获取数据目录与日志器
     */
    public YamlActionStorage(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    @Override
    public void saveAll(Map<String, Object> data) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(ROOT_KEY, data);
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        try {
            ensureParent(file);
            yaml.save(file);
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("保存 " + file.getName() + " 失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadAll() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.isFile()) {
            return new LinkedHashMap<>();
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(file);
        } catch (Exception e) {
            plugin.getLogger().warning("加载 " + file.getName() + " 失败: " + e.getMessage());
            return new LinkedHashMap<>();
        }
        Object root = yaml.get(ROOT_KEY);
        if (root instanceof Map) {
            // Bukkit YAML 加载 List<Map> 时元素可能为 Map 而非 LinkedHashMap，需 cast
            // 直接返回，由 ActionManager.loadAll 处理元素类型
            return normalize((Map<String, Object>) root);
        }
        return new LinkedHashMap<>();
    }

    /**
     * 递归规范化：Bukkit YAML 加载的 Map 可能为非 LinkedHashMap，统一转为 LinkedHashMap
     * 以保证 ActionManager.loadAll 内的类型一致性。
     *
     * @param raw 原始加载结果
     * @return 规范化后的 Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) {
                result.put(e.getKey(), normalize((Map<String, Object>) v));
            } else if (v instanceof List) {
                result.put(e.getKey(), normalizeList((List<Object>) v));
            } else {
                result.put(e.getKey(), v);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> normalizeList(List<Object> raw) {
        List<Object> result = new java.util.ArrayList<>(raw.size());
        for (Object v : raw) {
            if (v instanceof Map) {
                result.add(normalize((Map<String, Object>) v));
            } else if (v instanceof List) {
                result.add(normalizeList((List<Object>) v));
            } else {
                result.add(v);
            }
        }
        return result;
    }

    private void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
    }
}
