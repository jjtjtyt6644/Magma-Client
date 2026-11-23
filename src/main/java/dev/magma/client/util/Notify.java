package dev.magma.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Small fading toasts above the hotbar ("AutoMiner enabled", "pickaxe almost broken", ...). */
public final class Notify {
    private static final long LIFE = 2200;
    private static final List<Toast> TOASTS = new ArrayList<>();

    private record Toast(String text, int color, long born) {}

    private Notify() {}

    public static void show(String text, int color) {
        TOASTS.removeIf(t -> t.text.equals(text));
        TOASTS.add(new Toast(text, color, System.currentTimeMillis()));
        while (TOASTS.size() > 4) TOASTS.remove(0);
    }

    public static void render(GuiGraphics g) {
        if (TOASTS.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        long now = System.currentTimeMillis();
        int sw = g.guiWidth(), sh = g.guiHeight();
        float y = sh - 62;
        for (int i = TOASTS.size() - 1; i >= 0; i--) {
            Toast t = TOASTS.get(i);
            float age = now - t.born;
            float a = Math.min(1f, age / 150f) * Math.min(1f, (LIFE - age) / 300f);
            if (a <= 0.02f) continue;
            int w = font.width(t.text) + 22;
            float x = sw / 2f - w / 2f;
            float yy = y - (1 - Math.min(1f, age / 150f)) * 6;
            Render2D.roundedRect(g, x, yy, w, 14, 4, ColorUtil.mulAlpha(0xEE101217, a));
            Render2D.roundedOutline(g, x, yy, w, 14, 4, 1, ColorUtil.mulAlpha(0x1EFFFFFF, a));
            Render2D.circle(g, x + 8, yy + 7, 2.2f, ColorUtil.mulAlpha(t.color, a));
            g.drawString(font, t.text, (int) x + 15, (int) yy + 3, ColorUtil.textAlpha(0xFFE6E9EF, a), false);
            y -= 17;
        }
        for (Iterator<Toast> it = TOASTS.iterator(); it.hasNext(); ) if (now - it.next().born > LIFE) it.remove();
    }
}
