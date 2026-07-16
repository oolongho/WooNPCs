package com.oolongho.woonpc;

import com.oolongho.woonpc.hook.WooHologramsHook;
import com.oolongho.woonpc.skin.SkinManagerImpl;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WooNPCs 插件主类
 * <p>
 * 负责插件生命周期管理与各组件装配。

 * <p>当前为 Task 1 阶段的空骨架，仅完成实例装配与日志输出，
 * 各 manager 的初始化与清理逻辑将在后续 Task 中填充。</p>
 *
 * @author oolongho
 */
public class WooNPCs extends JavaPlugin {

    /** 插件单例实例 */
    private static WooNPCs instance;

    @Override
    public void onEnable() {
        instance = this;

        // 输出启用日志
        String version = getPluginMeta().getVersion();
        getLogger().info(() -> "WooNPCs v" + version + " 已启用");

        // TODO: 后续 Task 在此装配 ConfigManager / NpcManager / Tracker / Command / Listener 等组件
    }

    @Override
    public void onDisable() {
        // 关闭皮肤系统（若已初始化）：停止队列轮询 + 关闭执行器
        SkinManagerImpl.shutdown();

        // 关闭 WooHolograms Hook：销毁所有由本插件创建的 NPC 全息
        WooHologramsHook.shutdown();

        // TODO: 后续 Task 在此执行其他 manager 的清理与持久化保存逻辑

        getLogger().info("WooNPCs 已禁用");
        instance = null;
    }

    /**
     * 获取插件实例
     *
     * @return 插件单例
     */
    public static WooNPCs getInstance() {
        return instance;
    }
}
