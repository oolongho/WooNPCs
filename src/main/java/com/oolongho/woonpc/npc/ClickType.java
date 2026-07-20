package com.oolongho.woonpc.npc;

import java.util.Objects;

/**
 * NPC 点击类型枚举。
 *
 * <p>定义玩家与 NPC 交互时的点击方式，用于 {@code Npc.interact(Player, ClickType)}
 * 触发对应的动作集合（{@code ActionManager}）。</p>
 *
 * <p>与 Bukkit {@code org.bukkit.event.block.Action} 的差异：本枚举仅区分三类点击，
 * 不细分左/右键点击空气或方块，由 {@code NpcInteractListener} 将
 * Bukkit 原始 Action 归约为本枚举。</p>
 *
 * @author oolongho
 */
public enum ClickType {

    /** 左键点击（攻击键） */
    LEFT_CLICK("left", "左键点击"),

    /** 右键点击（交互键） */
    RIGHT_CLICK("right", "右键点击"),

    /** Shift + 任意键点击（潜行状态下交互） */
    SHIFT_CLICK("shift", "Shift 点击");

    /** 类型 ID（小写，用于配置文件序列化） */
    private final String id;

    /** 中文描述 */
    private final String description;

    ClickType(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /**
     * 获取类型 ID（小写，用于配置文件）。
     *
     * @return 类型 ID，如 {@code "left"}、{@code "right"}、{@code "shift"}
     */
    public String getId() {
        return id;
    }

    /**
     * 获取中文描述。
     *
     * @return 描述文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * 判断是否为 Shift 点击。
     *
     * @return 当前类型为 {@link #SHIFT_CLICK} 时返回 true
     */
    public boolean isShiftClick() {
        return this == SHIFT_CLICK;
    }

    /**
     * 根据 ID 解析点击类型。
     *
     * @param id 类型 ID，null 或空串返回 null
     * @return 对应的点击类型，未知 ID 返回 null
     */
    public static ClickType fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (ClickType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 根据 ID 解析点击类型，带默认值。
     *
     * @param id           类型 ID
     * @param defaultValue 解析失败时的默认值
     * @return 对应的点击类型，未知 ID 返回 defaultValue
     */
    public static ClickType fromIdOrDefault(String id, ClickType defaultValue) {
        ClickType resolved = fromId(id);
        return resolved != null ? resolved : Objects.requireNonNull(defaultValue, "defaultValue cannot be null");
    }
}
