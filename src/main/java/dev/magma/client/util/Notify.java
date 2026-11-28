package dev.magma.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Notification cards above the hotbar: a title plus a line explaining what's going on (and what to do).
 * Longer messages stay up longer; a keyed card (a running process) updates in place instead of stacking.
 */
public final class Notify {
    private static final List<Toast> TOASTS = new ArrayList<>();
    private static final int MAX_W = 240;

    private static final class Toast {
        final String key;
        String title, detail;
        int color;
        long born, life;

        Toast(String key, String title, String detail, int color) {
            this.key = key;
            set(title, detail, color);
        }

        void set(String title, String detail, int color) {
            this.title = title;
            this.detail = detail == null ? "" : detail;
            this.color = color;
            this.born = System.currentTimeMillis();
            // time to read it: ~2.2 s plus a bit per word
            int words = (title + " " + this.detail).split("\\s+").length;
            this.life = Math.min(9000, 2200 + words * 260L);
        }
    }

    private Notify() {}

    /** Short message (one line). */
    public static void show(String text, int color) {
        show(text, null, color);
    }

    /** Title + explanation. */
    public static void show(String title, String detail, int color) {
        TOASTS.removeIf(t -> t.key == null && t.title.equals(title));
        TOASTS.add(new Toast(null, title, detail, color));
        while (TOASTS.size() > 4) TOASTS.remove(0);
    }

    /** A running process (e.g. "Getting materials"): updates its own card instead of adding new ones. */
    public static void progress(String key, String title, String detail, int color) {
        for (Toast t : TOASTS) {
            if (key.equals(t.key)) { t.set(title, detail, color); return; }
        }
        TOASTS.add(new Toast(key, title, detail, color));
        while (TOASTS.size() > 4) TOASTS.remove(0);
    }

    public static void render(GuiGraphics g) {
        if (TOASTS.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        long now = System.currentTimeMillis();
        int sw = g.guiWidth(), sh = g.guiHeight();
        float y = sh - 60;
        for (int i = TOASTS.size() - 1; i >= 0; i--) {
            Toast t = TOASTS.get(i);
            float age = now - t.born;
            float in = Math.min(1f, age / 180f);
            float a = in * Math.min(1f, (t.life - age) / 300f);
            if (a <= 0.02f) continue;
            List<FormattedCharSequence> lines = t.detail.isEmpty() ? List.of() : font.split(Component.literal(t.detail), MAX_W - 24);
            if (lines.size() > 3) lines = lines.subList(0, 3);
            int w = Math.max(font.width(t.title), lines.stream().mapToInt(font::width).max().orElse(0)) + 26;
            int h = 14 + lines.size() * 10 + (lines.isEmpty() ? 0 : 3);
            float x = sw / 2f - w / 2f;
            float top = y - h + (1 - Anim.easeOutCubic(in)) * 8;
            Render2D.shadow(g, x, top, w, h, 5, 4, ColorUtil.mulAlpha(0x60000000, a));
            Render2D.roundedRect(g, x, top, w, h, 5, ColorUtil.mulAlpha(0xF0101217, a));
            Render2D.roundedOutline(g, x, top, w, h, 5, 1, ColorUtil.mulAlpha(0x1EFFFFFF, a));
            Render2D.roundedRect(g, x + 4, top + 4, 2, h - 8, 1, ColorUtil.mulAlpha(t.color, a)); // colour = kind of message
            g.drawString(font, t.title, (int) x + 12, (int) top + 4, ColorUtil.textAlpha(0xFFF1F3F7, a), false);
            for (int l = 0; l < lines.size(); l++)
                g.drawString(font, lines.get(l), (int) x + 12, (int) top + 15 + l * 10, ColorUtil.textAlpha(0xFFA9B0BE, a), false);
            // how long it stays: a thin bar that runs down
            float left = Math.max(0, 1 - age / (float) t.life);
            Render2D.rect(g, x + 8, top + h - 2, (w - 16) * left, 1, ColorUtil.mulAlpha(t.color, a * 0.6f));
            y = top - 4;
        }
        for (Iterator<Toast> it = TOASTS.iterator(); it.hasNext(); ) {
            Toast t = it.next();
            if (now - t.born > t.life) it.remove();
        }
    }
}
