package com.drip.model;

/**
 * 一枚 U-Th 年代锦标。
 *
 * @param id             锦标编号
 * @param depthMm        沿生长轴的深度（mm，自顶向下增大）
 * @param ageKa          校正后年龄（ka，越大越老）
 * @param ageSigmaKa     年龄总 1σ 不确定度（ka）
 * @param sharedSigmaKa  可归因于共享碎屑校正参数的 1σ 分量（ka），同组锦标协方差=shared_i*shared_j
 * @param corrGroup      共享校正参数相关组标识，null 表示独立
 * @param excluded       是否被用户排除
 */
public record DateSample(String id, double depthMm, double ageKa, double ageSigmaKa,
                         double sharedSigmaKa, String corrGroup, boolean excluded) {
}
