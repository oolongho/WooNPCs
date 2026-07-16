package com.oolongho.woonpc.nms.dto;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * NPC 皮肤纹理数据。
 *
 * <p>对应 Mojang textures property 的两部分：</p>
 * <ul>
 *   <li>{@code value}：Base64 编码的 JSON 纹理描述（含 skin 与 cape 的 URL）</li>
 *   <li>{@code signature}：textures property 的 RSA 签名（正版皮肤必需，离线皮肤可为 null）</li>
 * </ul>
 *
 * <p>此对象仅承载数据，不负责纹理获取；具体 GameProfile 构造在 NmsAdapter 实现中完成。</p>
 *
 * @param value     纹理数据 Base64 串，不可为 null
 * @param signature 纹理签名，离线皮肤可为 null
 * @author oolongho
 */
@ApiStatus.Internal
public record NpcTexture(
        String value,
        @Nullable String signature
) {
    public NpcTexture {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Texture value must not be null or blank");
        }
    }
}
