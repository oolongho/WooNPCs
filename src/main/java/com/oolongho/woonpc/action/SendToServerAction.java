package com.oolongho.woonpc.action;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.actions.ActionContext;
import com.oolongho.woonpc.api.actions.NpcAction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 将玩家发送到目标服务器的动作（BungeeCord/Velocity 跨服）。
 *
 * <p>通过 BungeeCord Plugin Message 通道发送 {@code Connect} 子通道请求。
 * BungeeCord 与 Velocity（兼容模式）均支持 {@code "BungeeCord"} 通道的 {@code Connect} 指令。</p>
 *
 * <h2>通道注册约定</h2>
 * <p>本动作不注册 BungeeCord 通道，由主类 {@link WooNPCs#onEnable} 通过
 * {@code getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord")}
 * 注册。</p>
 *
 * <p>执行时若检测到玩家未监听 BungeeCord 通道（即主类未注册），记录 warning 并跳过
 * （返回 true，避免动作链异常中止）。正常装配后此分支不会触发。</p>
 *
 * <h2>示例配置</h2>
 * <pre>
 *   type: send_to_server
 *   server: "survival"
 * </pre>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class SendToServerAction extends NpcAction {

    /** BungeeCord 通道名 */
    private static final String BUNGEE_CHANNEL = "BungeeCord";

    /** Connect 子通道指令 */
    private static final String CONNECT_SUBCHANNEL = "Connect";

    /** 目标服务器名 */
    private final String serverName;

    /**
     * 构造跨服动作。
     *
     * @param serverName 目标服务器名，不可为 null
     */
    public SendToServerAction(String serverName) {
        this.serverName = Objects.requireNonNull(serverName, "serverName cannot be null");
    }

    @Override
    public boolean execute(ActionContext context) {
        Player player = context.player();
        WooNPCs plugin = context.plugin();

        if (!player.getListeningPluginChannels().contains(BUNGEE_CHANNEL)) {
            plugin.getLogger().warning("SendToServer: BungeeCord channel not registered, skipping");
            return true;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(CONNECT_SUBCHANNEL);
        out.writeUTF(serverName);
        player.sendPluginMessage(plugin, BUNGEE_CHANNEL, out.toByteArray());
        return true;
    }

    @Override
    public String typeId() {
        return "send_to_server";
    }

    @Override
    public Map<String, String> serialize() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("server", serverName);
        return map;
    }

    @Override
    public String argsSummary() {
        return serverName;
    }
}
