package com.oolongho.woonpc.util;

import java.util.Objects;

/**
 * Minecraft 服务端版本号，支持双格式解析结果。
 *
 * <p>统一以 (major, minor, patch) 三元组表达，兼容两种版本命名格式：</p>
 * <ul>
 *   <li>旧格式 {@code 1.21.X}：major=1, minor=21, patch=X（无 patch 时为 0）</li>
 *   <li>新格式 {@code 26.X}：major=26, minor=X, patch=Y（无 minor 时为 0，无 patch 时为 0）</li>
 * </ul>
 *
 * <p>实现了 {@link Comparable}，按 major → minor → patch 字典序比较，
 * 可用于版本区间判断（如 1.21.5 是否不低于 1.21.4）。</p>
 *
 * <param name="major">主版本号</param>
 * <param name="minor">次版本号</param>
 * <param name="patch">修订号</param>
 *
 * @author oolongho
 */
public record MinecraftVersion(int major, int minor, int patch) implements Comparable<MinecraftVersion> {

    /**
     * 校验版本号非负。
     *
     * @throws IllegalArgumentException 当任一字段为负数
     */
    public MinecraftVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components must be non-negative: " + major + "." + minor + "." + patch);
        }
    }

    /**
     * 判断当前版本是否不低于指定的 (major, minor, patch)。
     *
     * @param major 目标主版本号
     * @param minor 目标次版本号
     * @param patch 目标修订号
     * @return 当前版本 &gt;= 目标版本时返回 true
     */
    public boolean isAtLeast(int major, int minor, int patch) {
        return compareTo(new MinecraftVersion(major, minor, patch)) >= 0;
    }

    /**
     * 判断当前版本是否不低于另一个版本。
     *
     * @param other 目标版本
     * @return 当前版本 &gt;= 目标版本时返回 true
     */
    public boolean isAtLeast(MinecraftVersion other) {
        Objects.requireNonNull(other, "other version cannot be null");
        return compareTo(other) >= 0;
    }

    /**
     * 判断当前版本是否不高于指定的 (major, minor, patch)。
     *
     * @param major 目标主版本号
     * @param minor 目标次版本号
     * @param patch 目标修订号
     * @return 当前版本 &lt;= 目标版本时返回 true
     */
    public boolean isAtMost(int major, int minor, int patch) {
        return compareTo(new MinecraftVersion(major, minor, patch)) <= 0;
    }

    @Override
    public int compareTo(MinecraftVersion o) {
        Objects.requireNonNull(o, "other version cannot be null");
        if (major != o.major) return Integer.compare(major, o.major);
        if (minor != o.minor) return Integer.compare(minor, o.minor);
        return Integer.compare(patch, o.patch);
    }

    /**
     * 返回标准格式字符串，如 {@code "1.21.5"} 或 {@code "26.1"}。
     * patch 为 0 时省略，minor 为 0 且 patch 为 0 时仅返回 major。
     *
     * @return 版本字符串
     */
    @Override
    public String toString() {
        if (patch == 0) {
            if (minor == 0) {
                return Integer.toString(major);
            }
            return major + "." + minor;
        }
        return major + "." + minor + "." + patch;
    }
}
