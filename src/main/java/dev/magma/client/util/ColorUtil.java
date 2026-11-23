package dev.magma.client.util;

import java.awt.Color;

public final class ColorUtil {
    private ColorUtil() {}

    public static int argb(int a, int r, int g, int b) {
        return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
    }

    public static int alpha(int c) { return c >>> 24; }
    public static int red(int c) { return c >> 16 & 255; }
    public static int green(int c) { return c >> 8 & 255; }
    public static int blue(int c) { return c & 255; }

    public static int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }

    public static int mulAlpha(int c, float f) {
        return withAlpha(c, Math.round(alpha(c) * Math.max(0, Math.min(1, f))));
    }

    public static int lerp(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        return argb(
                Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
                Math.round(red(a) + (red(b) - red(a)) * t),
                Math.round(green(a) + (green(b) - green(a)) * t),
                Math.round(blue(a) + (blue(b) - blue(a)) * t));
    }

    public static int hsb(float hue, float sat, float bri) {
        return 0xFF000000 | (Color.HSBtoRGB(hue, sat, bri) & 0xFFFFFF);
    }

    /** Text colors with alpha < 4 are drawn fully opaque by the font renderer, so keep a floor. */
    public static int textAlpha(int c, float f) {
        return withAlpha(c, Math.max(5, Math.round(255 * Math.max(0, Math.min(1, f)))));
    }
}
