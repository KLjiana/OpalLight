package org.mesdag.opallight.light;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/// 彩光的显示强度独立于传播范围；天空光按原版光照曲线抑制额外增亮。
final class LightBrightness {
    static final float MASK_INTENSITY = 0.3F;
    static final float TINT_INTENSITY = 1.0F;
    static final float MIN_DAY_TINT_VISIBILITY = 0.25F;
    private static final float ENTITY_LIGHT_LEVEL = 12.0F;

    private LightBrightness() {}

    static float skyBrightness(@Nullable ClientLevel level) {
        if (level == null) return 0;
        return level.getSkyFlashTime() > 0 ? 1.0F : level.getSkyDarken(1.0F) * 0.95F + 0.05F;
    }

    static float ambientLight(@Nullable ClientLevel level) {
        return level == null ? 0 : level.dimensionType().ambientLight();
    }

    /// skyUv 是原版 lightmap 的天空光坐标（0..240），与 light_mask.vsh 一致。
    static float skyVisibility(int skyUv, float skyBrightness, float ambientLight) {
        float sky = Mth.clamp(skyUv / 240.0F, 0.0F, 1.0F);
        float brightness = Mth.lerp(ambientLight, sky / (4.0F - 3.0F * sky), 1.0F) * skyBrightness;
        return 1.0F - Mth.clamp(brightness, 0.0F, 1.0F);
    }

    static int entityLight(float strength, int skyUv, float skyBrightness, float ambientLight) {
        return Math.round(Mth.clamp(strength, 0.0F, 1.0F) * ENTITY_LIGHT_LEVEL
                * LightFalloff.edgeOpacity(strength) * skyVisibility(skyUv, skyBrightness, ambientLight)) << 4;
    }

    /// 日光仍抑制额外照明，但有色光保留少量染色；白光不改变白天的表面。
    static float colorVisibility(int skyUv, float skyBrightness, float ambientLight, float saturation) {
        float visibility = skyVisibility(skyUv, skyBrightness, ambientLight);
        return visibility + (1.0F - visibility) * MIN_DAY_TINT_VISIBILITY * Mth.clamp(saturation, 0.0F, 1.0F);
    }

    /// 彩光占原版受光的比例决定显色，最外侧三档亮度平滑淡出。
    static float tintStrength(float strength, int blockUv, int skyUv, float skyBrightness, float ambientLight, float saturation) {
        float skyLight = 1.0F - skyVisibility(skyUv, skyBrightness, ambientLight);
        float existingLight = Math.max(1.0F / 15.0F, Math.max(Mth.clamp(blockUv / 240.0F, 0, 1), skyLight));
        float share = Mth.clamp(strength / existingLight, 0, 1);
        return TINT_INTENSITY * share * LightFalloff.edgeOpacity(strength)
                * colorVisibility(skyUv, skyBrightness, ambientLight, saturation);
    }
}
