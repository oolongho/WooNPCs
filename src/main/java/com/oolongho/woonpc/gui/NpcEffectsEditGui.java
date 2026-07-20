package com.oolongho.woonpc.gui;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.npc.NpcEffect;
import com.oolongho.woonpc.storage.NpcStorage;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * NPC 效果编辑 GUI（27 格，3 行布局）。
 *
 * <p>提供 6 种实体效果（FIRE / SNEAKING / SPRINTING / SWIMMING / INVISIBLE / GLOWING）
 * 的启用切换、当前效果展示与一键全部清除。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [返回][背景][背景][背景][当前效果][背景][背景][背景][背景]
 * Row 2 (9-17):  [FIRE][SNEAKING][SPRINTING][SWIMMING][INVISIBLE][GLOWING][背景][背景][背景]
 * Row 3 (18-26): [全部清除][背景][背景][背景][背景][背景][背景][背景][背景]
 * </pre>
 *
 * <h2>切换逻辑</h2>
 * <p>左键单击效果按钮 → 复制当前 effects 到可变 {@link EnumSet} → toggle（已启用则移除，
 * 未启用则添加）→ {@code npc.setEffects(newSet)} + storage.save + refresh。
 * 启用时按钮显示附魔光辉。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcEffectsEditGui extends GuiScreen {

    private static final int SIZE = 27;

    /** 效果按钮槽位配置（按 slot 9-14 顺序与 NpcEffect 枚举声明顺序一致） */
    private static final NpcEffect[] EFFECT_BUTTONS = {
            NpcEffect.FIRE,         // slot 9
            NpcEffect.SNEAKING,     // slot 10
            NpcEffect.SPRINTING,     // slot 11
            NpcEffect.SWIMMING,     // slot 12
            NpcEffect.INVISIBLE,    // slot 13
            NpcEffect.GLOWING       // slot 14
    };

    /** 每种效果对应的展示材质（与 EFFECT_BUTTONS 索引一致） */
    private static final Material[] EFFECT_MATERIALS = {
            Material.FIRE,              // FIRE
            Material.LEATHER_BOOTS,     // SNEAKING
            Material.DIAMOND_BOOTS,     // SPRINTING
            Material.TRIDENT,           // SWIMMING
            Material.POTION,            // INVISIBLE
            Material.GLOWSTONE_DUST     // GLOWING
    };

    private final NpcManager npcManager;
    private final NpcStorage storage;
    private final GuiManager guiManager;
    private final UUID npcId;

    /**
     * 构造 NPC 效果编辑 GUI。
     *
     * @param npcManager       NPC 管理器
     * @param storage          NPC 持久化存储
     * @param guiManager       GUI 管理器（返回导航）
     * @param npcId            目标 NPC 的 UUID
     * @param parent           父级 GUI（通常为 NpcDetailGui）
     */
    public NpcEffectsEditGui(@NotNull NpcManager npcManager,
                             @NotNull NpcStorage storage,
                             @NotNull GuiManager guiManager,
                             @NotNull UUID npcId,
                             @Nullable GuiScreen parent) {
        super("<dark_aqua>NPC 效果编辑", SIZE, parent);
        this.npcManager = Objects.requireNonNull(npcManager, "npcManager cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager cannot be null");
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
        Set<NpcEffect> effects = npc.getData().effects();

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

        // [4] 当前效果（展示已启用效果列表）
        setButton(4, GuiButton.builder(Material.PAPER)
                .name("<aqua>当前效果 (<yellow>" + effects.size() + "<aqua>)")
                .lore(buildEffectListLore(effects))
                .build());

        // [9-14] 6 个效果切换按钮
        for (int i = 0; i < EFFECT_BUTTONS.length; i++) {
            NpcEffect effect = EFFECT_BUTTONS[i];
            boolean enabled = effects.contains(effect);
            GuiButton.Builder builder = GuiButton.builder(EFFECT_MATERIALS[i])
                    .name("<yellow>" + effect.name())
                    .lore(buildEffectToggleLore(enabled))
                    .onClick(ctx -> {
                        if (ctx.clickType() != ClickType.LEFT) {
                            return;
                        }
                        // NpcData.effects() 返回不可修改视图，必须复制到 EnumSet 才能 add/remove
                        EnumSet<NpcEffect> newSet = effects.isEmpty()
                                ? EnumSet.noneOf(NpcEffect.class)
                                : EnumSet.copyOf(effects);
                        if (enabled) {
                            newSet.remove(effect);
                        } else {
                            newSet.add(effect);
                        }
                        npc.setEffects(newSet);
                        storage.save(npc);
                        refresh();
                    });
            if (enabled) {
                builder.glow();
            }
            setButton(9 + i, builder.build());
        }

        // [15-17] 背景
        setButton(15, backgroundButton());
        setButton(16, backgroundButton());
        setButton(17, backgroundButton());

        // [18] 全部清除
        setButton(18, GuiButton.builder(Material.BARRIER)
                .name("<red>全部清除")
                .lore(List.of("<gray>清空所有效果"))
                .onClick(ctx -> {
                    npc.setEffects(Set.of());
                    storage.save(npc);
                    refresh();
                })
                .build());

        // [19-26] 背景
        for (int s = 19; s <= 26; s++) {
            setButton(s, backgroundButton());
        }
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

    /** 构造当前效果列表 lore：无效果显示 "&lt;gray&gt;无"，否则逐行展示已启用效果 */
    private List<String> buildEffectListLore(Set<NpcEffect> effects) {
        List<String> lore = new ArrayList<>();
        if (effects.isEmpty()) {
            lore.add("<gray>无");
        } else {
            for (NpcEffect e : effects) {
                lore.add("<gray>- <aqua>" + e.name());
            }
        }
        return lore;
    }

    /** 构造效果切换按钮 lore：操作提示 + 启用状态 */
    private List<String> buildEffectToggleLore(boolean enabled) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>左键: 切换启用/禁用");
        if (enabled) {
            lore.add("<green>已启用");
        }
        return lore;
    }
}
