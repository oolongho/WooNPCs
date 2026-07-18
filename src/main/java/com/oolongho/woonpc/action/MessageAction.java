package com.oolongho.woonpc.action;

import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * 向玩家发送 MiniMessage 格式化消息的动作。
 *
 * <p>使用 Adventure {@link MiniMessage} 解析消息字符串，支持 {@code <gradient>}、
 * {@code <click>}、{@code <hover>} 等富文本标签。占位符替换在 MiniMessage 解析前进行。</p>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: message
 *   message: "&lt;green&gt;你好 {player}！&lt;/green&gt;"
 * </pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class MessageAction extends NpcAction {

    /** MiniMessage 格式的消息（含占位符，执行前替换） */
    private final String message;

    /**
     * 构造消息动作。
     *
     * @param message MiniMessage 字符串，不可为 null
     */
    public MessageAction(String message) {
        this.message = Objects.requireNonNull(message, "message cannot be null");
    }

    @Override
    public boolean execute(ActionContext context) {
        Component component = MiniMessage.miniMessage().deserialize(context.replacePlaceholders(message));
        context.player().sendMessage(component);
        return true;
    }

    @Override
    public String typeId() {
        return "message";
    }

    @Override
    public String argsSummary() {
        return message;
    }
}
