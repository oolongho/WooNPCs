package com.oolongho.woonpc.util;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 服务端版本解析工具。
 *
 * <p>从 {@link Bukkit#getBukkitVersion()}（如 {@code "1.21.5-R0.1-SNAPSHOT"}）
 * 解析出 {@link MinecraftVersion}。支持双格式：</p>
 * <ul>
 *   <li>旧格式 {@code 1.21.X}（1.21, 1.21.1, 1.21.5, 1.21.11）</li>
 *   <li>新格式 {@code 26.X}（26.1, 26.1.2, 26.2）</li>
 * </ul>
 *
 * <p>解析结果在首次调用 {@link #getServerVersion()} 时缓存，
 * 之后直接返回缓存实例（服务端版本运行期不变）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class VersionUtil {

    /** 匹配版本字符串开头的数字部分，兼容双格式：^(major)(.minor)?(.patch)? */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /** 缓存的服务端版本，volatile 保证多线程可见性 */
    private static volatile MinecraftVersion cachedServerVersion;

    private VersionUtil() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * 获取当前服务端的 Minecraft 版本。
     *
     * <p>首次调用时从 {@link Bukkit#getBukkitVersion()} 解析并缓存，
     * 后续调用直接返回缓存实例。线程安全。</p>
     *
     * @return 服务端版本
     */
    public static MinecraftVersion getServerVersion() {
        MinecraftVersion snapshot = cachedServerVersion;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (VersionUtil.class) {
            if (cachedServerVersion == null) {
                cachedServerVersion = parse(Bukkit.getBukkitVersion());
            }
            return cachedServerVersion;
        }
    }

    /**
     * 解析版本字符串为 {@link MinecraftVersion}。
     *
     * <p>支持带后缀的 Bukkit 版本串（如 {@code "1.21.5-R0.1-SNAPSHOT"}），
     * 仅取开头的主.次.修订部分，缺失部分按 0 处理。</p>
     *
     * @param versionString 原始版本字符串
     * @return 解析后的版本对象
     * @throws IllegalArgumentException 当字符串不包含合法版本号
     */
    public static MinecraftVersion parse(String versionString) {
        if (versionString == null || versionString.isBlank()) {
            throw new IllegalArgumentException("Version string is null or blank");
        }

        Matcher matcher = VERSION_PATTERN.matcher(versionString);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Cannot parse version from: " + versionString);
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
        int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;

        return new MinecraftVersion(major, minor, patch);
    }
}
