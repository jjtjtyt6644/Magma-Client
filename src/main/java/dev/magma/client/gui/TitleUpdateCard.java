package dev.magma.client.gui;

import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.Logo;
import dev.magma.client.util.Render2D;
import dev.magma.client.util.Theme;
import dev.magma.client.util.Updater;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** A small Magma update card in the top-left of the title screen, with a one-click button. */
public class TitleUpdateCard {
    private float bx, by, bw, bh; // button rect (last frame)
    private boolean hover;

    private static boolean visible() {
        var st = Updater.state();
        return st == Updater.State.AVAILABLE || st == Updater.State.DOWNLOADING || st == Updater.State.READY;
    }

    @SubscribeEvent
    public void onRender(ScreenEvent.Render.Post e) {
        if (!(e.getScreen() instanceof TitleScreen) || !visible()) return;
        GuiGraphics g = e.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        var st = Updater.state();
        boolean ready = st == Updater.State.READY, dl = st == Updater.State.DOWNLOADING;
        int accent = ready ? Theme.OK : Theme.accent();
        String title = ready ? "Magma update ready" : dl ? "Downloading Magma update" : "Magma update available";
        String ver = "v" + Updater.installed() + " → v" + Updater.latest();
        String btn = ready ? "Restart now" : "Update & restart";
        float w = Math.max(font.width(title), font.width(ver)) + 34 + (dl ? 0 : font.width(btn) + 26);
        float x = 8, y = 8, h = 32;

        Render2D.roundedRect(g, x, y, w, h, 7, 0xE6101217);
        Render2D.roundedOutline(g, x, y, w, h, 7, 1, ColorUtil.withAlpha(accent, 150));
        Logo.draw(g, x + 6, y + 8, 16, 1f);
        g.drawString(font, title, (int) x + 27, (int) y + 7, Theme.TEXT, false);
        if (dl) {
            float bwid = w - 35;
            Render2D.roundedRect(g, x + 27, y + 20, bwid, 3, 1.5f, 0xFF2A2E38);
            Render2D.roundedRect(g, x + 27, y + 20, Math.max(3, bwid * Updater.progress()), 3, 1.5f, accent);
            bw = 0;
        } else {
            g.drawString(font, ver, (int) x + 27, (int) y + 19, ColorUtil.withAlpha(accent, 230), false);
            bw = font.width(btn) + 16;
            bh = 16;
            bx = x + w - bw - 8;
            by = y + 8;
            hover = e.getMouseX() >= bx && e.getMouseX() < bx + bw && e.getMouseY() >= by && e.getMouseY() < by + bh;
            Render2D.roundedRect(g, bx, by, bw, bh, 5, hover ? ColorUtil.lerp(accent, 0xFFFFFFFF, 0.25f) : accent);
            g.drawString(font, btn, (int) (bx + bw / 2f - font.width(btn) / 2f), (int) by + 4, 0xFF120A06, false);
        }
    }

    @SubscribeEvent
    public void onClick(ScreenEvent.MouseButtonPressed.Pre e) {
        if (!(e.getScreen() instanceof TitleScreen) || !visible() || bw <= 0 || e.getButton() != 0) return;
        double mx = e.getMouseX(), my = e.getMouseY();
        if (mx >= bx && mx < bx + bw && my >= by && my < by + bh) {
            Updater.installAndRestart();
            e.setCanceled(true);
        }
    }
}
