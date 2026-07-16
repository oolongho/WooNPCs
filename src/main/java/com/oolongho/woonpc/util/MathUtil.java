package com.oolongho.woonpc.util;

import org.jetbrains.annotations.ApiStatus;

/**
 * 数学工具类（角度插值与归一化）。
 *
 * <p>主要用于 {@code LookTracker} 的头部朝向插值：将当前 yaw 朝目标 yaw 平滑逼近，
 * 处理 -180°~180° 跨越（如从 -170° 到 170° 应走 +20° 而非 -340°）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class MathUtil {

    private MathUtil() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * 将角度归一化到 {@code [-180, 180)}。
     *
     * @param degrees 输入角度（任意范围）
     * @return 归一化后的角度
     */
    public static float wrapDegrees(float degrees) {
        float f = degrees % 360.0f;
        if (f >= 180.0f) {
            f -= 360.0f;
        }
        if (f < -180.0f) {
            f += 360.0f;
        }
        return f;
    }

    /**
     * 计算从 {@code from} 到 {@code to} 的最短角度差（范围 {@code [-180, 180]}）。
     *
     * <p>例如 from=170, to=-170 返回 20（应顺时针走 20°），而非 -340。</p>
     *
     * @param from 起始角度
     * @param to   目标角度
     * @return 最短角度差
     */
    public static float shortestAngleDiff(float from, float to) {
        return wrapDegrees(to - from);
    }

    /**
     * 角度线性插值：从 {@code from} 朝 {@code to} 靠近 {@code step} 比例。
     *
     * <p>step=0 返回 from，step=1 返回 to，step=0.2 表示每次朝目标靠近 20%。
     * 自动处理 -180°~180° 跨越，保证走最短弧。</p>
     *
     * @param from 起始角度
     * @param to   目标角度
     * @param step 插值比例 {@code [0, 1]}
     * @return 插值后的角度（归一化到 {@code [-180, 180)}）
     */
    public static float lerpAngle(float from, float to, float step) {
        return wrapDegrees(from + shortestAngleDiff(from, to) * step);
    }
}
