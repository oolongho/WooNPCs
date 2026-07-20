package com.oolongho.woonpc.action;

import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 以控制台身份执行命令的动作。
 *
 * <p>将配置中的命令字符串经 {@link ActionContext#replacePlaceholders(String)} 替换占位符后，
 * 通过 {@link Bukkit#dispatchCommand} 以 {@link Bukkit#getConsoleSender()} 身份执行。</p>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: console_command
 *   command: "say Hello {player}!"
 * </pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class ConsoleCommandAction extends NpcAction {

    /** 要执行的命令（含占位符，执行前替换） */
    private final String command;

    /**
     * 构造控制台命令动作。
     *
     * @param command 命令字符串，不可为 null（可为空串，但通常无意义）
     */
    public ConsoleCommandAction(String command) {
        this.command = Objects.requireNonNull(command, "command cannot be null");
    }

    @Override
    public boolean execute(ActionContext context) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), context.replacePlaceholders(command));
        return true;
    }

    @Override
    public String typeId() {
        return "console_command";
    }

    @Override
    public Map<String, String> serialize() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("command", command);
        return map;
    }

    @Override
    public String argsSummary() {
        return command;
    }
}
