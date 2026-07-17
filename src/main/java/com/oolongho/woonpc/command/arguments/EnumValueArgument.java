package com.oolongho.woonpc.command.arguments;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 通用枚举参数解析器。
 *
 * <p>解析命令中的枚举值（不区分大小写），并提供 tab 补全。
 * 适用于 {@link com.oolongho.woonpc.npc.GlowingColor}、
 * {@link com.oolongho.woonpc.npc.NpcPose}、
 * {@link com.oolongho.woonpc.npc.NpcEffect}、
 * {@link com.oolongho.woonpc.npc.NpcEquipmentSlot} 等。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class EnumValueArgument {

    private EnumValueArgument() {
    }

    /**
     * 解析枚举值（不区分大小写）。
     *
     * @param enumClass 枚举类型
     * @param input     输入字符串
     * @param <E>       枚举泛型
     * @return 包含对应枚举值的 Optional，无法解析或入参为空返回 empty
     */
    public static <E extends Enum<E>> Optional<E> parse(Class<E> enumClass, String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(enumClass, input.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Tab 补全枚举值（小写形式）。
     *
     * @param enumClass 枚举类型
     * @param prefix    当前输入前缀（大小写不敏感）
     * @param <E>       枚举泛型
     * @return 匹配前缀的枚举名列表（小写）
     */
    public static <E extends Enum<E>> List<String> complete(Class<E> enumClass, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (E e : enumClass.getEnumConstants()) {
            String name = e.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(lower)) {
                result.add(name);
            }
        }
        return result;
    }
}
