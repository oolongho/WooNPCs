package com.oolongho.woonpc.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 消息管理器：加载 {@code lang/<locale>.yml} + MiniMessage 渲染 + 占位符替换。
 *
 * <p>统一所有用户可见消息的来源，支持热重载（{@link #reload()}）。
 * 所有消息字符串均为 MiniMessage 格式（如 {@code <green>成功}），由本类解析为 Adventure {@link Component}。</p>
 *
 * <h2>占位符约定</h2>
 * <p>使用 {@code {key}} 形式的占位符。lang 文件顶层 {@code prefix} 字段自动注册为 {@code {prefix}} 占位符，
 * 消息中通过 {@code {prefix}} 引用前缀。调用方可传入额外占位符覆盖默认值。</p>
 *
 * <h2>性能</h2>
 * <ul>
 *   <li>加载时缓存到 {@link YamlConfiguration}，查询 O(log n)</li>
 *   <li>{@link MiniMessage} 实例共享，避免重复创建</li>
 *   <li>无消息时降级返回路径名，便于发现缺失的 key</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class MessageManager {

    /** 默认语言文件目录（相对插件数据目录） */
    private static final String LANG_DIR = "lang";

    /** 默认语言文件名（config.yml 中 locale 配置对应） */
    private static final String DEFAULT_LOCALE = "zh-CN";

    /** 前缀占位符名（lang 文件顶层字段） */
    private static final String PREFIX_KEY = "prefix";

    private final Plugin plugin;
    private final MiniMessage miniMessage;
    private File langFile;
    private volatile YamlConfiguration messages;
    private volatile String prefix = "";

    /**
     * 创建消息管理器并加载默认语言文件。
     *
     * @param plugin 插件实例
     */
    public MessageManager(@NotNull Plugin plugin) {
        this(plugin, MiniMessage.miniMessage());
    }

    /**
     * 创建消息管理器（自定义 MiniMessage 实例，主要供测试使用）。
     *
     * @param plugin      插件实例
     * @param miniMessage MiniMessage 实例
     */
    public MessageManager(@NotNull Plugin plugin, @NotNull MiniMessage miniMessage) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.miniMessage = Objects.requireNonNull(miniMessage, "miniMessage cannot be null");
        reload();
    }

    /**
     * 重新加载语言文件。
     *
     * <p>首次调用会从 jar 释放默认 lang/zh-CN.yml（若不存在）。
     * 文件加载失败时降级为空配置（所有消息返回路径名）。</p>
     */
    public void reload() {
        File dir = new File(plugin.getDataFolder(), LANG_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建语言目录: " + dir.getAbsolutePath());
        }
        String locale = DEFAULT_LOCALE;
        try {
            locale = plugin.getConfig().getString("settings.locale", DEFAULT_LOCALE);
        } catch (RuntimeException ignored) {
            // getConfig 未初始化时使用默认
        }
        String fileName = locale + ".yml";
        this.langFile = new File(dir, fileName);
        if (!langFile.isFile()) {
            // 从 jar 释放默认语言文件
            String resourcePath = LANG_DIR + "/" + fileName;
            try {
                plugin.saveResource(resourcePath, false);
            } catch (IllegalArgumentException e) {
                // jar 中无对应资源，尝试默认 zh-CN.yml
                try {
                    plugin.saveResource(LANG_DIR + "/" + DEFAULT_LOCALE + ".yml", false);
                    this.langFile = new File(dir, DEFAULT_LOCALE + ".yml");
                } catch (IllegalArgumentException ignored2) {
                    // jar 中无任何语言文件，降级为空
                }
            }
        }
        try {
            this.messages = YamlConfiguration.loadConfiguration(langFile);
            this.prefix = messages.getString(PREFIX_KEY, "");
        } catch (RuntimeException e) {
            plugin.getLogger().warning("加载语言文件失败: " + e.getMessage());
            this.messages = new YamlConfiguration();
            this.prefix = "";
        }
    }

    /**
     * 获取原始消息字符串（无渲染，无占位符替换）。
     *
     * @param path 消息路径（点分隔），如 {@code general.no-permission}
     * @return 原始字符串；路径不存在时返回 path 本身
     */
    public @NotNull String getRaw(@NotNull String path) {
        String value = messages.getString(path);
        return value != null ? value : path;
    }

    /**
     * 渲染消息为 Component（占位符替换 + MiniMessage 解析）。
     *
     * <p>自动注入 {@code {prefix}} 占位符（来自 lang 文件顶层 prefix 字段）。
     * 调用方传入的 placeholders 若包含 {@code prefix} 键则覆盖默认前缀。</p>
     *
     * @param path         消息路径
     * @param placeholders 额外占位符键值对，可为 null
     * @return 渲染后的 Component
     */
    public @NotNull Component get(@NotNull String path, @Nullable Map<String, String> placeholders) {
        String raw = getRaw(path);
        Map<String, String> merged = mergePrefix(placeholders);
        return miniMessage.deserialize(replace(raw, merged));
    }

    /**
     * 渲染消息为 Component（仅前缀占位符，无额外替换）。
     *
     * @param path 消息路径
     * @return 渲染后的 Component
     */
    public @NotNull Component get(@NotNull String path) {
        return get(path, null);
    }

    /**
     * 发送消息给命令发送者。
     *
     * @param sender       接收者
     * @param path         消息路径
     * @param placeholders 额外占位符键值对，可为 null
     */
    public void send(@NotNull CommandSender sender, @NotNull String path, @Nullable Map<String, String> placeholders) {
        sender.sendMessage(get(path, placeholders));
    }

    /**
     * 发送消息给命令发送者（仅前缀占位符）。
     *
     * @param sender 接收者
     * @param path   消息路径
     */
    public void send(@NotNull CommandSender sender, @NotNull String path) {
        send(sender, path, null);
    }

    /**
     * 发送消息给玩家，并附加 PlaceholderAPI 占位符解析（需 PAPI 已安装）。
     *
     * <p>顺序：先替换内置 {@code {key}} 占位符，再交由 PAPI 解析 {@code %...%}，最后 MiniMessage 渲染。</p>
     *
     * @param player       玩家
     * @param path         消息路径
     * @param placeholders 内置占位符键值对，可为 null
     */
    public void sendWithPapi(@NotNull Player player, @NotNull String path, @Nullable Map<String, String> placeholders) {
        String raw = getRaw(path);
        Map<String, String> merged = mergePrefix(placeholders);
        String replaced = replace(raw, merged);
        String papiParsed = com.oolongho.woonpc.util.PlaceholderUtil.setPlaceholders(player, replaced);
        player.sendMessage(miniMessage.deserialize(papiParsed));
    }

    /**
     * 合并 prefix 与调用方传入的 placeholders（调用方优先级更高）。
     */
    private Map<String, String> mergePrefix(@Nullable Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            Map<String, String> result = new LinkedHashMap<>(2);
            result.put(PREFIX_KEY, prefix);
            return result;
        }
        Map<String, String> result = new LinkedHashMap<>(placeholders.size() + 1);
        result.put(PREFIX_KEY, prefix);
        result.putAll(placeholders);
        return result;
    }

    /**
     * 替换字符串中的 {@code {key}} 占位符。
     *
     * @param input        输入字符串
     * @param placeholders 占位符键值对，null 时原样返回
     * @return 替换后的字符串
     */
    private static @NotNull String replace(@NotNull String input, @Nullable Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return input;
        }
        String result = input;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }
}
