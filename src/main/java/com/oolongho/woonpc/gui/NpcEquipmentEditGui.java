package com.oolongho.woonpc.gui;

import com.oolongho.woonpc.api.Npc;
import com.oolongho.woonpc.api.NpcManager;
import com.oolongho.woonpc.npc.NpcEquipmentSlot;
import com.oolongho.woonpc.storage.NpcStorage;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * NPC 装备编辑 GUI（27 格，3 行布局）。
 *
 * <p>提供六槽位（HEAD/CHEST/LEGS/BOOTS/MAINHAND/OFFHAND）的装备编辑：
 * 左键放入玩家主手物品（clone 后存入），右键清空对应槽位。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * Row 1 (0-8):   [返回][背景][背景][背景][操作说明][背景][背景][背景][背景]
 * Row 2 (9-17):  [头盔][胸甲][护腿][靴子][主手][副手][背景][背景][背景]
 * Row 3 (18-26): [清空全部][背景][背景][背景][背景][背景][背景][背景][背景]
 * </pre>
 *
 * <h2>编辑模式</h2>
 * <ul>
 *   <li>左键装备槽：检查玩家主手物品，非 AIR 则 clone 后存入对应槽位</li>
 *   <li>右键装备槽：移除该槽位的装备</li>
 *   <li>清空全部：将 equipment 设为空映射</li>
 * </ul>
 *
 * <h2>装备图标显示</h2>
 * <p>若槽位已有装备：按钮 Material 使用装备自身的 Material，并通过 metaModifier
 * 复制装备的 CustomModelData（保留资源包视觉差异）；name/lore 使用统一格式覆盖装备原名。
 * 若槽位无装备：使用默认 Material（如 DIAMOND_HELMET）。</p>
 *
 * <p>NpcData.equipment() 返回不可修改视图，修改前需复制到 {@link LinkedHashMap}。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class NpcEquipmentEditGui extends GuiScreen {

    private static final MiniMessage MM = MiniMessage.miniMessage();
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
                        "<gray>左键: 放入主手物品",
                        "<gray>右键: 清空此槽位"
                ))
                .build());

        // [9-14] 装备槽位
        for (int i = 0; i < SLOT_ORDER.size(); i++) {
            NpcEquipmentSlot slot = SLOT_ORDER.get(i);
            ItemStack equipped = equipment.get(slot);
            setButton(9 + i, buildEquipmentButton(npc, slot, equipped));
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

    // ==================== 装备槽位按钮构建 ====================

    /**
     * 构造单个装备槽位按钮。
     *
     * <p>若槽位有装备：使用装备的 Material + metaModifier 拷贝 CustomModelData（保留资源包视觉）；
     * 若槽位无装备：使用默认 Material（如 DIAMOND_HELMET）。</p>
     *
     * <p>name 与 lore 使用统一格式，覆盖装备原本的 displayName。</p>
     */
    private GuiButton buildEquipmentButton(Npc npc, NpcEquipmentSlot slot,
                                           @Nullable ItemStack equipped) {
        boolean hasEquipped = equipped != null && equipped.getType() != Material.AIR;
        Material material = hasEquipped ? equipped.getType() : defaultMaterialFor(slot);
        String cnName = cnNameFor(slot);

        String statusLine;
        if (hasEquipped) {
            statusLine = "<yellow>" + cnName + " <gray>| <aqua>" + material.name() + " x" + equipped.getAmount();
        } else {
            statusLine = "<yellow>" + cnName + " <gray>| <red>空";
        }

        GuiButton.Builder builder = GuiButton.builder(material)
                .name(statusLine)
                .lore(List.of(
                        "<gray>左键: 放入主手物品",
                        "<gray>右键: 清空此槽位"
                ));

        // 若有装备，metaModifier 复制装备的 CustomModelData 以保留资源包外观
        if (hasEquipped) {
            final ItemStack src = equipped;
            builder.metaModifier(meta -> copyCustomModelData(src, meta));
        }

        builder.onClick(ctx -> {
            Player p = ctx.player();
            ClickType ct = ctx.clickType();
            if (ct == ClickType.LEFT) {
                // 放入主手物品
                ItemStack mainHand = p.getInventory().getItemInMainHand();
                if (mainHand == null || mainHand.getType() == Material.AIR) {
                    p.sendMessage(MM.deserialize("<red>主手无物品。"));
                    return;
                }
                // NpcData.equipment() 返回不可修改视图，必须复制到可变 Map
                Map<NpcEquipmentSlot, ItemStack> newEq = new LinkedHashMap<>(npc.getData().equipment());
                newEq.put(slot, mainHand.clone());
                npc.setEquipment(newEq);
                storage.save(npc);
                refresh();
            } else if (ct == ClickType.RIGHT) {
                // 清空此槽位
                Map<NpcEquipmentSlot, ItemStack> newEq = new LinkedHashMap<>(npc.getData().equipment());
                newEq.remove(slot);
                npc.setEquipment(newEq);
                storage.save(npc);
                refresh();
            }
        });

        return builder.build();
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

    // ==================== 辅助方法 ====================

    /** 构造灰色玻璃背景按钮（空白名称） */
    private GuiButton backgroundButton() {
        return GuiButton.builder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .build();
    }
}
