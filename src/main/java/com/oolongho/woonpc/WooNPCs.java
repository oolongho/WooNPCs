package com.oolongho.woonpc;

import com.oolongho.woonpc.api.NpcData;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.api.SkinManager;
import com.oolongho.woonpc.api.WooNPCsAPI;
import com.oolongho.woonpc.api.actions.ActionManager;
import com.oolongho.woonpc.command.MainCommand;
import com.oolongho.woonpc.config.MessageManager;
import com.oolongho.woonpc.gui.ChatInputManager;
import com.oolongho.woonpc.gui.GuiManager;
import com.oolongho.woonpc.hook.PlaceholderHook;
import com.oolongho.woonpc.hook.WooHologramsHook;
import com.oolongho.woonpc.listener.NpcInteractListener;
import com.oolongho.woonpc.listener.PlayerTrackerListener;
import com.oolongho.woonpc.listener.WorldLoadListener;
import com.oolongho.woonpc.manager.ActionManagerImpl;
import com.oolongho.woonpc.manager.NpcManagerImpl;
import com.oolongho.woonpc.nms.NmsAdapterFactory;
import com.oolongho.woonpc.skin.SkinManagerImpl;
import com.oolongho.woonpc.storage.ActionStorage;
import com.oolongho.woonpc.storage.AutoSaveTask;
import com.oolongho.woonpc.storage.NpcStorage;
import com.oolongho.woonpc.storage.YamlActionStorage;
import com.oolongho.woonpc.storage.YamlNpcStorage;
import com.oolongho.woonpc.tracker.LookTracker;
import com.oolongho.woonpc.tracker.VisibilityTracker;
import com.oolongho.woonpc.util.DebugManager;
import com.oolongho.woonpc.util.PlaceholderUtil;
import com.oolongho.woonpc.util.Scheduler;
import com.oolongho.woonpc.util.Schedulers;
import com.oolongho.woonpc.util.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * WooNPCs 插件主类
 * <p>
 * 负责插件生命周期管理与各组件装配。
 *
 * <p>装配顺序：
 * <ol>
 *   <li>NMS 适配器（按服务端版本）</li>
 *   <li>Scheduler 单例（按平台 Paper/Folia 自动选择）</li>
 *   <li>NpcManager / NpcStorage / ActionStorage / ActionManager / SkinManager / GuiManager / ChatInputManager</li>
 *   <li>加载持久化 NPC + actions（触发 NpcCreateEvent，由 tracker 监听）</li>
 *   <li>启动 VisibilityTracker / LookTracker（在 NPC 加载后注册监听并扫描现有 NPC）</li>
 *   <li>注册 NpcInteractListener / PlayerTrackerListener / WorldLoadListener</li>
 *   <li>注册 MainCommand</li>
 *   <li>注册 GuiManager / ChatInputManager 监听</li>
 *   <li>注册 BungeeCord 通道（SendToServerAction 使用）</li>
 *   <li>启动 AutoSaveTask（多回调：NPC 数据 + actions 数据）</li>
 * </ol>
 * </p>
 *
 * @author oolongho
 */
public class WooNPCs extends JavaPlugin {

    /** 插件单例实例 */
    private static WooNPCs instance;

    /** 持有引用以便 onDisable 时清理（避免提前 GC 导致任务泄漏） */
    private AutoSaveTask autoSaveTask;
    private VisibilityTracker visibilityTracker;
    private LookTracker lookTracker;
    private NpcInteractListener npcInteractListener;
    private NpcManager npcManager;
    private SkinManager skinManager;
    private NpcStorage storage;
    private ActionStorage actionStorage;
    private ActionManager actionManager;
    private MessageManager messageManager;
    private PlaceholderHook placeholderHook;
    /** GUI 与聊天输入管理器：onEnable 注册 Listener，onDisable 显式注销 */
    private GuiManager guiManager;
    private ChatInputManager chatInputManager;
    /** 调度器：reload 时重建 trackers 复用 */
    private Scheduler scheduler;

