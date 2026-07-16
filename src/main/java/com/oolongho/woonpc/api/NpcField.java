package com.oolongho.woonpc.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * NPC 可同步字段枚举。
 *
 * <p>列出 {@link NpcData} 中所有可能"变脏"并需要增量同步到客户端的字段。
 * {@code NpcController.update(NpcData)} 依据 {@code NpcData.dirtyFields()}
 * 决定发送哪些增量更新包，避免每次全量发包。</p>
 *
 * <h2>字段分类</h2>
 * <ul>
 *   <li><b>不可变字段</b>（{@code id}、{@code name}）：创建时确定，不进入 dirty 集合</li>
 *   <li><b>可变字段</b>：通过 {@code NpcData.withXxx} 修改时自动加入 dirty 集合</li>
 * </ul>
 *
 * <p>本枚举为内部 API，外部不应直接引用；用户通过 {@code Npc.setXxx} 间接触发 dirty 标记。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public enum NpcField {

    /** 位置（含世界，通过 {@code moveTo} / {@code setLocation} 修改） */
    LOCATION,

    /** 头顶显示名 */
    DISPLAY_NAME,

    /** 皮肤纹理 */
    SKIN,

    /** 装备映射（六槽位） */
    EQUIPMENT,

    /** 发光颜色 */
    GLOW_COLOR,

    /** 姿势（EntityPose） */
    POSE,

    /** 缩放比例 */
    SCALE,

    /** 实体效果状态位（FIRE/SNEAKING/GLOWING 等） */
    EFFECTS,

    /** 是否在 tab 列表显示 */
    SHOW_IN_TAB,

    /** 是否可碰撞 */
    COLLIDABLE,

    /** 是否转头跟随玩家 */
    TURN_TO_PLAYER,

    /** 转头跟随触发距离 */
    TURN_TO_PLAYER_DISTANCE,

    /** 可见距离 */
    VISIBILITY_DISTANCE,

    /** 可见权限 */
    VISIBILITY_PERMISSIONS,

    /** 交互冷却（毫秒） */
    INTERACTION_COOLDOWN
}
