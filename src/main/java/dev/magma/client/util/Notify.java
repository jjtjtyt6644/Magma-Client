package dev.magma.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Notification cards in the bottom-right corner (away from chat and the hotbar): a short title plus a few lines
 * explaining what's going on and what to do. Cards have a fixed maximum width - long text wraps, never runs off screen.
 * A keyed card (a running process) updates in place instead of stacking.
 */
public final class Notify {
    private static final List<Toast> TOASTS = new ArrayList<>();
    private static final int MAX_W = 230, MIN_W = 120, PAD = 7, MAX_LINES = 4;

    private static final class Toast {
        final String key;
        String title, detail;
        int color;
        long born, life, changed;
        float slide; // 0 = just appeared (off to the right), 1 = in place
        float y = Float.NaN; // animated vertical position

        Toast(String key, String title, String detail, int color) {
            this.key = key;
            set(title, detail, color);
        }

        void set(String title, String detail, int color) {
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            this.color = color;
            long now = System.currentTimeMillis();
            this.born = now;
            this.changed = now;
            // time to read it: ~2.5 s plus a bit per word
            int words = (this.title + " " + this.detail).split("\\s+").length;
            this.life = Math.min(10000, 2500 + words * 280L);
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
            if (key.equals(t.key)) {
                t.set(title, detail, color);
                return;
            }
        }
        TOASTS.add(new Toast(key, title, detail, color));
        while (TOASTS.size() > 4) TOASTS.remove(0);
    }

    private static long lastFrame;

    public static void render(GuiGraphics g) {
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (TOASTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();
        // beside the hotbar if a full-width card fits there, otherwise just above the hotbar and hunger bar
        int besideRoom = sw - 6 - (sw / 2 + 91 + 8);
        boolean beside = besideRoom >= MAX_W;
        int maxW = Math.min(MAX_W, sw - 12);
        float bottom = beside ? sh - 6 : sh - 46;

        float y = bottom;
        for (int i = TOASTS.size() - 1; i >= 0; i--) {
            Toast t = TOASTS.get(i);
            float age = now - t.born;
            t.slide = Anim.approach(t.slide, 1, dt * 7f);
            float fadeOut = Math.min(1f, (t.life - age) / 350f);
            float a = Math.max(0, Math.min(1f, t.slide * 1.4f) * fadeOut);
            if (a <= 0.02f) continue;

            int textW = maxW - PAD * 2 - 6;
            List<FormattedCharSequence> titleLines = font.split(Component.literal(t.title), textW);
            if (titleLines.size() > 2) titleLines = clip(font, titleLines, 2, textW);
            List<FormattedCharSequence> lines = t.detail.isEmpty() ? List.of() : font.split(Component.literal(t.detail), textW);
            if (lines.size() > MAX_LINES) lines = clip(font, lines, MAX_LINES, textW);

            int contentW = 0;
            for (FormattedCharSequence l : titleLines) contentW = Math.max(contentW, font.width(l));
            for (FormattedCharSequence l : lines) contentW = Math.max(contentW, font.width(l));
            int w = Math.max(MIN_W, Math.min(maxW, contentW + PAD * 2 + 6));
            int h = PAD + titleLines.size() * 10 + (lines.isEmpty() ? 0 : 3 + lines.size() * 9) + PAD - 1;

            float target = y - h;
            t.y = Float.isNaN(t.y) ? target : Anim.approach(t.y, target, dt * 12f);
            float top = t.y;
            float x = sw - 6 - w + (1 - Anim.easeOutCubic(t.slide)) * (w + 10);

            Render2D.shadow(g, x, top, w, h, 5, 5, ColorUtil.mulAlpha(0x70000000, a));
            Render2D.roundedRect(g, x, top, w, h, 5, ColorUtil.mulAlpha(0xF2101217, a));
            Render2D.roundedOutline(g, x, top, w, h, 5, 1, ColorUtil.mulAlpha(ColorUtil.withAlpha(t.color, 70), a));
            Render2D.roundedRect(g, x + 4, top + 5, 2, h - 10, 1, ColorUtil.mulAlpha(t.color, a)); // colour = kind of message

            // a card that just changed (a process moved on) flashes briefly
            float flash = Math.max(0, 1 - (now - t.changed) / 400f);
            if (flash > 0 && t.key != null) Render2D.roundedRect(g, x, top, w, h, 5, ColorUtil.mulAlpha(ColorUtil.withAlpha(t.color, 40), flash * a));

            int tx = (int) x + PAD + 5;
            int ty = (int) top + PAD;
            for (FormattedCharSequence l : titleLines) {
                g.drawString(font, l, tx, ty, ColorUtil.textAlpha(0xFFF1F3F7, a), false);
                ty += 10;
            }
            ty += 3;
            for (FormattedCharSequence l : lines) {
                g.drawString(font, l, tx, ty, ColorUtil.textAlpha(0xFFA3AAB8, a), false);
                ty += 9;
            }
            // how long it stays: a thin bar that runs down (a running process keeps a steady bar)
            float left = Math.max(0, 1 - age / (float) t.life);
            Render2D.rect(g, x + 8, top + h - 2, (w - 16) * left, 1, ColorUtil.mulAlpha(t.color, a * 0.55f));
            y = top - 4;
            if (y < 40) break; // don't stack up over the whole screen
        }
        for (Iterator<Toast> it = TOASTS.iterator(); it.hasNext(); ) {
            Toast t = it.next();
            if (now - t.born > t.life) it.remove();
        }
    }

    /** Keep the first n lines; the last one ends with "…" so it's clear there was more. */
    private static List<FormattedCharSequence> clip(Font font, List<FormattedCharSequence> lines, int n, int width) {
        List<FormattedCharSequence> out = new ArrayList<>(lines.subList(0, n - 1));
        StringBuilder sb = new StringBuilder();
        lines.get(n - 1).accept((idx, style, cp) -> { sb.appendCodePoint(cp); return true; });
        String last = sb.toString();
        while (!last.isEmpty() && font.width(last + "…") > width) last = last.substring(0, last.length() - 1);
        out.add(Component.literal(last.stripTrailing() + "…").getVisualOrderText());
        return out;
    }
}
