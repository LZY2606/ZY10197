package com.drip.model;

/**
 * 生长间断。深度区间 [topDepthMm, bottomDepthMm] 内不允许任何年龄插值穿过。
 *
 * @param topOpen    间断上侧（浅部）边界开放：开放时不做边界年龄投影
 * @param bottomOpen 间断下侧（深部）边界开放
 */
public record HiatusSpec(String id, double topDepthMm, double bottomDepthMm,
                         boolean topOpen, boolean bottomOpen) {
}
