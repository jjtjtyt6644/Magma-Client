package dev.magma.client.util;

import dev.magma.client.module.ModuleManager;
import dev.magma.client.modules.ClickGui;

/** Central palette. Accent colors come from the ClickGui module settings. */
public final class Theme {
    private Theme() {}

    public static final int BG_DARK = 0xF00D0F14;
    public static final int PANEL = 0xE6141720;
    public static final int PANEL_HEADER = 0xF01A1E2A;
    public static final int ROW_HOVER = 0x22FFFFFF;
    public static final int SETTINGS_BG = 0x66000000;
    public static final int TEXT = 0xFFECEFF4;
    public static final int TEXT_DIM = 0xFF8A93A6;
    public static final int TEXT_MUTED = 0xFF5C6475;
    public static final int TRACK = 0xFF2A2F3D;
    public static final int DANGER = 0xFFFF4D4D;
    public static final int WARN = 0xFFFFA62B;
    public static final int OK = 0xFF4ADE80;

    private static ClickGui gui() {
        return ModuleManager.ready() ? ModuleManager.get(ClickGui.class) : null;
    }

    /** Primary accent; offset (0..1) shifts along the gradient for multi-color effects. */
    public static int accent(float offset) {
        ClickGui g = gui();
        float hue = g == null ? 0.04f : g.hue.getFloat() / 360f;
        float spread = g == null ? 0.08f : g.spread.getFloat() / 360f;
        if (g != null && g.rainbow.get()) {
            hue += (System.currentTimeMillis() % 6000L) / 6000f;
            spread = 0.35f;
        }
        float t = (float) (0.5 + 0.5 * Math.sin((offset + (System.currentTimeMillis() % 4000L) / 4000f) * Math.PI * 2));
        return ColorUtil.hsb(hue + spread * t, 0.78f, 1f);
    }

    public static int accent() { return accent(0); }

    public static int accent2() {
        ClickGui g = gui();
        float hue = g == null ? 0.04f : g.hue.getFloat() / 360f;
        float spread = g == null ? 0.08f : g.spread.getFloat() / 360f;
        if (g != null && g.rainbow.get()) hue += (System.currentTimeMillis() % 6000L) / 6000f + 0.3f;
        return ColorUtil.hsb(hue + spread, 0.85f, 1f);
    }
}
