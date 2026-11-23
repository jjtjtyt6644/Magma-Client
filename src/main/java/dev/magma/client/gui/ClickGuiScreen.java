package dev.magma.client.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.magma.client.MagmaClient;
import dev.magma.client.config.ConfigManager;
import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.modules.ClickGui;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.ModeSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.setting.Setting;
import dev.magma.client.util.Anim;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.Render2D;
import dev.magma.client.util.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Magma ClickGUI: draggable glass panels, animated switches/sliders, live search, inline key binding.
 * Left-click: toggle / interact. Right-click module: settings. Middle-click module: bind. Right-click header: collapse.
 */
public class ClickGuiScreen extends Screen {
    private static final int PANEL_W = 128, HEADER_H = 24, ROW_H = 19, SET_H = 16, NUM_H = 24, RADIUS = 7;

    // ------------------------------------------------------------ persistent layout (survives reopening)
    private static final Map<Category, float[]> LAYOUT = new EnumMap<>(Category.class); // x, y, collapsed
    private static final Set<Module> EXPANDED = new HashSet<>();
    private static final Map<Object, Float> ANIMS = new HashMap<>();
    private static final List<Category> ORDER = new ArrayList<>(List.of(Category.values()));

    static {
        for (int i = 0; i < Category.values().length; i++) {
            LAYOUT.put(Category.values()[i], new float[]{16 + i * (PANEL_W + 14), 46, 0});
        }
    }

    private record Hit(float x, float y, float w, float h, Object key, IntConsumer onClick, String tooltip) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private static final Map<Category, Float> PANEL_SCROLL = new EnumMap<>(Category.class);
    private final Map<Category, float[]> panelRects = new EnumMap<>(Category.class);
    /** Current clip rect (x, y, w, h) for hit-testing; null = no clip. */
    private float[] clip;

    private void addHit(Hit h) {
        if (clip == null) {
            hits.add(h);
            return;
        }
        float x0 = Math.max(h.x(), clip[0]), y0 = Math.max(h.y(), clip[1]);
        float x1 = Math.min(h.x() + h.w(), clip[0] + clip[2]), y1 = Math.min(h.y() + h.h(), clip[1] + clip[3]);
        if (x1 <= x0 || y1 <= y0) return;
        hits.add(new Hit(x0, y0, x1 - x0, y1 - y0, h.key(), h.onClick(), h.tooltip()));
    }
    private List<Hit> lastHits = new ArrayList<>();
    private Object hovered;
    private String tooltip;

    private float open;
    private boolean closing;
    private Category dragging;
    private float dragDX, dragDY;
    private NumberSetting slider;
    private float sliderX, sliderW;
    private Module binding;
    private String search = "";
    private boolean searchFocus;
    private float scroll, scrollTarget;
    private int mouseX, mouseY;
    private final Anim.Clock clock = new Anim.Clock();

    public ClickGuiScreen() {
        super(Component.literal("Magma ClickGUI"));
    }

    @Override public boolean isPauseScreen() { return false; }

    private ClickGui cfg() { return ModuleManager.get(ClickGui.class); }

    // ------------------------------------------------------------ animation helpers

    private float anim(Object key, float target, float speed, float dt) {
        float v = ANIMS.getOrDefault(key, target == 1f ? 0f : target);
        v = Anim.approach(v, target, dt * speed);
        ANIMS.put(key, v);
        return v;
    }

    private boolean isHovered(Object key) { return key.equals(hovered); }

    // ------------------------------------------------------------ rendering

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        if (cfg().blur.get()) renderBlurredBackground(pt);
        float a = Anim.easeOutCubic(open);
        Render2D.vGradient(g, 0, 0, width, height, ColorUtil.mulAlpha(0x900A0B10, a), ColorUtil.mulAlpha(0xB0050508, a));
        // soft accent glow at the bottom
        Render2D.vGradient(g, 0, height - 120, width, 120, 0x00000000, ColorUtil.withAlpha(Theme.accent(), (int) (34 * a)));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        float dt = clock.tick();
        mouseX = mx;
        mouseY = my;
        open = Anim.approach(open, closing ? 0f : 1f, dt * 9f);
        if (closing && open < 0.03f) {
            super.onClose();
            return;
        }
        scroll = Anim.approach(scroll, scrollTarget, dt * 14f);

