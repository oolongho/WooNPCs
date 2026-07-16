package com.oolongho.woonpc.skin;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * NPC 皮肤数据（业务层）。
 *
 * <p>承载玩家皮肤的纹理值与签名两部分，对应 Mojang textures property：</p>
 * <ul>
 *   <li>{@code texture}：Base64 编码的 JSON 纹理描述（含 skin 与 cape 的 URL），
 *       空字符串表示使用默认 Steve 皮肤</li>
 *   <li>{@code signature}：textures property 的 RSA 签名，离线皮肤或默认皮肤可为空字符串</li>
 * </ul>
 *
 * <p>与 {@link com.oolongho.woonpc.nms.dto.NpcTexture}（协议层 DTO）的区别：</p>
 * <ul>
 *   <li>本类是业务层数据，允许空字符串表示默认皮肤，由 {@code NpcController} 在 spawn 时
 *       判断并转换为 {@code NpcTexture}（非空）或 {@code null}（使用服务端默认 Steve）</li>
 *   <li>{@code NpcTexture} 是协议层不可空值对象，构造时强制 texture 非空非空白</li>
 * </ul>
 *
 * <p>本类为公共 API 的一部分，用户通过 {@code NpcData.Builder.skin(SkinData)} 设置皮肤。</p>
 *
 * @param texture  纹理数据 Base64 串，空字符串表示默认 Steve 皮肤
 * @param signature 纹理签名，无签名时为空字符串
 * @author oolongho
 */
public record SkinData(
        String texture,
        String signature
) {

    /** 默认 Steve 皮肤实例（texture 与 signature 均为空字符串） */
    private static final SkinData DEFAULT = new SkinData("", "");

    /**
     * 校验字段非 null。
     *
     * @throws NullPointerException 当 texture 或 signature 为 null
     */
    public SkinData {
        Objects.requireNonNull(texture, "texture cannot be null (use empty string for default skin)");
        Objects.requireNonNull(signature, "signature cannot be null (use empty string for no signature)");
    }

    /**
     * 获取默认 Steve 皮肤实例。
     *
     * <p>texture 与 signature 均为空字符串，{@code NpcController} 在 spawn 时
     * 会将其转换为 {@code null} 传给 {@code NmsAdapter}，触发服务端默认皮肤。</p>
     *
     * @return 默认皮肤单例
     */
    public static SkinData defaultSkin() {
        return DEFAULT;
    }

    /**
     * 判断是否为默认皮肤（texture 为空）。
     *
     * @return texture 为空或空白时返回 true
     */
    public boolean isDefault() {
        return texture.isBlank();
    }

    /**
     * 判断是否有签名（正版皮肤）。
     *
     * @return signature 非空非空白时返回 true
     */
    public boolean isSigned() {
        return !signature.isBlank();
    }

    /**
     * 转换为协议层 {@link com.oolongho.woonpc.nms.dto.NpcTexture}。
     *
     * <p>当 {@link #isDefault()} 为 true 时返回 null，表示使用服务端默认 Steve 皮肤；
     * 否则构造非空 {@code NpcTexture}。</p>
     *
     * @return 协议层纹理对象，默认皮肤返回 null
     */
    @ApiStatus.Internal
    public @Nullable com.oolongho.woonpc.nms.dto.NpcTexture toNpcTexture() {
        if (isDefault()) {
            return null;
        }
        return new com.oolongho.woonpc.nms.dto.NpcTexture(
                texture,
                signature.isBlank() ? null : signature
        );
    }
}
