package com.oolongho.woonpc.npc;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * NPC 实体效果枚举。
 *
 * <p>对应 Minecraft 实体状态位（Entity Status Flags，DataWatcher 索引 0）的各位标志。
 * 多个效果可通过位或叠加为单个 byte 值写入元数据。</p>
 *
 * <h2>位掩码对照（参考 Minecraft 协议）</h2>
 * <ul>
 *   <li>{@link #FIRE}       = 0x01（着火）</li>
 *   <li>{@link #SNEAKING}   = 0x02（潜行 / 蹲下）</li>
 *   <li>{@link #SPRINTING}  = 0x04（冲刺）</li>
 *   <li>{@link #SWIMMING}   = 0x08（游泳）</li>
 *   <li>{@link #INVISIBLE}  = 0x10（不可见，仅影响元数据标志位，不影响 spawn 决策）</li>
 *   <li>{@link #GLOWING}    = 0x20（发光，发光颜色由 {@link GlowingColor} 控制）</li>
 * </ul>
 *
 * <p>注意：{@link #INVISIBLE} 仅控制实体元数据中的不可见标志位，
 * 数据包 NPC 是否真正"不可见"由上层 {@code NpcData} / Tracker 决定是否发送 spawn 包，
 * 本枚举不干预 spawn 流程。</p>
 *
 * @author oolongho
 */
public enum NpcEffect {

    /** 着火（实体燃烧动画） */
    FIRE(0x01),

    /** 潜行（身体压低，区别于 {@link NpcPose#CROUCHING} 元数据姿势） */
    SNEAKING(0x02),

    /** 冲刺（跑步粒子与动作） */
    SPRINTING(0x04),

    /** 游泳（游泳动作） */
    SWIMMING(0x08),

    /** 不可见（实体元数据标志位，不阻止 spawn） */
    INVISIBLE(0x10),

    /** 发光（轮廓发光，颜色由 {@link GlowingColor} 控制） */
    GLOWING(0x20);

    /** 单效果位掩码 */
    private final int bitmask;

    NpcEffect(int bitmask) {
        this.bitmask = bitmask;
    }

    /**
     * 获取该效果在实体状态位中的单标志位掩码。
     *
     * @return 位掩码（如 {@code 0x01}、{@code 0x20}）
     */
    public int getBitmask() {
        return bitmask;
    }

    /**
     * 将多个效果合并为单个 byte 状态值。
     *
     * <p>对应写入 DataWatcher 索引 0 的 {@code byte} 值。</p>
     *
     * @param effects 效果集合，null 或空集返回 0
     * @return 合并后的状态位 byte 值
     */
    public static byte merge(Set<NpcEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return 0;
        }
        int flags = 0;
        for (NpcEffect effect : effects) {
            flags |= effect.bitmask;
        }
        return (byte) flags;
    }

    /**
     * 从 byte 状态值解析出效果集合。
     *
     * @param flags 实体状态位 byte 值
     * @return 解析出的效果集合（不可变），无任何效果时返回空集
     */
    public static Set<NpcEffect> parse(byte flags) {
        int bits = flags & 0xFF;
        if (bits == 0) {
            return Collections.unmodifiableSet(EnumSet.noneOf(NpcEffect.class));
        }
        EnumSet<NpcEffect> result = EnumSet.noneOf(NpcEffect.class);
        for (NpcEffect effect : values()) {
            if ((bits & effect.bitmask) != 0) {
                result.add(effect);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
