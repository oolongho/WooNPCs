package com.oolongho.woonpc.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * GUI 按钮组件（不可变）。
 *
 * <p>使用 Builder 模式构建，内部通过 {@link MiniMessage} 反序列化 name 与 lore，
 * 替代传统 § 颜色代码。构造后不可修改，{@link #getItemStack()} 返回副本以防止外部篡改。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * GuiButton button = GuiButton.builder(Material.STONE)
 *         .name("<green>确认")
 *         .lore(List.of("<gray>点击执行操作"))
 *         .glow()
 *         .onClick(ctx -> player.sendMessage("已点击"))
 *         .build();
 * }</pre>
 *
 * <h2>ClickContext</h2>
 * <p>点击回调通过 {@link ClickContext} 传入玩家与点击类型
 * （{@link ClickType}，来自 {@code org.bukkit.event.inventory}）。</p>
 *
 * @author oolongho
 */
@ApiStatus.Internal
public final class GuiButton {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ItemStack item;
    private final Consumer<ClickContext> onClick;

    private GuiButton(@NotNull ItemStack item, @Nullable Consumer<ClickContext> onClick) {
        this.item = item;
        this.onClick = onClick;
    }

    /**
     * 创建 Builder。
     *
     * @param material 物品材质，不可为 null
     * @return Builder 实例
     */
    public static Builder builder(@NotNull Material material) {
        return new Builder(material);
    }

    /**
     * 获取按钮物品的副本（防止外部修改内部状态）。
     *
     * @return ItemStack 副本
     */
    public ItemStack getItemStack() {
        return item.clone();
    }

    /**
     * 触发点击回调。由 {@link GuiScreen#handleClick} 委托调用。
     *
     * @param player    点击的玩家
     * @param clickType 点击类型
     */
    public void click(@NotNull Player player, @NotNull ClickType clickType) {
        if (onClick != null) {
            onClick.accept(new ClickContext(player, clickType));
        }
    }

    /**
     * 点击上下文记录类。
     *
     * @param player    点击的玩家
     * @param clickType 点击类型（{@link ClickType}）
     */
    public record ClickContext(@NotNull Player player, @NotNull ClickType clickType) {
    }

    /**
     * GuiButton 构建器。
     */
    public static final class Builder {

        private final Material material;
        private String name;
        private List<String> lore = List.of();
        private Consumer<ClickContext> onClick;
        private boolean glow;
        private final Set<ItemFlag> flags = new HashSet<>();
        /** 自定义 ItemMeta 修改器，在 displayName/lore/glow/flags 应用之后执行 */
        private Consumer<ItemMeta> metaModifier;

        private Builder(@NotNull Material material) {
            this.material = Objects.requireNonNull(material, "material cannot be null");
        }

        /**
         * 设置显示名（MiniMessage 格式字符串）。
         *
         * @param name MiniMessage 格式名称
         * @return 当前 Builder
         */
        public Builder name(@Nullable String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置 lore（每行为 MiniMessage 格式字符串）。
         *
         * @param lore lore 行列表
         * @return 当前 Builder
         */
        public Builder lore(@NotNull List<String> lore) {
            this.lore = Objects.requireNonNull(lore, "lore cannot be null");
            return this;
        }

        /**
         * 设置点击回调。
         *
         * @param onClick 点击回调
         * @return 当前 Builder
         */
        public Builder onClick(@Nullable Consumer<ClickContext> onClick) {
            this.onClick = onClick;
            return this;
        }

        /**
         * 启用附魔光辉效果（添加隐藏附魔 + HIDE_ENCHANTS）。
         *
         * @return 当前 Builder
         */
        public Builder glow() {
            this.glow = true;
            return this;
        }

        /**
         * 添加物品隐藏标志。
         *
         * @param flags ItemFlag 可变参数
         * @return 当前 Builder
         */
        public Builder flag(@NotNull ItemFlag... flags) {
            Objects.requireNonNull(flags, "flags cannot be null");
            for (ItemFlag f : flags) {
                this.flags.add(f);
            }
            return this;
        }

        /**
         * 设置自定义 ItemMeta 修改器。
         *
         * <p>在 {@link #build()} 中，当 displayName/lore/glow/flags 全部应用之后，
         * 调用此修改器对 {@link ItemMeta} 进行额外定制（如设置 PLAYER_HEAD 的
         * PlayerProfile、自定义模型数据等）。修改器接收的 {@link ItemMeta} 已经
         * 应用了基础属性，调用方可在其上追加或覆盖。</p>
         *
         * <p>注意：修改器在 {@link ItemStack#setItemMeta(ItemMeta)} 之前执行，
         * 无需调用方手动 setItemMeta。</p>
         *
         * @param modifier ItemMeta 修改器，不可为 null
         * @return 当前 Builder
         */
        public Builder metaModifier(@NotNull Consumer<ItemMeta> modifier) {
            this.metaModifier = Objects.requireNonNull(modifier, "metaModifier cannot be null");
            return this;
        }

        /**
         * 构建不可变的 GuiButton。
         *
         * @return GuiButton 实例
         */
        public GuiButton build() {
            ItemStack stack = new ItemStack(material);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (name != null) {
                    meta.displayName(MM.deserialize(name));
                }
                if (!lore.isEmpty()) {
                    List<Component> loreComponents = new ArrayList<>(lore.size());
                    for (String line : lore) {
                        loreComponents.add(MM.deserialize(line));
                    }
                    meta.lore(loreComponents);
                }
                if (glow) {
                    // 添加不可见的附魔以产生光辉效果
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
                for (ItemFlag f : flags) {
                    meta.addItemFlags(f);
                }
                // 应用自定义 meta 修改器（如设置 PLAYER_HEAD 的 PlayerProfile）
                if (metaModifier != null) {
                    metaModifier.accept(meta);
                }
                stack.setItemMeta(meta);
            }
            return new GuiButton(stack, onClick);
        }
    }
}
