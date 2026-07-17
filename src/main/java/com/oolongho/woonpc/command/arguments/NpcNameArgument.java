package com.oolongho.woonpc.command.arguments;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcManager;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * NPC 名称参数解析器。
 *
 * <p>从命令参数中解析 NPC 名，通过 {@link NpcManager#getByName} 查找。
 * 同时提供 tab 补全（基于已注册 NPC 名）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcNameArgument {

    private NpcNameArgument() {
    }

    /**
     * 解析 NPC 名为对应的 Npc 实例。
     *
     * @param manager NpcManager
     * @param name    NPC 名
     * @return 包含对应 NPC 的 Optional，不存在或入参为空返回 empty
     */
    public static Optional<Npc> parse(NpcManager manager, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return manager.getByName(name);
    }

    /**
     * Tab 补全 NPC 名。
     *
     * @param manager NpcManager
     * @param prefix  当前输入前缀（大小写不敏感）
     * @return 匹配前缀的 NPC 名列表
     */
    public static List<String> complete(NpcManager manager, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (Npc npc : manager.getAll()) {
            String name = npc.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(name);
            }
        }
        return result;
    }
}
