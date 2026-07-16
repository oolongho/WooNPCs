package com.oolongho.woonpc.skin.dto;

import com.oolongho.woonpc.skin.json.Json;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Ashcon 备用 API（{@code api.ashcon.app/mojang/v2/user/{name|uuid}}）响应。
 *
 * <p>当 Mojang session API 限速或失败时作为同源回退（Ashcon 同样返回玩家正版皮肤）。
 * 一次请求即返回 UUID 与 textures，{@code textures.raw} 内含 Base64 value 与 signature。</p>
 *
 * <p>注意：Ashcon 字段名为 {@code username}（兼容历史 {@code name}），{@code uuid} 为带连字符格式。</p>
 *
 * @param uuid     玩家 UUID（带连字符）
 * @param name     玩家名
 * @param textures 纹理原始数据，缺失时为 null
 * @author oolongho
 */
@ApiStatus.Internal
public record AshconResponse(String uuid, String name, @Nullable Raw textures) {

    /** 从已解析的 JSON 对象构造。 */
    public static AshconResponse fromJson(Json.Obj obj) {
        String name = obj.str("username");
        if (name == null) {
            name = obj.str("name");
        }
        Raw raw = null;
        Json.Obj textures = obj.obj("textures");
        if (textures != null) {
            Json.Obj rawObj = textures.obj("raw");
            if (rawObj != null) {
                raw = new Raw(rawObj.str("value"), rawObj.str("signature"));
            }
        }
        return new AshconResponse(obj.str("uuid"), name, raw);
    }

    /** Ashcon textures.raw 节点。 */
    @ApiStatus.Internal
    public record Raw(String value, String signature) {
    }
}