        // hover = top-most hit from last frame
        hovered = null;
        tooltip = null;
        for (int i = lastHits.size() - 1; i >= 0; i--) {
            Hit h = lastHits.get(i);
            if (h.contains(mx, my)) {
                hovered = h.key;
                tooltip = h.tooltip;
                break;
            }
        }
        hits.clear();

        renderBackground(g, mx, my, pt);
        if (slider != null) applySlider(mx);

        float alpha = Anim.easeOutCubic(open);
        drawTopBar(g, alpha, dt);

        int idx = 0;
        for (Category c : ORDER) {
            drawPanel(g, c, alpha, dt, idx++);
        }

        if (tooltip != null && cfg().descriptions.get() && dragging == null && slider == null) drawTooltip(g, tooltip, alpha);
        if (binding != null) drawBindOverlay(g, alpha);
        g.flush();

        lastHits = new ArrayList<>(hits);
    }

    private void drawTopBar(GuiGraphics g, float alpha, float dt) {
        Font f = font;
        String logo = MagmaClient.NAME.toUpperCase();
        int logoW = f.width(logo) + 6;
        String ver = "v" + MagmaClient.VERSION;
        int searchW = 150;
        String winLbl = "▣ Window";
        int winW = f.width(winLbl) + 12;
        int total = 16 + logoW + f.width(ver) + 14 + searchW + 8 + winW + 6;
        float x = width / 2f - total / 2f;
        float y = 10 - (1 - alpha) * 14;

        Render2D.shadow(g, x, y, total, 24, 9, 8, ColorUtil.mulAlpha(ColorUtil.withAlpha(Theme.accent(), 70), alpha));
        Render2D.roundedRect(g, x, y, total, 24, 9, ColorUtil.mulAlpha(0xE60F1118, alpha));
        Render2D.roundedOutline(g, x, y, total, 24, 9, 1, ColorUtil.mulAlpha(0x22FFFFFF, alpha));

        // flame dot + logo
        dev.magma.client.util.Logo.draw(g, x + 5, y + 4, 16, alpha);
        int tx = (int) (x + 21);
        tx += Render2D.gradientText(g, logo, tx, y + 8, alpha, false);
        g.drawString(f, ver, tx + 4, (int) y + 8, ColorUtil.textAlpha(Theme.TEXT_MUTED, alpha), false);

        // search box
        float wbx = x + total - winW - 6, wby = y + 4;
        Object wk = "toWindow";
        float wh = anim("toWindowHover", isHovered(wk) ? 1 : 0, 10, dt);
        Render2D.roundedRect(g, wbx, wby, winW, 16, 6, ColorUtil.mulAlpha(ColorUtil.lerp(0xFF1A1E29, 0xFF2A3040, wh), alpha));
        g.drawString(f, winLbl, (int) wbx + 6, (int) wby + 4, ColorUtil.textAlpha(ColorUtil.lerp(Theme.TEXT_DIM, Theme.TEXT, wh), alpha), false);
        addHit(new Hit(wbx, wby, winW, 16, wk, b -> {
            cfg().style.set("Window");
            ConfigManager.save();
            minecraft.setScreen(new MenuScreen());
        }, "Switch back to the main menu window"));

        float sx = wbx - searchW - 6, sy = y + 4;
        Object key = "search";
        float hov = anim("searchHover", isHovered(key) || searchFocus ? 1 : 0, 10, dt);
        Render2D.roundedRect(g, sx, sy, searchW, 16, 6, ColorUtil.mulAlpha(ColorUtil.lerp(0xFF1A1E29, 0xFF222736, hov), alpha));
        if (searchFocus) Render2D.roundedOutline(g, sx, sy, searchW, 16, 6, 1, ColorUtil.mulAlpha(Theme.accent(), alpha));
        String shown = search.isEmpty() && !searchFocus ? "⌕ Search modules..." : search;
        boolean caret = searchFocus && (System.currentTimeMillis() / 500) % 2 == 0;
        String clipped = f.plainSubstrByWidth(shown, searchW - 14, true);
        g.drawString(f, clipped + (caret ? "_" : ""), (int) sx + 7, (int) sy + 4,
                ColorUtil.textAlpha(search.isEmpty() && !searchFocus ? Theme.TEXT_MUTED : Theme.TEXT, alpha), false);
        addHit(new Hit(sx, sy, searchW, 16, key, b -> {
            searchFocus = true;
            if (b == 1) search = "";
        }, "Type to filter modules. Right-click to clear."));
    }

    private boolean matches(Module m) {
        if (search.isEmpty()) return true;
        String s = search.toLowerCase();
        return m.getName().toLowerCase().contains(s) || m.getDescription().toLowerCase().contains(s);
    }

    private float settingHeight(Setting<?> s) {
        if (s instanceof dev.magma.client.setting.MultiSetting ms) return SET_H + (float) Math.ceil(ms.getOptions().size() / 2.0) * 13 + 2;
        return s instanceof NumberSetting ? NUM_H : SET_H;
    }

    private void drawPanel(GuiGraphics g, Category cat, float alpha, float dt, int index) {
        float[] lay = LAYOUT.get(cat);
        List<Module> mods = new ArrayList<>();
        for (Module m : ModuleManager.byCategory(cat)) if (matches(m)) mods.add(m);

        // staggered open slide
        float stagger = Anim.easeOutCubic(Math.max(0, Math.min(1, open * 1.4f - index * 0.08f)));
        float px = lay[0];
        float py = lay[1] + scroll - (1 - stagger) * 24;
        float pa = alpha * stagger;
        if (pa < 0.02f) return;

        float collapse = anim("collapse:" + cat, lay[2] > 0.5f ? 0 : 1, 12, dt);

        // content height
        float body = 0;
        for (Module m : mods) {
            body += ROW_H;
            float ex = anim("expand:" + m.getName(), EXPANDED.contains(m) ? 1 : 0, 12, dt);
            if (ex > 0.001f) body += settingsBlockHeight(m) * ex;
        }
        if (mods.isEmpty()) body = 18;
        // never let a panel run off the bottom of the screen: cap height and scroll inside it
        float maxBody = Math.max(40, height - py - HEADER_H - 12);
        float overflow = Math.max(0, body - maxBody);
        float pScroll = Math.max(0, Math.min(overflow, PANEL_SCROLL.getOrDefault(cat, 0f)));
        PANEL_SCROLL.put(cat, pScroll);
        float bodyH = Math.min(body, maxBody) * collapse;
        float h = HEADER_H + bodyH + (collapse > 0.01f ? 5 * collapse : 0);

        // blocker so clicks don't fall through to panels underneath
        addHit(new Hit(px, py, PANEL_W, h, "panel:" + cat, b -> {}, null));

        int dim = mods.isEmpty() && !search.isEmpty() ? 1 : 0;
        float panelA = pa * (dim == 1 ? 0.45f : 1f);

        // shadow + glass body
        boolean drag = dragging == cat;
        Render2D.shadow(g, px, py, PANEL_W, h, RADIUS, drag ? 14 : 9, ColorUtil.mulAlpha(drag ? ColorUtil.withAlpha(Theme.accent(), 90) : 0x70000000, panelA));
        Render2D.roundedRect(g, px, py, PANEL_W, h, RADIUS, ColorUtil.mulAlpha(Theme.PANEL, panelA));

        // header
        Render2D.roundedGradient(g, px, py, PANEL_W, HEADER_H, RADIUS, ColorUtil.mulAlpha(0xFF1C2130, panelA), ColorUtil.mulAlpha(0xFF161A25, panelA), true);
        if (bodyH > 1) Render2D.rect(g, px, py + HEADER_H - RADIUS, PANEL_W, RADIUS, ColorUtil.mulAlpha(0xFF161A25, panelA));
        Render2D.roundedGradient(g, px + 10, py + HEADER_H - 1.5f, PANEL_W - 20, 1.5f, 0.75f,
                ColorUtil.mulAlpha(Theme.accent(0), panelA), ColorUtil.mulAlpha(Theme.accent2(), panelA), false);

        // icon badge
        Render2D.roundedGradient(g, px + 7, py + 5, 14, 14, 4, ColorUtil.mulAlpha(Theme.accent(), panelA), ColorUtil.mulAlpha(Theme.accent2(), panelA), true);
        g.drawString(font, cat.icon, (int) (px + 14 - font.width(cat.icon) / 2f), (int) py + 8, ColorUtil.textAlpha(0xFF101010, panelA), false);
        g.drawString(font, cat.display, (int) px + 26, (int) py + 8, ColorUtil.textAlpha(Theme.TEXT, panelA), false);
        int enabledCount = 0;
        for (Module m : ModuleManager.byCategory(cat)) if (m.isEnabled()) enabledCount++;
        String cnt = enabledCount + "/" + ModuleManager.byCategory(cat).size();
        String chev = lay[2] > 0.5f ? "▸" : "▾";
        g.drawString(font, cnt, (int) (px + PANEL_W - 18 - font.width(cnt)), (int) py + 8, ColorUtil.textAlpha(Theme.TEXT_MUTED, panelA), false);
        g.drawString(font, chev, (int) (px + PANEL_W - 12), (int) py + 8, ColorUtil.textAlpha(Theme.TEXT_DIM, panelA), false);

        addHit(new Hit(px, py, PANEL_W, HEADER_H, "header:" + cat, b -> {
            if (b == 0) {
                dragging = cat;
                dragDX = mouseX - lay[0];
                dragDY = mouseY - lay[1];
                ORDER.remove(cat);
                ORDER.add(cat);
            } else if (b == 1) {
                lay[2] = lay[2] > 0.5f ? 0 : 1;
            }
        }, "Drag to move • Right-click to collapse"));

        if (bodyH < 0.5f) return;

        // body (clipped while animating)
        float by = py + HEADER_H + 2;
        g.flush();
        g.enableScissor((int) px, (int) (py + HEADER_H), (int) (px + PANEL_W), (int) Math.ceil(py + h - 2));
        if (mods.isEmpty()) {
            g.drawString(font, "No matches", (int) px + 10, (int) by + 5, ColorUtil.textAlpha(Theme.TEXT_MUTED, panelA), false);
        }
        clip = new float[]{px, py + HEADER_H, PANEL_W, h - HEADER_H - 2};
        float y = by - pScroll;
        for (Module m : mods) {
            y = drawModule(g, m, px, y, panelA, dt, py + h);
        }
        g.flush();
        g.disableScissor();
        clip = null;
        panelRects.put(cat, new float[]{px, py, PANEL_W, h, overflow});
        if (overflow > 0 && collapse > 0.5f) {
            float trackH = h - HEADER_H - 8;
            float barH = Math.max(12, trackH * trackH / (trackH + overflow));
            float barY = py + HEADER_H + 4 + (pScroll / overflow) * (trackH - barH);
            Render2D.roundedRect(g, px + PANEL_W - 4, barY, 2, barH, 1, ColorUtil.mulAlpha(0x50FFFFFF, panelA));
        }
    }

    private float settingsBlockHeight(Module m) {
        float sh = 4;
        for (Setting<?> s : m.getSettings()) if (s.isVisible()) sh += settingHeight(s);
        sh += SET_H; // keybind row
        return sh + 2;
    }

    private float drawModule(GuiGraphics g, Module m, float px, float y, float pa, float dt, float clipBottom) {
        Object key = "mod:" + m.getName();
        float hov = anim("hover:" + m.getName(), isHovered(key) ? 1 : 0, 14, dt);
        float on = anim("on:" + m.getName(), m.isEnabled() ? 1 : 0, 12, dt);

        float rx = px + 4, rw = PANEL_W - 8;
        // row background: hover + enabled glow
        if (on > 0.01f) {
            Render2D.roundedGradient(g, rx, y, rw, ROW_H - 2, 5,
                    ColorUtil.mulAlpha(ColorUtil.withAlpha(Theme.accent(), 70), on * pa),
                    ColorUtil.mulAlpha(ColorUtil.withAlpha(Theme.accent2(), 18), on * pa), false);
        }
        if (hov > 0.01f) Render2D.roundedRect(g, rx, y, rw, ROW_H - 2, 5, ColorUtil.mulAlpha(Theme.ROW_HOVER, hov * pa));
        // left accent bar grows when enabled
        float barH = (ROW_H - 8) * on;
        if (barH > 0.5f) Render2D.roundedRect(g, rx + 2, y + (ROW_H - 2) / 2f - barH / 2f, 2, barH, 1, ColorUtil.mulAlpha(Theme.accent(), pa));

        int textCol = ColorUtil.lerp(Theme.TEXT_DIM, Theme.TEXT, Math.max(on, hov * 0.6f));
        g.drawString(font, m.getName(), (int) (rx + 8 + 2 * hov), (int) y + 5, ColorUtil.textAlpha(textCol, pa), false);

        // key hint
        if (m.getKey() != -1) {
            String k = keyName(m.getKey());
            g.drawString(font, k, (int) (rx + rw - 30 - font.width(k)), (int) y + 5, ColorUtil.textAlpha(Theme.TEXT_MUTED, pa), false);
        }

        // switch
        float swW = 18, swH = 9;
        float swX = rx + rw - swW - 5, swY = y + (ROW_H - 2) / 2f - swH / 2f;
        Render2D.roundedRect(g, swX, swY, swW, swH, swH / 2f, ColorUtil.mulAlpha(Theme.TRACK, pa));
        if (on > 0.01f) Render2D.roundedGradient(g, swX, swY, swW, swH, swH / 2f, ColorUtil.mulAlpha(Theme.accent(), on * pa), ColorUtil.mulAlpha(Theme.accent2(), on * pa), false);
        float knob = swX + swH / 2f + (swW - swH) * on;
        Render2D.circle(g, knob, swY + swH / 2f, swH / 2f - 1.5f, ColorUtil.mulAlpha(0xFFFFFFFF, pa));

        addHit(new Hit(rx, y, rw, ROW_H - 1, key, b -> {
            if (b == 0 && m instanceof ClickGui) {
                if (!EXPANDED.remove(m)) EXPANDED.add(m); // the menu module just shows its settings
            } else if (b == 0) {
                m.toggle();
                ConfigManager.save();
            } else if (b == 1) {
                if (!EXPANDED.remove(m)) EXPANDED.add(m);
            } else if (b == 2) {
                binding = m;
            }
        }, m.getDescription() + "   • Right-click: settings • Middle-click: bind"));

        y += ROW_H;

        float exNow = ANIMS.getOrDefault("expand:" + m.getName(), 0f);
        if (exNow > 0.001f) {
            float full = settingsBlockHeight(m);
            float visible = full * exNow;
            float top = y;
            g.flush();
            g.enableScissor((int) px, (int) top, (int) (px + PANEL_W), (int) Math.min(clipBottom, Math.ceil(top + visible)));
            Render2D.roundedRect(g, rx, top, rw, full - 2, 5, ColorUtil.mulAlpha(Theme.SETTINGS_BG, pa));
            Render2D.rect(g, rx + 4, top + 2, 1, full - 6, ColorUtil.mulAlpha(ColorUtil.withAlpha(Theme.accent(), 90), pa));
            float sy = top + 3;
            for (Setting<?> s : m.getSettings()) {
                if (!s.isVisible()) continue;
                drawSetting(g, m, s, rx + 8, sy, rw - 12, pa * exNow, dt);
                sy += settingHeight(s);
            }
            drawBindRow(g, m, rx + 8, sy, rw - 12, pa * exNow, dt);
            g.flush();
            g.disableScissor();
            y += visible;
        }
        return y;
    }

    private void drawSetting(GuiGraphics g, Module m, Setting<?> s, float x, float y, float w, float a, float dt) {
        Object key = "set:" + m.getName() + ":" + s.getName();
        float hov = anim("hover:" + key, isHovered(key) ? 1 : 0, 14, dt);
        int label = ColorUtil.lerp(Theme.TEXT_DIM, Theme.TEXT, hov);

        if (s instanceof dev.magma.client.setting.MultiSetting ms) {
            g.drawString(font, s.getName(), (int) x + 2, (int) y + 4, ColorUtil.textAlpha(label, a), false);
            String sum = ms.summary();
            g.drawString(font, sum, (int) (x + w - font.width(sum) - 2), (int) y + 4, ColorUtil.textAlpha(ms.get().isEmpty() ? Theme.DANGER : Theme.TEXT_DIM, a), false);
            List<String> opts = ms.getOptions();
            float cw = (w - 4) / 2f;
            for (int i = 0; i < opts.size(); i++) {
                String o = opts.get(i);
                float cx = x + (i % 2) * (cw + 2);
                float cy = y + SET_H + (i / 2) * 13;
                boolean sel = ms.has(o);
                Object ok = key + ":" + o;
                float oh = anim("hover:" + ok, isHovered(ok) ? 1 : 0, 14, dt);
                Render2D.roundedRect(g, cx, cy, cw, 11, 3, ColorUtil.mulAlpha(ColorUtil.lerp(0xFF1E2230, 0xFF282E3F, oh), a));
                Render2D.roundedRect(g, cx + 2.5f, cy + 2.5f, 6, 6, 1.5f, ColorUtil.mulAlpha(sel ? Theme.accent() : 0xFF343A4A, a));
                g.drawString(font, font.plainSubstrByWidth(o, (int) cw - 13), (int) cx + 11, (int) cy + 2, ColorUtil.textAlpha(sel ? Theme.TEXT : Theme.TEXT_DIM, a), false);
                addHit(new Hit(cx, cy, cw, 11, ok, btn -> { ms.toggle(o); ConfigManager.save(); }, (sel ? "Stop mining " : "Also mine ") + o));
            }
            return;
        }
        if (s instanceof BoolSetting b) {
            float on = anim("bool:" + key, b.get() ? 1 : 0, 14, dt);
            g.drawString(font, s.getName(), (int) x + 2, (int) y + 4, ColorUtil.textAlpha(label, a), false);
            float bx = x + w - 12, by = y + 3;
            Render2D.roundedRect(g, bx, by, 9, 9, 2.5f, ColorUtil.mulAlpha(Theme.TRACK, a));
            if (on > 0.01f) {
                float inset = 4.5f * (1 - on);
                Render2D.roundedGradient(g, bx + inset, by + inset, 9 - inset * 2, 9 - inset * 2, 2.5f,
                        ColorUtil.mulAlpha(Theme.accent(), a), ColorUtil.mulAlpha(Theme.accent2(), a), true);
            }
            if (on > 0.5f) g.drawString(font, "✔", (int) bx + 1, (int) by + 1, ColorUtil.textAlpha(0xFF101010, a * (on - 0.5f) * 2), false);
            addHit(new Hit(x, y, w, SET_H, key, btn -> { b.toggle(); ConfigManager.save(); }, s.getDescription()));
        } else if (s instanceof NumberSetting n) {
            g.drawString(font, s.getName(), (int) x + 2, (int) y + 3, ColorUtil.textAlpha(label, a), false);
            String v = n.display();
            g.drawString(font, v, (int) (x + w - font.width(v) - 2), (int) y + 3, ColorUtil.textAlpha(Theme.accent(), a), false);
            float tx = x + 2, tw = w - 6, ty = y + 15;
            float pct = (float) ((n.get() - n.getMin()) / (n.getMax() - n.getMin()));
            float shown = anim("num:" + key, pct, 18, dt);
            Render2D.roundedRect(g, tx, ty, tw, 3, 1.5f, ColorUtil.mulAlpha(Theme.TRACK, a));
            Render2D.roundedGradient(g, tx, ty, Math.max(3, tw * shown), 3, 1.5f, ColorUtil.mulAlpha(Theme.accent(), a), ColorUtil.mulAlpha(Theme.accent2(), a), false);
            float kr = 3.2f + (slider == n ? 1f : hov * 0.6f);
            Render2D.circle(g, tx + tw * shown, ty + 1.5f, kr + 1, ColorUtil.mulAlpha(ColorUtil.withAlpha(Theme.accent(), 90), a));
            Render2D.circle(g, tx + tw * shown, ty + 1.5f, kr, ColorUtil.mulAlpha(0xFFFFFFFF, a));
            addHit(new Hit(x, y, w, NUM_H, key, btn -> {
                if (btn == 1) { n.reset(); ConfigManager.save(); return; }
                slider = n;
                sliderX = tx;
                sliderW = tw;
                applySlider(mouseX);
            }, s.getDescription() + "  (right-click to reset)"));
        } else if (s instanceof dev.magma.client.setting.TextSetting ts) {
            g.drawString(font, s.getName(), (int) x + 2, (int) y + 4, ColorUtil.textAlpha(label, a), false);
            String v = ts.get().isEmpty() ? "-" : ts.get();
            g.drawString(font, v, (int) (x + w - font.width(v) - 2), (int) y + 4, ColorUtil.textAlpha(Theme.accent(), a), false);
            addHit(new Hit(x, y, w, SET_H, key, btn -> {}, s.getDescription() + " - edit in the Window menu or use .goto x y z in chat"));
        } else if (s instanceof dev.magma.client.setting.ButtonSetting bs) {
            g.drawString(font, s.getName(), (int) x + 2, (int) y + 4, ColorUtil.textAlpha(label, a), false);
            String v = bs.get();
            int vw = font.width(v);
            float bx = x + w - vw - 10;
            Render2D.roundedRect(g, bx - 2, y + 1.5f, vw + 10, 12, 4, ColorUtil.mulAlpha(ColorUtil.lerp(0xFF1E2230, 0xFF282E3F, hov), a));
            g.drawString(font, v, (int) bx + 3, (int) y + 4, ColorUtil.textAlpha(Theme.TEXT, a), false);
            addHit(new Hit(x, y, w, SET_H, key, btn -> { bs.press(); ConfigManager.save(); }, s.getDescription()));
        } else if (s instanceof ModeSetting md) {
            g.drawString(font, s.getName(), (int) x + 2, (int) y + 4, ColorUtil.textAlpha(label, a), false);
            String v = md.get();
            int vw = font.width(v);
            float cx = x + w - vw - 14;
            Render2D.roundedRect(g, cx - 4, y + 1.5f, vw + 18, 12, 4, ColorUtil.mulAlpha(ColorUtil.lerp(0xFF1E2230, 0xFF282E3F, hov), a));
            g.drawString(font, "‹", (int) cx - 1, (int) y + 4, ColorUtil.textAlpha(Theme.TEXT_MUTED, a), false);
            g.drawString(font, v, (int) cx + 5, (int) y + 4, ColorUtil.textAlpha(Theme.accent(), a), false);
            g.drawString(font, "›", (int) (cx + 7 + vw), (int) y + 4, ColorUtil.textAlpha(Theme.TEXT_MUTED, a), false);
            addHit(new Hit(x, y, w, SET_H, key, btn -> { md.cycle(btn == 1 ? -1 : 1); ConfigManager.save(); },
                    s.getDescription() + "  (" + String.join(" / ", md.getModes()) + ")"));
        }
    }

    private void drawBindRow(GuiGraphics g, Module m, float x, float y, float w, float a, float dt) {
        Object key = "bind:" + m.getName();
        float hov = anim("hover:" + key, isHovered(key) ? 1 : 0, 14, dt);
        g.drawString(font, "Keybind", (int) x + 2, (int) y + 4, ColorUtil.textAlpha(ColorUtil.lerp(Theme.TEXT_DIM, Theme.TEXT, hov), a), false);
        boolean listening = binding == m;
        String v = listening ? "..." : keyName(m.getKey());
        int vw = font.width(v);
        float bx = x + w - vw - 10;
        int bg = listening ? ColorUtil.withAlpha(Theme.accent(), 120) : ColorUtil.lerp(0xFF1E2230, 0xFF282E3F, hov);
        Render2D.roundedRect(g, bx - 2, y + 1.5f, vw + 10, 12, 4, ColorUtil.mulAlpha(bg, a));
        g.drawString(font, v, (int) bx + 3, (int) y + 4, ColorUtil.textAlpha(listening ? 0xFFFFFFFF : Theme.TEXT, a), false);
        addHit(new Hit(x, y, w, SET_H, key, btn -> {
            if (btn == 1) { m.setKey(-1); ConfigManager.save(); }
            else binding = m;
        }, "Click then press a key. Right-click to unbind."));
    }

    private void drawTooltip(GuiGraphics g, String text, float alpha) {
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(text), 260);
        int w = 0;
        for (var l : lines) w = Math.max(w, font.width(l));
        int h = lines.size() * 10 + 8;
        float x = width / 2f - (w + 16) / 2f;
        float y = height - h - 12;
        Render2D.shadow(g, x, y, w + 16, h, 7, 6, ColorUtil.mulAlpha(0x60000000, alpha));
        Render2D.roundedRect(g, x, y, w + 16, h, 7, ColorUtil.mulAlpha(0xF0101219, alpha));
        Render2D.roundedOutline(g, x, y, w + 16, h, 7, 1, ColorUtil.mulAlpha(ColorUtil.withAlpha(Theme.accent(), 110), alpha));
        int ly = (int) y + 5;
        for (var l : lines) {
            g.drawString(font, l, (int) (width / 2f - font.width(l) / 2f), ly, ColorUtil.textAlpha(Theme.TEXT, alpha), false);
            ly += 10;
        }
    }

    private void drawBindOverlay(GuiGraphics g, float alpha) {
        String t = "Press a key to bind " + binding.getName();
        String s = "ESC to cancel • DELETE to unbind";
        int w = Math.max(font.width(t), font.width(s)) + 30;
        float x = width / 2f - w / 2f, y = height / 2f - 22;
        Render2D.shadow(g, x, y, w, 40, 9, 12, ColorUtil.withAlpha(Theme.accent(), 90));
        Render2D.roundedRect(g, x, y, w, 40, 9, 0xF5101219);
        Render2D.roundedGradient(g, x, y, w, 2.5f, 1.2f, Theme.accent(), Theme.accent2(), false);
        g.drawString(font, t, (int) (width / 2f - font.width(t) / 2f), (int) y + 10, Theme.TEXT, false);
        g.drawString(font, s, (int) (width / 2f - font.width(s) / 2f), (int) y + 24, Theme.TEXT_MUTED, false);
    }

    private static String keyName(int key) {
        if (key == -1) return "None";
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) return "RShift";
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) return "LShift";
        if (key == GLFW.GLFW_KEY_RIGHT_CONTROL) return "RCtrl";
        if (key == GLFW.GLFW_KEY_LEFT_CONTROL) return "LCtrl";
        if (key == GLFW.GLFW_KEY_RIGHT_ALT) return "RAlt";
        if (key == GLFW.GLFW_KEY_LEFT_ALT) return "LAlt";
        return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
    }

    private void applySlider(double mx) {
        if (slider == null) return;
        double pct = Math.max(0, Math.min(1, (mx - sliderX) / sliderW));
        slider.set(slider.getMin() + pct * (slider.getMax() - slider.getMin()));
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (binding != null) {
            binding = null;
            return true;
        }
        boolean wasSearch = searchFocus;
        searchFocus = false;
        for (int i = lastHits.size() - 1; i >= 0; i--) {
            Hit h = lastHits.get(i);
            if (h.contains(mx, my)) {
                h.onClick.accept(button);
                return true;
            }
        }
        if (wasSearch && search.isEmpty()) searchFocus = false;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            float[] lay = LAYOUT.get(dragging);
            lay[0] = (float) Math.max(0, Math.min(width - PANEL_W, mx - dragDX));
            lay[1] = (float) Math.max(-scroll, Math.min(height - HEADER_H - scroll, my - dragDY));
            return true;
        }
        if (slider != null) {
            applySlider(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging != null || slider != null) {
            dragging = null;
            slider = null;
            ConfigManager.save();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        for (int i = ORDER.size() - 1; i >= 0; i--) {
            Category c = ORDER.get(i);
            float[] r = panelRects.get(c);
            if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                if (r[4] > 0) {
                    PANEL_SCROLL.put(c, Math.max(0, Math.min(r[4], PANEL_SCROLL.getOrDefault(c, 0f) - (float) sy * 20f)));
                    return true;
                }
                break;
            }
        }
        scrollTarget += (float) sy * 22f;
        scrollTarget = Math.min(0, Math.max(-600, scrollTarget));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (binding != null) {
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) binding.setKey(-1);
            else if (key != GLFW.GLFW_KEY_ESCAPE) binding.setKey(key);
            binding = null;
            ConfigManager.save();
            return true;
        }
        if (searchFocus) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
                searchFocus = false;
                if (key == GLFW.GLFW_KEY_ESCAPE) search = "";
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_F && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
            searchFocus = true;
            return true;
        }
        if (key == ModuleManager.get(ClickGui.class).getKey() && !searchFocus) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (binding != null) return true;
        if (!searchFocus && Character.isLetterOrDigit(c)) {
            searchFocus = true; // start typing anywhere to search
        }
        if (searchFocus && c >= 32 && search.length() < 24) {
            search += c;
            return true;
        }
        return super.charTyped(c, mods);
    }

    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        dragging = null;
        slider = null;
        ConfigManager.save();
    }

    @Override
    public void removed() {
        ConfigManager.save();
    }

    // ------------------------------------------------------------ persistence

    public static JsonObject saveLayout() {
        JsonObject o = new JsonObject();
        for (Category c : Category.values()) {
            float[] l = LAYOUT.get(c);
            JsonObject p = new JsonObject();
            p.addProperty("x", l[0]);
            p.addProperty("y", l[1]);
            p.addProperty("collapsed", l[2] > 0.5f);
            o.add(c.name(), p);
        }
        return o;
    }

    public static void loadLayout(JsonObject o) {
        for (Category c : Category.values()) {
            if (!o.has(c.name())) continue;
            JsonObject p = o.getAsJsonObject(c.name());
            float[] l = LAYOUT.get(c);
            if (p.has("x")) l[0] = p.get("x").getAsFloat();
            if (p.has("y")) l[1] = p.get("y").getAsFloat();
            if (p.has("collapsed")) l[2] = p.get("collapsed").getAsBoolean() ? 1 : 0;
        }
    }
}