    @Override
    public void onEnable() {
        instance = this;

        String version = getPluginMeta().getVersion();
        getLogger().info(() -> "WooNPCs v" + version + " 已启用"
                + (Schedulers.FOLIA ? "（Folia 平台）" : "（Paper 平台）"));

        // 保存默认配置（首次启动时从 jar 释放 config.yml）
        saveDefaultConfig();

        // ==================== 1. NMS 适配器 ====================
        // 调用 createAdapter 触发副作用：若服务端版本不支持则抛异常，导致插件禁用
        try {
            NmsAdapterFactory.createAdapter(VersionUtil.getServerVersion());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "NMS 适配器初始化失败，插件将禁用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ==================== 2. Scheduler 单例 ====================
        this.scheduler = Schedulers.create(this);

        // ==================== 3. 业务组件装配 ====================
        this.npcManager = new NpcManagerImpl();
        this.storage = new YamlNpcStorage(this);
        this.actionStorage = new YamlActionStorage(this);
        this.actionManager = new ActionManagerImpl(this, scheduler);
        this.messageManager = new MessageManager(this);
        this.skinManager = SkinManagerImpl.getInstance();
        this.guiManager = new GuiManager(this);
        this.chatInputManager = new ChatInputManager(this, scheduler);

        // 初始化 DebugManager（reloadConfig 后立即可用）
        DebugManager.init(getLogger(), getConfig().getBoolean("settings.debug", false));

        // ==================== 4. 加载持久化 NPC + actions ====================
        // 加载会在 npcManager.create 时触发 NpcCreateEvent，但此时 tracker 尚未注册为 Listener，
        // 因此本阶段的 create 不会触发 tracker 的 onNpcCreate。tracker.start() 会遍历现有 NPC 补注册。
        loadPersistedNpcs(this.storage, this.npcManager);
        loadPersistedActions(this.actionStorage, this.actionManager);

        // ==================== 5. 启动 trackers（在 NPC 加载后） ====================
        long trackerInterval = getConfig().getLong("tracker.tracker-interval", 20L);
        long lookInterval = getConfig().getLong("tracker.look-tracker-interval", 2L);
        this.visibilityTracker = new VisibilityTracker(this, this.npcManager, scheduler, trackerInterval);
        this.lookTracker = new LookTracker(this, this.npcManager, scheduler, lookInterval);
        this.visibilityTracker.start();
        this.lookTracker.start();

        // ==================== 6. 注册 Listener ====================
        this.npcInteractListener = new NpcInteractListener(this, this.npcManager, scheduler);
        this.npcInteractListener.register();
        // ActionManager 监听 NpcDeleteEvent 自动清理 pendingTasks（防御外部直接调用 npcManager.remove）
        Bukkit.getPluginManager().registerEvents((Listener) this.actionManager, this);
        Bukkit.getPluginManager().registerEvents(
                new PlayerTrackerListener(this, this.visibilityTracker, scheduler), this);
        Bukkit.getPluginManager().registerEvents(
                new WorldLoadListener(this, this.visibilityTracker, scheduler), this);

        // ==================== 7. 注册 MainCommand ====================
        MainCommand cmd = new MainCommand(this, this.npcManager, this.storage, this.actionManager,
                this.skinManager, this.guiManager, this.chatInputManager, this.scheduler, this.messageManager);
        if (getCommand("woonpc") != null) {
            getCommand("woonpc").setExecutor(cmd);
            getCommand("woonpc").setTabCompleter(cmd);
        } else {
            getLogger().severe("命令 'woonpc' 未在 plugin.yml 中注册");
        }

        // ==================== 8. 注册 GUI 监听 ====================
        Bukkit.getPluginManager().registerEvents(this.guiManager, this);
        Bukkit.getPluginManager().registerEvents(this.chatInputManager, this);

        // ==================== 9. 注册 BungeeCord 通道（SendToServerAction 使用） ====================
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        // ==================== 10. PlaceholderAPI 扩展注册 + 启动 AutoSaveTask ====================
        PlaceholderUtil.refresh();
        if (PlaceholderUtil.isEnabled()) {
            this.placeholderHook = new PlaceholderHook(this, this.npcManager);
            this.placeholderHook.register();
            getLogger().info("PlaceholderAPI 扩展已注册 (%woonpc_<id>_<field>%)");
        }

        this.autoSaveTask = new AutoSaveTask(this, this.scheduler,
                this.storage::saveAll,
                () -> this.actionStorage.saveAll(this.actionManager.serializeAll()));
        this.autoSaveTask.startFromConfig();

        // ==================== 11. 暴露公共 API 门面 ====================
        // 必须在所有组件装配完成后调用，否则外部调用可能拿到 null
        WooNPCsAPI.initialize(this);
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

    /**
     * 从 storage 加载所有持久化的动作数据并通过 actionManager 装载。
     *
     * <p>加载时清空现有内存状态后全量重建。未注册的 typeId 对应的动作会被跳过并记录 warning。
     * 加载失败的 NPC（在 loadPersistedNpcs 中跳过）所对应的 actions 仍会被加载，
     * 但因为内存中无对应 NPC，这些 actions 会成为孤儿数据（下次 saveAll 时被清除）。</p>
     *
     * @param storage       动作存储器
     * @param actionManager 动作管理器
     */
    private void loadPersistedActions(ActionStorage storage, ActionManager actionManager) {
        try {
            java.util.Map<String, Object> data = storage.loadAll();
            if (data.isEmpty()) {
                return;
            }
            actionManager.loadAll(data);
            getLogger().info("已加载 actions 数据");
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "动作数据加载失败", e);
        }
    }

    @Override
    public void onDisable() {
        // 0. 立即关闭公共 API 门面：外部调用将抛 IllegalStateException 而非拿到 null
        WooNPCsAPI.shutdown();

        // 1. 停止 AutoSaveTask
        if (autoSaveTask != null) {
            autoSaveTask.stop();
        }

        // 2. 停止 trackers（取消所有 per-NPC timer + 注销监听）
        if (visibilityTracker != null) {
            visibilityTracker.shutdown();
        }
        if (lookTracker != null) {
            lookTracker.shutdown();
        }

        // 3. 注销交互监听器（移除所有玩家的 Netty handler）
        if (npcInteractListener != null) {
            npcInteractListener.unregister();
        }

        // 4. 注销 GUI / ChatInput / ActionManager 监听器（与 onEnable 注册对称）
        if (guiManager != null) {
            HandlerList.unregisterAll(guiManager);
        }
        if (chatInputManager != null) {
            HandlerList.unregisterAll(chatInputManager);
        }
        if (actionManager instanceof org.bukkit.event.Listener listener) {
            HandlerList.unregisterAll(listener);
        }

        // 5. 关闭皮肤系统：停止队列轮询 + 关闭执行器
        SkinManagerImpl.shutdown();

        // 6. 关闭 WooHolograms Hook：销毁所有由本插件创建的 NPC 全息
        WooHologramsHook.shutdown();

        // 7. 最后一次保存（确保关闭前的内存修改落盘）
        if (storage != null) {
            try {
                storage.saveAll();
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "关闭时保存 NPC 数据失败", e);
            }
        }
        if (actionStorage != null && actionManager != null) {
            try {
                actionStorage.saveAll(actionManager.serializeAll());
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "关闭时保存动作数据失败", e);
            }
        }

