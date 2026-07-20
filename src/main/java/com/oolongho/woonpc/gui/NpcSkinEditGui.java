package com.oolongho.woonpc.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.oolongho.woonpc.WooNPCs;
import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.api.SkinManager;
import com.oolongho.woonpc.skin.SkinData;
import com.oolongho.woonpc.storage.NpcStorage;
import com.oolongho.woonpc.util.Scheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * NPC 皮肤编辑 GUI（27 格，3 行布局）。
 *
 * <p>展示当前皮肤预览，提供以下编辑入口：</p>
 * <ul>
 *   <li>手动输入 texture / signature（聊天输入）</li>
 *   <li>从玩家名异步获取皮肤（{@link SkinManager}）</li>
 *   <li>重置为默认 Steve 皮肤</li>
 * </ul>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [返回][背景][背景][背景][当前皮肤][背景][背景][背景][背景]
 * Row 2 (9-17):  [输入texture][背景][背景][背景][输入signature][背景][背景][背景][背景]
 * Row 3 (18-26): [从玩家获取][背景][背景][背景][重置默认][背景][背景][背景][背景]
 * </pre>
 *
 * <h2>异步回调线程切换</h2>
 * <p>{@link SkinManager#getSkin} 的回调在异步线程执行，必须通过
 * {@link Scheduler#runSync} 切回主线程后才能调用
 * Bukkit API 与 NPC setter（数据包发送非线程安全）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcSkinEditGui extends GuiScreen {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int SIZE = 27;

    private final WooNPCs plugin;
    private final NpcManager npcManager;
    private final NpcStorage storage;
    private final SkinManager skinManager;
    private final GuiManager guiManager;
    private final ChatInputManager chatInputManager;
    private final Scheduler scheduler;
    private final UUID npcId;

    /**
     * 构造 NPC 皮肤编辑 GUI。
     *
     * @param plugin           插件实例
     * @param npcManager       NPC 管理器
     * @param storage          NPC 持久化存储
     * @param skinManager      皮肤管理器（异步获取玩家皮肤）
     * @param guiManager       GUI 管理器（返回导航）
     * @param chatInputManager 聊天输入管理器（texture / signature / 玩家名输入）
     * @param scheduler        调度器（异步皮肤回调切回主线程使用）
     * @param npcId            目标 NPC 的 UUID
     * @param parent           父级 GUI（通常为 NpcDetailGui）
     */
    public NpcSkinEditGui(@NotNull WooNPCs plugin,
                          @NotNull NpcManager npcManager,
                          @NotNull NpcStorage storage,
                          @NotNull SkinManager skinManager,
                          @NotNull GuiManager guiManager,
                          @NotNull ChatInputManager chatInputManager,
                          @NotNull Scheduler scheduler,
                          @NotNull UUID npcId,
                          @Nullable GuiScreen parent) {
        super("<dark_aqua>NPC 皮肤编辑", SIZE, parent);
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.skinManager = Objects.requireNonNull(skinManager, "skinManager cannot be null");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager cannot be null");
        this.chatInputManager = Objects.requireNonNull(chatInputManager, "chatInputManager cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.npcId = Objects.requireNonNull(npcId, "npcId cannot be null");
    }

    // ==================== 渲染入口 ====================

    @Override
    public void render() {
        Optional<Npc> opt = npcManager.getById(npcId);
        if (opt.isEmpty()) {
            renderNpcNotFound();
            return;
        }
        Npc npc = opt.get();
        SkinData skin = npc.getData().skin();

        // [0] 返回
        setButton(0, GuiButton.builder(Material.BOOK)
                .name("<yellow>返回")
                .lore(List.of("<gray>点击返回上一级"))
                .onClick(ctx -> guiManager.goBack(ctx.player()))
                .build());

        // [1-3, 5-8] 背景
        setButton(1, backgroundButton());
        setButton(2, backgroundButton());
        setButton(3, backgroundButton());
        setButton(5, backgroundButton());
        setButton(6, backgroundButton());
        setButton(7, backgroundButton());
        setButton(8, backgroundButton());

        // [4] 当前皮肤预览（PLAYER_HEAD，自定义皮肤时设置 PlayerProfile 显示真实头像）
        GuiButton.Builder headBuilder = GuiButton.builder(Material.PLAYER_HEAD)
                .name("<aqua>当前皮肤")
                .lore(List.of(
                        "<gray>texture: <aqua>" + preview(skin.texture()),
                        "<gray>signature: <aqua>" + preview(skin.signature()),
                        "<gray>状态: " + (skin.isDefault() ? "<red>默认皮肤" : "<green>自定义皮肤")
                ));
        if (!skin.isDefault()) {
            headBuilder.metaModifier(meta -> applyHeadProfile(meta, npc, skin));
        }
        setButton(4, headBuilder.build());

        // [9] 输入 texture（关闭 GUI → 聊天输入 → 重开）
        setButton(9, GuiButton.builder(Material.OAK_SIGN)
                .name("<aqua>输入 texture")
                .lore(List.of("<gray>点击输入皮肤 texture 值"))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    guiManager.closeGui(p);
                    String oldSignature = npc.getData().skin().signature();
                    chatInputManager.requestInput(p,
                            "<aqua>请输入 skin texture（长字符串），输入 cancel 取消",
                            ChatInputManager.InputType.SKIN_TEXTURE,
                            texture -> {
                                npc.setSkin(new SkinData(texture, oldSignature));
                                storage.save(npc);
                                p.sendMessage(MM.deserialize("<green>皮肤 texture 已更新。"));
                                guiManager.openGui(p, new NpcSkinEditGui(plugin, npcManager, storage,
                                        skinManager, guiManager, chatInputManager, scheduler, npcId, parent));
                            });
                })
                .build());

        // [10-12, 14-17] 背景
        setButton(10, backgroundButton());
        setButton(11, backgroundButton());
        setButton(12, backgroundButton());
        setButton(14, backgroundButton());
        setButton(15, backgroundButton());
        setButton(16, backgroundButton());
        setButton(17, backgroundButton());

        // [13] 输入 signature
        setButton(13, GuiButton.builder(Material.OAK_SIGN)
                .name("<aqua>输入 signature")
                .lore(List.of("<gray>点击输入皮肤 signature 值"))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    guiManager.closeGui(p);
                    String currentTexture = npc.getData().skin().texture();
                    chatInputManager.requestInput(p,
                            "<aqua>请输入 skin signature（长字符串），输入 cancel 取消",
                            ChatInputManager.InputType.SKIN_SIGNATURE,
                            signature -> {
                                npc.setSkin(new SkinData(currentTexture, signature));
                                storage.save(npc);
                                p.sendMessage(MM.deserialize("<green>皮肤 signature 已更新。"));
                                guiManager.openGui(p, new NpcSkinEditGui(plugin, npcManager, storage,
                                        skinManager, guiManager, chatInputManager, scheduler, npcId, parent));
                            });
                })
                .build());

        // [18] 从玩家获取（异步 SkinManager.getSkin → 主线程更新 NPC + 重开 GUI）
        setButton(18, GuiButton.builder(Material.COMPASS)
                .name("<aqua>从玩家获取皮肤")
                .lore(List.of("<gray>输入玩家名获取其皮肤"))
                .onClick(ctx -> {
                    Player p = ctx.player();
                    guiManager.closeGui(p);
                    chatInputManager.requestInput(p,
                            "<aqua>请输入玩家名（用于获取皮肤，输入 cancel 取消）",
                            ChatInputManager.InputType.PLAYER_NAME,
                            playerName -> {
                                p.sendMessage(MM.deserialize("<yellow>正在异步获取 <aqua>" + playerName + " <yellow>的皮肤..."));
                                // SkinManager 回调在异步线程执行，所有 Bukkit API / NPC 写操作必须切回玩家所在 region
                                // Folia 上 setSkin/openGui/save 等调用涉及玩家 API，需在玩家 region
                                skinManager.getSkin(playerName, skinData -> {
                                    scheduler.runAtEntity(p, () -> {
                                        // 切回主线程后再次校验 NPC 是否仍存在
                                        Optional<Npc> cur = npcManager.getById(npcId);
                                        if (cur.isEmpty()) {
                                            p.sendMessage(MM.deserialize("<red>NPC 已不存在，无法更新皮肤。"));
                                            return;
                                        }
                                        Npc current = cur.get();
                                        current.setSkin(skinData);
                                        storage.save(current);
                                        p.sendMessage(MM.deserialize("<green>皮肤已更新。"));
                                        guiManager.openGui(p, new NpcSkinEditGui(plugin, npcManager, storage,
                                                skinManager, guiManager, chatInputManager, scheduler, npcId, parent));
                                    });
                                });
                            });
                })
                .build());

        // [19-21] 背景
        setButton(19, backgroundButton());
        setButton(20, backgroundButton());
        setButton(21, backgroundButton());

        // [22] 重置默认
        setButton(22, GuiButton.builder(Material.STRUCTURE_VOID)
                .name("<red>重置默认皮肤")
                .lore(List.of("<gray>恢复为 Steve 默认皮肤"))
                .onClick(ctx -> {
                    npc.setSkin(SkinData.defaultSkin());
                    storage.save(npc);
                    ctx.player().sendMessage(MM.deserialize("<green>已重置为默认皮肤。"));
                    refresh();
                })
                .build());

        // [23-26] 背景
        setButton(23, backgroundButton());
        setButton(24, backgroundButton());
        setButton(25, backgroundButton());
        setButton(26, backgroundButton());
    }

    // ==================== NPC 不存在时的错误显示 ====================

    /**
     * 渲染 NPC 不存在的错误界面：slot 13 提示，slot 22 返回按钮。
     */
    private void renderNpcNotFound() {
        setButton(13, GuiButton.builder(Material.BARRIER)
                .name("<red>NPC 不存在")
                .lore(List.of(
                        "<gray>该 NPC 可能已被删除",
                        "<gray>请返回上一级刷新"
                ))
                .build());
        setButton(22, GuiButton.builder(Material.BOOK)
                .name("<yellow>返回")
                .lore(List.of("<gray>点击返回上一级"))
                .onClick(ctx -> guiManager.openGui(ctx.player(), parent))
                .build());
    }

    // ==================== 辅助方法 ====================

    /** 构造灰色玻璃背景按钮（空白名称） */
    private GuiButton backgroundButton() {
        return GuiButton.builder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .build();
    }

    /**
     * 将 NPC 的皮肤数据应用到 SkullMeta 的 PlayerProfile（Paper API）。
     * 复用自 NpcListGui / NpcDetailGui 的同名实现。
     */
    private void applyHeadProfile(ItemMeta meta, Npc npc, SkinData skin) {
        if (!(meta instanceof SkullMeta skullMeta)) {
            return;
        }
        PlayerProfile profile = Bukkit.createProfile(npc.getId(), npc.getName());
        String signature = skin.signature().isEmpty() ? null : skin.signature();
        profile.setProperty(new ProfileProperty("textures", skin.texture(), signature));
        skullMeta.setPlayerProfile(profile);
    }

    /** 字符串前 16 字符预览（空字符串显示 "&lt;空&gt;"） */
    private String preview(String value) {
        if (value == null || value.isEmpty()) {
            return "<空>";
        }
        return value.length() > 16 ? value.substring(0, 16) + "..." : value;
    }
}
