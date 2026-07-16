package com.oolongho.woonpc.api.actions;

import com.oolongho.woonpc.npc.ClickType;

/**
 * NPC 动作触发器枚举。
 *
 * <p>定义 {@link NpcAction} 被绑定的触发条件。与 {@link ClickType}（玩家原始点击类型）
 * 是不同维度：{@code ClickType} 描述玩家"做了什么动作"，{@code ActionTrigger} 描述
 * "在何种触发条件下执行这组动作"。</p>
 *
 * <h2>触发逻辑</h2>
 * <ul>
 *   <li>{@link #LEFT_CLICK}：仅匹配玩家左键点击</li>
 *   <li>{@link #RIGHT_CLICK}：仅匹配玩家右键点击</li>
 *   <li>{@link #ANY_CLICK}：匹配任意点击（含 Shift 点击）</li>
 *   <li>{@link #CUSTOM}：自定义触发（不通过点击匹配，如对话进度、定时器等）</li>
 * </ul>
 *
 * <p>{@link ActionManager} 在 {@code execute} 时优先查找具体 trigger
 * （{@code LEFT_CLICK}/{@code RIGHT_CLICK}），若为空再回退到 {@link #ANY_CLICK}。
 * {@link #CUSTOM} 永远不会被自动触发，仅供外部代码主动调用
 * {@code ActionManager.getActions(npcId, ActionTrigger.CUSTOM)}。</p>
 *
 * @author oolongho
 */
public enum ActionTrigger {

    /** 左键点击触发 */
    LEFT_CLICK,

    /** 右键点击触发 */
    RIGHT_CLICK,

    /** 任意点击触发（含 Shift 点击） */
    ANY_CLICK,

    /** 自定义触发（不通过点击匹配，由外部代码主动调用） */
    CUSTOM;

    /**
     * 判断本 trigger 是否匹配玩家点击类型。
     *
     * <p>{@link #CUSTOM} 永远不匹配（返回 false），因其不通过点击触发。
     * {@link #ANY_CLICK} 匹配所有 {@link ClickType}（含 SHIFT_CLICK）。</p>
     *
     * @param clickType 玩家点击类型，不可为 null
     * @return 匹配时返回 true
     */
    public boolean matches(ClickType clickType) {
        if (this == ANY_CLICK) return true;
        if (this == LEFT_CLICK) return clickType == ClickType.LEFT_CLICK;
        if (this == RIGHT_CLICK) return clickType == ClickType.RIGHT_CLICK;
        return false; // CUSTOM 不通过点击匹配
    }
}