        // 8. 注销 BungeeCord 通道
        try {
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
        } catch (RuntimeException ignored) {
            // 通道未注册或已注销，忽略
        }

        // 9. 注销 PlaceholderAPI 扩展（自动从 PAPI 注册表移除）
        if (placeholderHook != null) {
            try {
                placeholderHook.unregister();
            } catch (RuntimeException ignored) {
                // PAPI 已卸载或扩展未注册，忽略
            }
        }

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

    /**
     * 获取消息管理器。
     *
     * @return 消息管理器，未装配时返回 null（在 onEnable 完成前调用）
     */
    public MessageManager getMessageManager() {
        return messageManager;
    }

    /**
     * 获取 NPC 存储器。
     *
     * @return NPC 存储器，未装配时返回 null
     */
    public NpcStorage getStorage() {
        return storage;
    }

    /**
     * 获取 NPC 管理器。
     *
     * @return NPC 管理器，未装配时返回 null
     */
    public NpcManager getNpcManager() {
        return npcManager;
    }

    /**
     * 获取皮肤管理器。
     *
     * @return 皮肤管理器，未装配时返回 null
     */
    public SkinManager getSkinManager() {
        return skinManager;
    }

    /**
     * 获取动作管理器。
     *
     * @return 动作管理器，未装配时返回 null
     */
    public ActionManager getActionManager() {
        return actionManager;
    }

