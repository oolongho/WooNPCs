package com.oolongho.woonpc.command.arguments;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 皮肤来源参数解析器。
 *
 * <p>解析命令中的皮肤来源字符串，支持三种格式：</p>
 * <ul>
 *   <li>{@code default}（或 {@code none}/{@code steve}）→ 默认 Steve 皮肤</li>
 *   <li>{@code player:<name>} 或直接 {@code <name>} → 玩家名（由 SkinManager 异步获取）</li>
 *   <li>{@code texture:<base64>} → 直接使用纹理值（无签名）</li>
 * </ul>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class SkinSourceArgument {

    /** 皮肤来源类型 */
    public enum Type {
        /** 默认 Steve 皮肤 */
        DEFAULT,
        /** 玩家名（由 SkinManager 异步获取） */
        PLAYER,
        /** 直接纹理值（Base64） */
        TEXTURE
    }

    /** 解析结果 */
    public record SkinSource(Type type, String value) {
    }

    private SkinSourceArgument() {
    }

    /**
     * 解析皮肤来源字符串。
     *
     * @param input 输入字符串
     * @return 解析结果，输入为空返回 null
     */
    public static @Nullable SkinSource parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("default") || lower.equals("none") || lower.equals("steve")) {
            return new SkinSource(Type.DEFAULT, "");
        }
        if (lower.startsWith("player:")) {
            String name = trimmed.substring(7);
            return name.isBlank() ? null : new SkinSource(Type.PLAYER, name);
        }
        if (lower.startsWith("texture:")) {
            String texture = trimmed.substring(8);
            return texture.isBlank() ? null : new SkinSource(Type.TEXTURE, texture);
        }
        // 默认视为玩家名
        return new SkinSource(Type.PLAYER, trimmed);
    }

    /**
     * Tab 补全皮肤来源关键字。
     *
     * @param prefix 当前输入前缀
     * @return 匹配的补全建议
     */
    public static List<String> complete(String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        if ("default".startsWith(lower)) result.add("default");
        if ("player:".startsWith(lower)) result.add("player:");
        if ("texture:".startsWith(lower)) result.add("texture:");
        return result;
    }
}
