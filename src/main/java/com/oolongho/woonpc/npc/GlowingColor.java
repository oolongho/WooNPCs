package com.oolongho.woonpc.npc;

/**
 * NPC 发光颜色枚举。
 *
 * <p>对应 Minecraft 1.21 的 16 种团队颜色（Team Color），外加 {@link #NONE} 表示不发光。
 * 发光效果本身由 {@code NpcEffect.GLOWING} 状态位控制，本枚举仅决定发光的着色。</p>
 *
 * <p>团队颜色名（{@link #getTeamColor()}）对应 NMS {@code ChatFormatting} 的小写名称，
 * 用于构造 Scoreboard Team 的 color 字段。{@link #NONE} 返回空字符串，
 * 表示不覆盖团队颜色（继承默认白色或由其他逻辑控制）。</p>
 *
 * @author oolongho
 */
public enum GlowingColor {

    /** 不发光 / 不覆盖团队颜色 */
    NONE(""),

    /** 黑色 */
    BLACK("black"),

    /** 深蓝色 */
    DARK_BLUE("dark_blue"),

    /** 深绿色 */
    DARK_GREEN("dark_green"),

    /** 深青色 */
    DARK_AQUA("dark_aqua"),

    /** 深红色 */
    DARK_RED("dark_red"),

    /** 深紫色 */
    DARK_PURPLE("dark_purple"),

    /** 金色 */
    GOLD("gold"),

    /** 灰色 */
    GRAY("gray"),

    /** 深灰色 */
    DARK_GRAY("dark_gray"),

    /** 蓝色 */
    BLUE("blue"),

    /** 绿色 */
    GREEN("green"),

    /** 青色 */
    AQUA("aqua"),

    /** 红色 */
    RED("red"),

    /** 浅紫色 */
    LIGHT_PURPLE("light_purple"),

    /** 黄色 */
    YELLOW("yellow"),

    /** 白色 */
    WHITE("white");

    /** 团队颜色名（小写带下划线），{@link #NONE} 为空字符串 */
    private final String teamColor;

    GlowingColor(String teamColor) {
        this.teamColor = teamColor;
    }

    /**
     * 获取 Scoreboard Team 颜色名。
     *
     * <p>对应 NMS {@code ChatFormatting.getName()}，如 {@code "red"}、{@code "aqua"}、
     * {@code "dark_blue"} 等。{@link #NONE} 返回空字符串。</p>
     *
     * @return 团队颜色名，{@link #NONE} 返回空串
     */
    public String getTeamColor() {
        return teamColor;
    }

    /**
     * 判断是否为有效颜色（非 {@link #NONE}）。
     *
     * @return 当前枚举不是 {@link #NONE} 时返回 true
     */
    public boolean isColored() {
        return this != NONE;
    }
}
