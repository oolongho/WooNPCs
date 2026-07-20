package com.oolongho.woonpc.gui;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.npc.NpcEquipmentSlot;
import com.oolongho.woonpc.storage.NpcStorage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * NPC 装备编辑 GUI（27 格，3 行布局，拖物品模式）。
 *
 * <p>提供六槽位（HEAD/CHEST/LEGS/BOOTS/MAINHAND/OFFHAND）的装备编辑：
 * 玩家将光标物品左键放入装备槽位、右键清空、Shift+点击克隆到背包。
 * 装备槽位由 {@link GuiScreen#markItemSlot} 标记，{@link GuiManager#onInventoryClick}
 * 特殊处理光标写入，并通过 {@link #onItemSlotClick} 钩子同步 NPC 装备数据。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [返回][背景][背景][背景][操作说明][背景][背景][背景][背景]
 * Row 2 (9-17):  [头盔][胸甲][护腿][靴子][主手][副手][背景][背景][背景]
 * Row 3 (18-26): [清空全部][背景][背景][背景][背景][背景][背景][背景][背景]
 * </pre>
 *
 * <h2>编辑模式（拖物品）</h2>
 * <ul>
 *   <li>左键/中键装备槽：将光标物品写入对应槽位（覆盖原物品）</li>
 *   <li>右键装备槽：清空该槽位</li>
 *   <li>Shift+点击装备槽：克隆装备到玩家背包（不修改 NPC 装备）</li>
 *   <li>清空全部：将 equipment 设为空映射</li>
 * </ul>
 *
 * <h2>装备图标显示</h2>
 * <p>若槽位已有装备：直接显示装备自身的 ItemStack（保留 displayName/CustomModelData/附魔等全部 NBT）；
 * 若槽位无装备：使用默认 Material（如 DIAMOND_HELMET）作为占位预览。</p>
 *
 * <p>NpcData.equipment() 返回不可修改视图，修改前需复制到 {@link LinkedHashMap}。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcEquipmentEditGui extends GuiScreen {

    private static final int SIZE = 27;

    /** slot 9-14 对应的装备枚举（按展示顺序 HEAD/CHEST/LEGS/BOOTS/MAINHAND/OFFHAND） */
    private static final List<NpcEquipmentSlot> SLOT_ORDER = List.of(
            NpcEquipmentSlot.HEAD,
            NpcEquipmentSlot.CHEST,
            NpcEquipmentSlot.LEGS,
            NpcEquipmentSlot.BOOTS,
            NpcEquipmentSlot.MAINHAND,
            NpcEquipmentSlot.OFFHAND
    );

    private final NpcManager npcManager;
    private final NpcStorage storage;
    private final GuiManager guiManager;
    private final UUID npcId;

    /**
     * 构造 NPC 装备编辑 GUI。
     *
     * @param npcManager       NPC 管理器
     * @param storage          NPC 持久化存储
     * @param guiManager       GUI 管理器（返回导航）
     * @param npcId            目标 NPC 的 UUID
     * @param parent           父级 GUI（通常为 NpcDetailGui）
     */
    public NpcEquipmentEditGui(@NotNull NpcManager npcManager,
                               @NotNull NpcStorage storage,
                               @NotNull GuiManager guiManager,
                               @NotNull UUID npcId,
                               @Nullable GuiScreen parent) {
        super("<dark_aqua>NPC 装备编辑", SIZE, parent);
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
        Map<NpcEquipmentSlot, ItemStack> equipment = npc.getData().equipment();

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

        // [4] 操作说明
        setButton(4, GuiButton.builder(Material.PAPER)
                .name("<aqua>操作说明")
                .lore(List.of(
                        "<gray>左键装备槽: 放入光标物品",
                        "<gray>右键装备槽: 清空此槽位",
                        "<gray>Shift+左键装备槽: 克隆到背包",
                        "<dark_gray>装备会立即保存"
                ))
                .build());

        // [9-14] 装备槽位（物品槽位，由 GuiManager 特殊处理光标写入）
        for (int i = 0; i < SLOT_ORDER.size(); i++) {
            NpcEquipmentSlot slot = SLOT_ORDER.get(i);
            ItemStack equipped = equipment.get(slot);
            ItemStack preview = (equipped != null && equipped.getType() != Material.AIR)
                    ? equipped
                    : defaultPreviewItem(slot);
            markItemSlot(9 + i, preview);
        }

        // [15-17] 背景
        setButton(15, backgroundButton());
        setButton(16, backgroundButton());
        setButton(17, backgroundButton());

        // [18] 清空全部
        setButton(18, GuiButton.builder(Material.BARRIER)
                .name("<red>清空全部装备")
                .lore(List.of("<gray>清空所有槽位"))
                .onClick(ctx -> {
                    npc.setEquipment(Map.of());
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

    // ==================== 物品槽位点击回调 ====================

    /**
     * 物品槽位点击钩子：当玩家在装备槽位执行操作（左键放入光标/右键清空）后由 {@link GuiManager} 调用。
     *
     * <p>同步 NPC 装备数据并持久化。不调用 {@link #refresh()}，因为 {@link GuiManager} 已直接写入
     * Inventory 槽位，调用 refresh 会重置正在编辑的物品状态。</p>
     *
     * @param player    操作的玩家
     * @param slot      物品槽位索引（9-14）
     * @param newItem   写入后的新物品（null 表示已清空）
     * @param clickType 点击类型
     */
    @Override
    public void onItemSlotClick(@NotNull Player player, int slot,
                                @Nullable ItemStack newItem, @NotNull ClickType clickType) {
        int idx = slot - 9;
        if (idx < 0 || idx >= SLOT_ORDER.size()) {
            return;
        }
        NpcEquipmentSlot eqSlot = SLOT_ORDER.get(idx);
        Optional<Npc> opt = npcManager.getById(npcId);
        if (opt.isEmpty()) {
            return;
        }
        Npc npc = opt.get();
        // NpcData.equipment() 返回不可修改视图，必须复制到可变 Map
        Map<NpcEquipmentSlot, ItemStack> newEq = new LinkedHashMap<>(npc.getData().equipment());
        if (newItem == null || newItem.getType() == Material.AIR) {
            newEq.remove(eqSlot);
        } else {
            newEq.put(eqSlot, newItem);
        }
        npc.setEquipment(newEq);
        storage.save(npc);
    }

    // ==================== 辅助方法 ====================

    /** 槽位无装备时的默认预览物品（占位提示槽位位置） */
    private static ItemStack defaultPreviewItem(NpcEquipmentSlot slot) {
        return new ItemStack(defaultMaterialFor(slot));
    }

    /** 槽位无装备时的默认展示 Material */
    private static Material defaultMaterialFor(NpcEquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Material.DIAMOND_HELMET;
            case CHEST -> Material.DIAMOND_CHESTPLATE;
            case LEGS -> Material.DIAMOND_LEGGINGS;
            case BOOTS -> Material.DIAMOND_BOOTS;
            case MAINHAND -> Material.DIAMOND_SWORD;
            case OFFHAND -> Material.SHIELD;
        };
    }

    /** 槽位中文名 */
    private static String cnNameFor(NpcEquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "头盔";
            case CHEST -> "胸甲";
            case LEGS -> "护腿";
            case BOOTS -> "靴子";
            case MAINHAND -> "主手";
            case OFFHAND -> "副手";
        };
    }

    /** 拷贝源 ItemStack 的 CustomModelData 到目标 ItemMeta（保留资源包视觉差异） */
    private static void copyCustomModelData(ItemStack src, ItemMeta dest) {
        ItemMeta srcMeta = src.getItemMeta();
        if (srcMeta == null) return;
        if (srcMeta.hasCustomModelData()) {
            dest.setCustomModelData(srcMeta.getCustomModelData());
        }
    }

    /** 构造黄绿色玻璃背景按钮（空白名称） */
    private GuiButton backgroundButton() {
        return GuiButton.builder(Material.LIME_STAINED_GLASS_PANE)
                .name(" ")
                .build();
    }
}