    // ==================== 配置热重载支持 ====================

    /**
     * 完整重载流程：保存数据 → 重载配置 → 刷新各依赖配置的组件。
     *
     * <p>统一入口，供 {@code MainCommand.reload} 与 {@code NpcListGui} 重载按钮共用，
     * 保证两条重载路径行为一致。调用顺序：
     * <ol>
     *   <li>{@link #saveAllData()}：保存内存中的 NPC + actions 数据（避免重载后崩溃丢失最近修改）</li>
     *   <li>{@link #reloadConfig()}：重载 config.yml</li>
     *   <li>{@code messageManager.reload()}：重载语言文件</li>
     *   <li>{@code DebugManager.reload(...)}：刷新 debug 开关</li>
     *   <li>{@code PlaceholderUtil.refresh()}：刷新 PlaceholderAPI 状态</li>
     *   <li>{@link #reloadTrackers()}：按新 interval 重建 VisibilityTracker / LookTracker</li>
     *   <li>{@link #reloadAutoSave()}：按新 interval 重建 AutoSaveTask</li>
     * </ol>
     * </p>
     */
    public void reloadAll() {
        saveAllData();
        reloadConfig();
        messageManager.reload();
        DebugManager.reload(getConfig().getBoolean("settings.debug", false));
        PlaceholderUtil.refresh();
        reloadTrackers();
        reloadAutoSave();
    }

    /**
     * 重载 trackers：按最新 config 中的 {@code tracker.tracker-interval} 与
     * {@code tracker.look-tracker-interval} 重建 VisibilityTracker / LookTracker。
     *
     * <p>调用流程：先 shutdown 旧实例（cancel 所有 per-NPC timer + 注销 Listener），
     * 再用新 interval 构造 + start（重新注册 Listener + 为所有现有 NPC 注册 timer）。
     * 由 {@link #reloadAll()} 在重载配置后调用。</p>
     */
    public void reloadTrackers() {
        if (visibilityTracker != null) {
            visibilityTracker.shutdown();
        }
        if (lookTracker != null) {
            lookTracker.shutdown();
        }
        long trackerInterval = getConfig().getLong("tracker.tracker-interval", 20L);
        long lookInterval = getConfig().getLong("tracker.look-tracker-interval", 2L);
        this.visibilityTracker = new VisibilityTracker(this, this.npcManager, this.scheduler, trackerInterval);
        this.lookTracker = new LookTracker(this, this.npcManager, this.scheduler, lookInterval);
        this.visibilityTracker.start();
        this.lookTracker.start();
    }

    /**
     * 重载 AutoSaveTask：按最新 config 中的 {@code settings.auto-save-interval} 重建任务。
     *
     * <p>调用流程：先 stop 旧任务，再以新 interval 从 config 启动新任务。
     * 由 {@link #reloadAll()} 在重载配置后调用。</p>
     */
    public void reloadAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.stop();
        }
        this.autoSaveTask = new AutoSaveTask(this, this.scheduler,
                this.storage::saveAll,
                () -> this.actionStorage.saveAll(this.actionManager.serializeAll()));
        this.autoSaveTask.startFromConfig();
    }

    /**
     * 立即保存全部持久化数据：NPC 数据 + actions 数据。
     *
     * <p>供 {@link #reloadAll()} 在重载配置前调用，确保用户最近的内存修改
     * （NPC 字段变更、action add/remove）落盘，避免服务器在下次 AutoSaveTask
     * 触发前崩溃导致丢失。</p>
     *
     * <p>内部捕获 {@link RuntimeException} 仅记录 warning，不向上抛出（保存失败不阻塞 reload 流程）。</p>
     */
    public void saveAllData() {
        if (storage != null) {
            try {
                storage.saveAll();
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "保存 NPC 数据失败", e);
            }
        }
        if (actionStorage != null && actionManager != null) {
            try {
                actionStorage.saveAll(actionManager.serializeAll());
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "保存动作数据失败", e);
            }
        }
    }
}
