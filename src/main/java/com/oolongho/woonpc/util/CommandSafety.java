package com.oolongho.woonpc.util;

import org.jetbrains.annotations.ApiStatus;

import java.util.regex.Pattern;

/**
 * 命令安全校验工具。
 *
 * <p>提供 NPC 名、半径、坐标的合法性校验，供命令系统在解析参数前调用。
 * 所有方法返回 boolean，不抛异常，校验失败由调用方决定如何反馈。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class CommandSafety {

    /** NPC 名合法性正则：1-32 字符，仅字母数字下划线 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,32}$");

    /** 最大半径（方块） */
    private static final double MAX_RADIUS = 100.0;

    private CommandSafety() {
    }

    /**
     * 校验 NPC 名合法性。
     *
     * @param name 待校验名称
     * @return 合法返回 true（1-32 字符，仅字母数字下划线）
     */
    public static boolean validateName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * 校验半径合法性。
     *
     * @param radius 半径
     * @return 合法返回 true（{@code >0} 且 {@code <=100}）
     */
    public static boolean validateRadius(double radius) {
        return radius > 0.0 && radius <= MAX_RADIUS;
    }

    /**
     * 校验坐标字符串合法性（必须为可解析数字）。
     *
     * @param coord 坐标字符串
     * @return 合法返回 true
     */
    public static boolean validateCoordinate(String coord) {
        if (coord == null || coord.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(coord);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
