package com.oolongho.woonpc.npc;

/**
 * NPC 姿势枚举。
 *
 * <p>对应 Minecraft {@code net.minecraft.world.entity.Pose}（EntityPose），
 * 通过 {@code ClientboundSetEntityDataPacket} 写入 DataWatcher 索引 24（1.21+）
 * 切换实体姿态。</p>
 *
 * <p>{@link #getNmsName()} 返回的字符串对应 NMS {@code Pose.name()} 的小写形式，
 * 用于反射构造 {@code Pose.valueOf(upperName)} 时反向还原。</p>
 *
 * <h2>版本兼容</h2>
 * <p>已移除 Mob-only 姿势（CROAKING/USING_TONGUE/ROARING/SNIFFING/EMERGING/DIGGING），
 * 旧数据反序列化时映射到 STANDING。</p>
 *
 * @author oolongho
 */
public enum NpcPose {

    /** 站立（默认） */
    STANDING("standing"),

    /** 使用鞘翅滑翔 */
    FALL_FLYING("fall_flying"),

    /** 睡觉（床） */
    SLEEPING("sleeping"),

    /** 游泳 */
    SWIMMING("swimming"),

    /** 三叉戟激流旋转攻击 */
    SPIN_ATTACK("spin_attack"),

    /** 潜行（蹲下） */
    CROUCHING("crouching"),

    /** 远跳（跳跃附魔） */
    LONG_JUMPING("long_jumping"),

    /** 死亡动画 */
    DYING("dying"),

    /** 坐下（1.21.0+ 玩家通用姿势） */
    SITTING("sitting");

    /** NMS Pose 名称（小写带下划线） */
    private final String nmsName;

    NpcPose(String nmsName) {
        this.nmsName = nmsName;
    }

    /**
     * 获取 NMS {@code Pose} 的小写带下划线名称。
     *
     * <p>如 {@code "standing"}、{@code "fall_flying"}、{@code "spin_attack"}。
     * 反射构造时通过 {@code Pose.valueOf(name().toUpperCase())} 还原枚举值。</p>
     *
     * @return NMS Pose 名称
     */
    public String getNmsName() {
        return nmsName;
    }
}
