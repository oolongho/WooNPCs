package com.oolongho.woonpc;

import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.api.SkinManager;
import com.oolongho.woonpc.api.actions.ActionManager;
import com.oolongho.woonpc.command.MainCommand;
import com.oolongho.woonpc.gui.ChatInputManager;
import com.oolongho.woonpc.gui.GuiManager;
import com.oolongho.woonpc.hook.WooHologramsHook;
import com.oolongho.woonpc.manager.ActionManagerImpl;
import com.oolongho.woonpc.manager.NpcManagerImpl;
import com.oolongho.woonpc.nms.NmsAdapter;
import com.oolongho.woonpc.nms.NmsAdapterFactory;
import com.oolongho.woonpc.skin.SkinManagerImpl;
import com.oolongho.woonpc.storage.NpcStorage;
import com.oolongho.woonpc.storage.YamlNpcStorage;
import com.oolongho.woonpc.util.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * WooNPCs 插件主类
 * <p>
 * 负责插件生命周期管理与各组件装配。
 *
 * <p>Task 10 阶段完成 GUI 系统所需的最小装配：NMS 适配器 / NpcManager /
 * NpcStorage / ActionManager / SkinManager / GuiManager / ChatInputManager /
 * MainCommand + 注册 Listener + 加载持久化 NPC 数据。
 * 其他 Listener（NpcInteractListener / PlayerTrackerListener 等）与
 * AutoSaveTask / BungeeCord 通道等将在 Task 18 完整装配。</p>
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

        // 保存默认配置（首次启动时从 jar 释放 config.yml）
        saveDefaultConfig();

        // 装配组件（按依赖顺序）
        NmsAdapter adapter;
        try {
            adapter = NmsAdapterFactory.createAdapter(VersionUtil.getServerVersion());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "NMS 适配器初始化失败，插件将禁用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        NpcManager npcManager = new NpcManagerImpl(adapter);
        NpcStorage storage = new YamlNpcStorage(this);
        ActionManager actionManager = new ActionManagerImpl(this);
        SkinManager skinManager = SkinManagerImpl.getInstance();
        GuiManager guiManager = new GuiManager(this);
        ChatInputManager chatInputManager = new ChatInputManager(this);

        // 加载持久化的 NPC 数据（在命令可用前完成，保证 /woonpc list 显示历史数据）
        loadPersistedNpcs(storage, npcManager);

        // 注册 MainCommand
        MainCommand cmd = new MainCommand(this, npcManager, storage, actionManager, skinManager, guiManager, chatInputManager);
        if (getCommand("woonpc") != null) {
            getCommand("woonpc").setExecutor(cmd);
            getCommand("woonpc").setTabCompleter(cmd);
        } else {
            getLogger().severe("命令 'woonpc' 未在 paper-plugin.yml 中注册");
        }

        // 注册 Listener（GUI 事件必需）
        Bukkit.getPluginManager().registerEvents(guiManager, this);
        Bukkit.getPluginManager().registerEvents(chatInputManager, this);

        // TODO Task 18: 装配其他 Listener（NpcInteractListener / PlayerTrackerListener / WorldLoadListener）
        // TODO Task 18: 注册 BungeeCord 通道（SendToServerAction 使用）
        // TODO Task 18: 启动 AutoSaveTask（storage 自动保存）
    }

    /**
     * 从 storage 加载所有持久化的 NPC 数据并通过 npcManager 重建实例。
     *
     * <p>加载失败的 NPC 会被 storage 跳过并记录警告。create 失败时记录日志并继续（不阻塞后续 NPC 加载）。</p>
     *
     * @param storage    NPC 存储器
     * @param npcManager NPC 管理器
     */
    private void loadPersistedNpcs(NpcStorage storage, NpcManager npcManager) {
        try {
            List<NpcData> dataList = storage.loadAll();
            for (NpcData data : dataList) {
                try {
                    npcManager.create(data);
                } catch (RuntimeException e) {
                    getLogger().warning("加载 NPC '" + data.name() + "' 失败: " + e.getMessage());
                }
            }
            if (!dataList.isEmpty()) {
                getLogger().info("已加载 " + dataList.size() + " 个 NPC");
            }
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "NPC 数据加载失败", e);
        }
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
