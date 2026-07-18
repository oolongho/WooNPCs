package com.oolongho.woonpc.action;

import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import org.bukkit.Sound;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * 向玩家播放音效的动作。
 *
 * <p>在玩家当前位置播放指定 Sound。构造时通过 {@link Sound#valueOf(String)} 解析音效名，
 * 解析失败回退到 {@link Sound#BLOCK_NOTE_BLOCK_HAT}，避免因配置错误导致动作链异常。</p>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: play_sound
 *   sound: "ENTITY_EXPERIENCE_ORB_PICKUP"
 *   volume: 1.0
 *   pitch: 1.0
 * </pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class PlaySoundAction extends NpcAction {

    /** 默认音效（Sound.valueOf 失败时使用） */
    private static final Sound FALLBACK_SOUND = Sound.BLOCK_NOTE_BLOCK_HAT;

    /** 要播放的音效 */
    private final Sound sound;

    /** 音量（0.0 - 1.0+） */
    private final float volume;

    /** 音高（0.5 - 2.0） */
    private final float pitch;

    /**
     * 构造音效动作。
     *
     * <p>soundName 经 {@code toUpperCase} 后用 {@link Sound#valueOf} 解析，
     * 解析失败（IllegalArgumentException 或 NullPointerException）时回退到
     * {@link Sound#BLOCK_NOTE_BLOCK_HAT}。</p>
     *
     * @param soundName 音效名（大小写不敏感），不可为 null
     * @param volume    音量
     * @param pitch     音高
     */
    public PlaySoundAction(String soundName, float volume, float pitch) {
        this.sound = resolveSound(soundName);
        this.volume = volume;
        this.pitch = pitch;
    }

    private static Sound resolveSound(String soundName) {
        Objects.requireNonNull(soundName, "soundName cannot be null");
        try {
            return Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return FALLBACK_SOUND;
        }
    }

    @Override
    public boolean execute(ActionContext context) {
        context.player().playSound(context.player().getLocation(), sound, volume, pitch);
        return true;
    }

    @Override
    public String typeId() {
        return "play_sound";
    }

    @Override
    public String argsSummary() {
        return sound.name() + " " + volume + " " + pitch;
    }
}
