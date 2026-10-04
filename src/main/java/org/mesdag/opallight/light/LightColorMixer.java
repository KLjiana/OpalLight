package org.mesdag.opallight.light;

/// 按原版遮罩使用的 RGB 系数累加到达强度，全部光源叠加后才统一限制亮度。
final class LightColorMixer {
    private LightColorMixer() {}

    /// 色相与亮度分开：单灯的颜色、衰减和中心白色保持不变。
    static double contribution(float channel, float sourcePeak, float white, float strength) {
        if (sourcePeak <= 0 || strength <= 0) return 0;
        double hue = channel / (double) sourcePeak;
        hue += (1 - hue) * white;
        return hue * strength;
    }

    static long finish(double red, double green, double blue) {
        double peak = Math.max(red, Math.max(green, blue));
        if (peak <= 0) return 0;
        /// 原版 RGBA8 地形目标与遮罩直接乘 RGB；再次套 sRGB 曲线会抬高弱通道。
        double scale = LightColorCache.mappedStrength((float) peak) / peak;
        return LightColorCache.pack((float) (red * scale), (float) (green * scale), (float) (blue * scale));
    }
}
