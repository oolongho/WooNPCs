package com.oolongho.woonpc.skin.dto;

import com.oolongho.woonpc.skin.json.Json;
import org.jetbrains.annotations.ApiStatus;

/**
 * Mojang username→UUID API（{@code api.mojang.com/users/profiles/minecraft/{name}}）响应。
 *
 * <p>注意：{@code id} 为不带连字符的 32 位十六进制串。</p>
 *
 * @param id   玩家 UUID（无连字符）
 * @param name 玩家名
 * @author oolongho
 */
@ApiStatus.Internal
public record MojangUuidResponse(String id, String name) {

    /** 从已解析的 JSON 对象构造。 */
    public static MojangUuidResponse fromJson(Json.Obj obj) {
        return new MojangUuidResponse(obj.str("id"), obj.str("name"));
    }
}
