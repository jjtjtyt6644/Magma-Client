package dev.magma.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.magma.client.MagmaClient;
import dev.magma.client.config.ConfigManager;
import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.modules.ClickGui;
import dev.magma.client.modules.Hud;
import dev.magma.client.modules.LavaGuard;
import dev.magma.client.modules.AutoMiner;
import dev.magma.client.modules.VillagerRoller;
import dev.magma.client.modules.Xray;
import dev.magma.client.modules.Builder;
import net.minecraft.core.BlockPos;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.ModeSetting;
import dev.magma.client.setting.NumberSetting;
import dev.magma.client.setting.Setting;
import dev.magma.client.util.Anim;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.Render2D;
import dev.magma.client.util.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Magma main menu: slim single window with a sidebar.
 * Home = active cheats + live status, category pages = compact module rows with inline settings,
 * Appearance = theme, HUD, lava indicators, miner loadout, X-Ray look.
 */
public class MenuScreen extends Screen {
    // layout
    private static final int SIDEBAR = 112, NAV_H = 18, NAV_GAP = 2, R = 7, ROW = 28, ROW_R = 5;
    // palette (flat, low contrast surfaces)
    private static final int WIN = 0xF70E1015, SIDE = 0xFF0B0C10, SURFACE = 0xFF14161C, SURFACE_HI = 0xFF1A1D24,
            INSET = 0xFF0F1116, BORDER = 0x12FFFFFF, CHIP = 0xFF1C1F27;

    private record Page(String title, String subtitle, String icon, Category cat) {}

    private static final List<Page> PAGES = new ArrayList<>();

    static {
        PAGES.add(new Page("Home", "Active cheats and live status", "⌂", null));
        PAGES.add(new Page("Safety", "Lava protection", Category.SAFETY.icon, Category.SAFETY));
        PAGES.add(new Page("World", "Auto mining", Category.WORLD.icon, Category.WORLD));
        PAGES.add(new Page("Movement", "Fly, no fall, freecam", Category.MOVEMENT.icon, Category.MOVEMENT));
        PAGES.add(new Page("Combat", "Auto fighting, player ESP", Category.COMBAT.icon, Category.COMBAT));
        PAGES.add(new Page("Automation", "Villager rolling, fast break, tools, food", Category.AUTOMATION.icon, Category.AUTOMATION));
        PAGES.add(new Page("Render", "X-Ray and vision", Category.RENDER.icon, Category.RENDER));
        PAGES.add(new Page("Client", "HUD and menu", Category.CLIENT.icon, Category.CLIENT));
        PAGES.add(new Page("Settings", "Theme, menu and updates", "⚙", null));
    }

    private static int page = 0;
    private static final float[] PAGE_SCROLL = new float[16];
    private static final Set<Module> EXPANDED = new HashSet<>();
    private static final Map<Object, Float> ANIMS = new HashMap<>();

    private record Hit(float x, float y, float w, float h, Object key, IntConsumer onClick, String tooltip) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private List<Hit> lastHits = new ArrayList<>();
    private Object hovered;
    private String tooltip;
    private long hoverSince;
    private Object lastHovered;
    private final Anim.Clock clock = new Anim.Clock();

    private float open, pageAnim = 1f, navY = -1;
    /** When the current page appeared (rows cascade in from here) and which row is being drawn. */
    private long pageShownAt = System.currentTimeMillis();
    private int rowSeq;
    private static final Map<String, Long> FLASH = new HashMap<>();
    private boolean closing;
    private float scroll, scrollTarget, contentH, viewH;
    private float clipX, clipY, clipW, clipH;
    private NumberSetting slider;
    private float sliderX, sliderW;
    private Module binding;
    private dev.magma.client.setting.TextSetting editing;
    /** Just focused: the next typed character replaces the whole value (like select-all). */
    private boolean editingFresh;
    private String search = "";
    private boolean searchFocus;
    private int mouseX, mouseY;
    private float alpha;
    private float dt;

    /** Dev self-test only: jump to a page (and optionally expand a module). */
    public static void testPage(int p, Module expand) {
        page = p;
        EXPANDED.clear();
        if (expand != null) EXPANDED.add(expand);
    }

    public MenuScreen() {
        super(Component.literal("Magma"));
    }

    @Override public boolean isPauseScreen() { return false; }

    private ClickGui cfg() { return ModuleManager.get(ClickGui.class); }

    // ================================================================= helpers

    private float anim(Object key, float target, float speed) {
        float v = ANIMS.getOrDefault(key, target);
        v = Anim.approach(v, target, dt * speed);
        ANIMS.put(key, v);
        return v;
    }

    private boolean hov(Object key) { return key.equals(hovered); }

    private int a(int color) { return ColorUtil.mulAlpha(color, alpha); }

    private int ta(int color) { return ColorUtil.textAlpha(color, alpha * ColorUtil.alpha(color) / 255f); }

    private void text(GuiGraphics g, String s, float x, float y, int color) {
        g.drawString(font, s, Math.round(x), Math.round(y), ta(color), false);
    }

    private String clip(String s, int w) {
        if (w <= 8) return "";
        if (font.width(s) <= w) return s;
        return font.plainSubstrByWidth(s, w - font.width("…")) + "…";
    }

    private void chit(float x, float y, float w, float h, Object key, IntConsumer c, String tip) {
        float x0 = Math.max(x, clipX), y0 = Math.max(y, clipY);
        float x1 = Math.min(x + w, clipX + clipW), y1 = Math.min(y + h, clipY + clipH);
        if (x1 <= x0 || y1 <= y0) return;
        hits.add(new Hit(x0, y0, x1 - x0, y1 - y0, key, c, tip));
    }

    private void hit(float x, float y, float w, float h, Object key, IntConsumer c, String tip) {
        hits.add(new Hit(x, y, w, h, key, c, tip));
    }

    /** Slim pill switch (16x8). */
    private void drawSwitch(GuiGraphics g, float x, float y, float on) {
        float w = 16, h = 8;
        Render2D.roundedRect(g, x, y, w, h, h / 2f, a(0xFF2A2E38));
        if (on > 0.01f) Render2D.roundedRect(g, x, y, w, h, h / 2f, a(ColorUtil.mulAlpha(Theme.accent(), on)));
        Render2D.circle(g, x + h / 2f + (w - h) * on, y + h / 2f, h / 2f - 1.5f, a(ColorUtil.lerp(0xFF9AA0AE, 0xFFFFFFFF, on)));
    }

    private static String keyName(int key) {
        if (key == -1) return "None";
        return switch (key) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LShift";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCtrl";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RAlt";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LAlt";
            default -> InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        };
    }

    // ================================================================= frame

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        if (cfg().blur.get()) renderBlurredBackground(pt);
        float e = Anim.easeOutCubic(open);
        Render2D.rect(g, 0, 0, width, height, ColorUtil.mulAlpha(0x8C06070A, e));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        dt = clock.tick();
        mouseX = mx;
        mouseY = my;
        open = Anim.approach(open, closing ? 0 : 1, dt * 12f);
        if (closing && open < 0.03f) {
            super.onClose();
            return;
        }
        pageAnim = Anim.approach(pageAnim, 1, dt * 10f);
        scroll = Anim.approach(scroll, scrollTarget, dt * 18f);

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
        if (hovered == null || !hovered.equals(lastHovered)) hoverSince = System.currentTimeMillis();
        lastHovered = hovered;
        hits.clear();
        if (slider != null) applySlider(mx);

        renderBackground(g, mx, my, pt);
        alpha = Anim.easeOutCubic(open);

        float W = Math.min(560, width - 40), H = Math.min(330, height - 40);
        float wx = Math.round((width - W) / 2f), wy = Math.round((height - H) / 2f + (1 - alpha) * 10);

        // springs open: a slight zoom with a little overshoot
        float sc = 0.94f + 0.06f * easeOutBack(open);
        g.pose().pushPose();
        g.pose().translate(wx + W / 2f, wy + H / 2f, 0);
        g.pose().scale(sc, sc, 1f);
        g.pose().translate(-(wx + W / 2f), -(wy + H / 2f), 0);
        rowSeq = 0;
        Render2D.shadow(g, wx, wy, W, H, R, 10, a(0x80000000));
        Render2D.roundedRect(g, wx, wy, W, H, R, a(WIN));
        Render2D.roundedRect(g, wx, wy, SIDEBAR, H, R, a(SIDE));
        Render2D.rect(g, wx + SIDEBAR - R, wy, R, H, a(SIDE));
        Render2D.rect(g, wx + SIDEBAR, wy, 1, H, a(BORDER));
        Render2D.roundedOutline(g, wx, wy, W, H, R, 1, a(0x16FFFFFF));
        hit(wx, wy, W, H, "window", b -> {}, null);

        drawSidebar(g, wx, wy, H);
        drawContent(g, wx + SIDEBAR + 1, wy, W - SIDEBAR - 1, H);
        g.pose().popPose();

        // delayed, small tooltip
        if (tooltip != null && cfg().descriptions.get() && slider == null && System.currentTimeMillis() - hoverSince > 350) drawTooltip(g, tooltip);
        if (binding != null) drawBindOverlay(g);
        g.flush();
        lastHits = new ArrayList<>(hits);
    }

    // ================================================================= sidebar

    private void drawSidebar(GuiGraphics g, float x, float y, float h) {
        // brand
        float lx = x + 12, ly = y + 12;
        dev.magma.client.util.Logo.draw(g, lx - 1, ly - 2, 16, alpha);
        Render2D.gradientText(g, MagmaClient.NAME, lx + 19, ly + 2, alpha, false);

        float ny = y + 36;
        // more pages than fit: squeeze the spacing so the last one never runs into the footer
        float step = Math.min(NAV_H + NAV_GAP, (h - 36 - 30) / PAGES.size());
        float navH = Math.min(NAV_H, step - 1);
        float targetY = ny + page * step;
        if (navY < 0) navY = targetY;
        navY = Anim.approach(navY, targetY, dt * 16f);
        Render2D.roundedRect(g, x + 6, navY, SIDEBAR - 12, navH, 4, a(0x14FFFFFF));
        Render2D.roundedRect(g, x + 6, navY + 4, 2, navH - 8, 1, a(Theme.accent()));

        for (int i = 0; i < PAGES.size(); i++) {
            Page p = PAGES.get(i);
            float iy = ny + i * step;
            if (i == PAGES.size() - 1) {
                // divider before Appearance
                Render2D.rect(g, x + 12, iy - 1, SIDEBAR - 24, 1, a(BORDER));
            }
            Object key = "nav:" + i;
            float hv = anim(key, hov(key) ? 1 : 0, 16);
            boolean sel = i == page;
            if (!sel && hv > 0.01f) Render2D.roundedRect(g, x + 6, iy, SIDEBAR - 12, navH, 4, a(ColorUtil.mulAlpha(0x0AFFFFFF, hv)));
            text(g, p.icon, x + 14, iy + (navH - 8) / 2f, sel ? Theme.accent() : 0xFF5C6373);
            text(g, p.title, x + 27, iy + (navH - 8) / 2f, sel ? Theme.TEXT : ColorUtil.lerp(0xFF8B92A1, Theme.TEXT, hv));
            if (p.cat != null) {
                int on = 0;
                for (Module m : ModuleManager.byCategory(p.cat)) if (m.isEnabled()) on++;
                if (on > 0) {
                    String c = String.valueOf(on);
                    text(g, c, x + SIDEBAR - 14 - font.width(c), iy + (navH - 8) / 2f, sel ? Theme.accent() : 0xFF5C6373);
                }
            }
            final int idx = i;
            hit(x + 6, iy, SIDEBAR - 12, navH, key, b -> switchPage(idx), null);
        }

        // footer
        float fy = y + h - 22;
        Object pk = "panels";
        float hv = anim(pk, hov(pk) ? 1 : 0, 16);
        text(g, "▦ Panels", x + 12, fy + 4, ColorUtil.lerp(0xFF5C6373, Theme.TEXT, hv));
        hit(x + 6, fy, font.width("▦ Panels") + 12, 16, pk, b -> {
            cfg().style.set("Panels");
            ConfigManager.save();
            minecraft.setScreen(new ClickGuiScreen());
        }, "Switch to floating panels");
        text(g, "v" + MagmaClient.VERSION, x + SIDEBAR - 12 - font.width("v" + MagmaClient.VERSION), fy + 4, 0xFF3E4452);
    }

    private static float easeOutBack(float t) {
        t = Math.max(0, Math.min(1, t));
        float c1 = 1.70158f, c3 = c1 + 1, u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    private void switchPage(int idx) {
        if (idx == page) return;
        pageShownAt = System.currentTimeMillis();
        PAGE_SCROLL[page] = scrollTarget;
        page = idx;
        pageAnim = 0;
        scroll = scrollTarget = PAGE_SCROLL[idx];
        searchFocus = false;
    }

    // ================================================================= content

    private void drawContent(GuiGraphics g, float x, float y, float w, float h) {
        Page p = PAGES.get(page);
        float pad = 14;
        float off = (1 - Anim.easeOutCubic(pageAnim)) * 8;
        float saved = alpha;
        alpha *= 0.4f + 0.6f * pageAnim;

        g.pose().pushPose();
        g.pose().translate(x + pad + off, y + 12, 0);
        g.pose().scale(1.2f, 1.2f, 1f);
        g.drawString(font, p.title, 0, 0, ta(Theme.TEXT), false);
        g.pose().popPose();
        text(g, p.subtitle, x + pad + off + font.width(p.title) * 1.2f + 8, y + 14, 0xFF5C6373);

        if (p.cat != null || page == 0) drawSearch(g, x + w - pad - 110, y + 10, 110);
        Render2D.rect(g, x + pad, y + 32, w - pad * 2, 1, a(BORDER));

        clipX = x + 4;
        clipY = y + 34;
        clipW = w - 8;
        clipH = h - 50;
        Render2D.rect(g, x + pad, y + h - 15, w - pad * 2, 1, a(BORDER));
        String hint = binding != null ? "Press a key · Esc cancel · Delete unbind"
                : "Click toggle · Right-click settings · Middle-click bind · Shift+scroll adjust · Tab next page";
        text(g, clip(hint, (int) (w - pad * 2)), x + pad, y + h - 11, 0xFF454B58);
        viewH = clipH;
        g.flush();
        g.enableScissor((int) clipX, (int) clipY, (int) (clipX + clipW), (int) (clipY + clipH));
        float cx = x + pad + off, cw = w - pad * 2;
        multiCols = cw - 24 >= 330 ? 3 : 2;
        float top = clipY + 8 + scroll;
        float end;
        if (page == 0) end = drawHome(g, cx, top, cw);
        else if (p.cat != null) end = drawCategory(g, p.cat, cx, top, cw);
        else end = drawAppearance(g, cx, top, cw);
        g.flush();
        g.disableScissor();

        contentH = end - top + 8;
        float minScroll = Math.min(0, viewH - contentH);
        scrollTarget = Math.max(minScroll, Math.min(0, scrollTarget));
        if (contentH > viewH) {
            float barH = Math.max(16, viewH * viewH / contentH);
            float barY = clipY + (-scroll / (contentH - viewH)) * (viewH - barH);
            Render2D.roundedRect(g, x + w - 4, barY, 2, barH, 1, a(0x30FFFFFF));
        }
        alpha = saved;
    }

    private void drawSearch(GuiGraphics g, float x, float y, float w) {
        Object key = "search";
        float hv = anim(key, hov(key) || searchFocus ? 1 : 0, 14);
        Render2D.roundedRect(g, x, y, w, 14, 4, a(ColorUtil.lerp(SURFACE, SURFACE_HI, hv)));
        Render2D.roundedOutline(g, x, y, w, 14, 4, 1, a(searchFocus ? ColorUtil.withAlpha(Theme.accent(), 160) : BORDER));
        boolean empty = search.isEmpty();
        String s = empty && !searchFocus ? "Search" : search + ((System.currentTimeMillis() / 500) % 2 == 0 && searchFocus ? "_" : "");
        text(g, "⌕", x + 5, y + 3, 0xFF5C6373);
        text(g, clip(s, (int) w - 22), x + 15, y + 3, empty && !searchFocus ? 0xFF5C6373 : Theme.TEXT);
        hit(x, y, w, 14, key, b -> {
            searchFocus = true;
            if (b == 1) search = "";
        }, null);
    }

    private boolean matches(Module m) {
        if (search.isEmpty()) return true;
        String s = search.toLowerCase();
        return m.getName().toLowerCase().contains(s) || m.getDescription().toLowerCase().contains(s);
    }

    private float section(GuiGraphics g, String title, float x, float y, float w) {
        text(g, title.toUpperCase(), x, y, 0xFF5C6373);
        return y + 13;
    }

    // ----------------------------------------------------------------- home

    private float drawHome(GuiGraphics g, float x, float y, float w) {
        if (!search.isEmpty()) {
            y = section(g, "Results", x, y, w);
            for (Module m : ModuleManager.all()) if (matches(m)) y = drawModuleRow(g, m, x, y, w) + 3;
            return y;
        }

        List<Module> active = new ArrayList<>();
        for (Module m : ModuleManager.all()) if (m.isEnabled() && m != cfg()) active.add(m);

        y = drawUpdateBanner(g, x, y, w);
        y = drawQuickActions(g, x, y, w);

        // stat strip
        LavaGuard lg = ModuleManager.get(LavaGuard.class);
        AutoMiner qm = ModuleManager.get(AutoMiner.class);
        double ld = lg.nearestLavaDistance();
        String[][] stats = {
                {"Active", String.valueOf(active.size())},
                {"Nearest lava", ld < 0 ? "—" : String.format("%.1fm", ld)},
                {"Total mined", String.valueOf(qm.getTotalMined())},
        };
        float sw = (w - 12) / 3f;
        for (int i = 0; i < stats.length; i++) {
            float sx = x + i * (sw + 6);
            Render2D.roundedRect(g, sx, y, sw, 30, ROW_R, a(SURFACE));
            text(g, stats[i][0], sx + 9, y + 6, 0xFF5C6373);
            int col = i == 1 && ld >= 0 && ld < 3 ? Theme.DANGER : Theme.TEXT;
            text(g, clip(stats[i][1], (int) sw - (i == 2 ? 52 : 14)), sx + 9, y + 17, col);
            if (i == 2) {
                Object rk = "resetTotal";
                float rh = anim(rk, hov(rk) ? 1 : 0, 16);
                String rl = "reset";
                float rx = sx + sw - 9 - font.width(rl);
                text(g, rl, rx, y + 17, ColorUtil.lerp(0xFF4A505D, Theme.accent(), rh));
                chit(rx - 4, y + 14, font.width(rl) + 8, 14, rk, b -> qm.resetTotal(), "Reset the all-time ore counter");
            }
        }
        y += 38;

        // active cheats
        y = section(g, "Active (" + active.size() + ")", x, y, w);
        if (active.isEmpty()) {
            text(g, "Nothing running.", x, y + 2, 0xFF5C6373);
            y += 16;
        } else {
            float colW = (w - 6) / 2f;
            for (int i = 0; i < active.size(); i++) {
                Module m = active.get(i);
                drawActiveRow(g, m, x + (i % 2) * (colW + 6), y + (i / 2) * 25, colW);
            }
            y += ((active.size() + 1) / 2) * 25 + 6;
        }

        y = drawProgress(g, x, y, w);

        // live status
        y = section(g, "Status", x, y, w);
        VillagerRoller vr = ModuleManager.get(VillagerRoller.class);
        Xray xr = ModuleManager.get(Xray.class);
        StringBuilder sb = new StringBuilder();
        if (xr.isEnabled()) {
            int n = 0;
            for (var e : xr.counts().entrySet()) {
                if (n++ >= 3) break;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(e.getValue()).append(' ').append(e.getKey().replace(" Ore", "").replace("Nether ", ""));
            }
        }
        String[][] rows = {
                {"LavaGuard", lg.isEnabled() ? (ld < 0 ? "Protecting · no lava nearby" : String.format("Protecting · lava %.1fm", ld)) : "Off"},
                {"AutoMiner", qm.isEnabled() ? qm.speed.get() + " · " + qm.getStatus() + " · " + qm.getMined() + " this run" : "Off"},
                {"VillagerRoller", vr.isEnabled() ? vr.getStatus() + " · " + vr.getAttempts() + " rolls"
                        : vr.getHistory().isEmpty() ? "Off" : "Off · last " + vr.getHistory().peekFirst()},
                {"X-Ray", xr.isEnabled() ? (sb.length() == 0 ? "No ores in range" : sb.toString()) : "Off"},
        };
        Render2D.roundedRect(g, x, y, w, rows.length * 18 + 4, ROW_R, a(SURFACE));
        for (int i = 0; i < rows.length; i++) {
            float ry = y + 2 + i * 18;
            if (i > 0) Render2D.rect(g, x + 8, ry, w - 16, 1, a(0x0AFFFFFF));
            boolean off = rows[i][1].startsWith("Off");
            Render2D.circle(g, x + 11, ry + 9, 2, a(off ? 0xFF3A3F4B : Theme.OK));
            text(g, rows[i][0], x + 19, ry + 5, Theme.TEXT_DIM);
            text(g, clip(rows[i][1], (int) (w - 120)), x + 110, ry + 5, off ? 0xFF5C6373 : Theme.TEXT);
        }
        y += rows.length * 18 + 12;

        return y;
    }

    private double gotoStartDist = -1;
    private BlockPos gotoFor;

    /** Bars for anything that's working towards a goal: the build, a Goto trip, ores X-Ray can see. */
    private float drawProgress(GuiGraphics g, float x, float y, float w) {
        List<Object[]> bars = new ArrayList<>(); // label, detail, fraction, colour
        Builder b = ModuleManager.get(Builder.class);
        float bp = b.progress();
        if (bp >= 0) bars.add(new Object[]{"Building " + b.schematicName(), (int) (bp * 100) + "% · " + b.blocksLeft() + " blocks left · " + b.status(), bp, Theme.accent()});
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        BlockPos gt = am.getGotoTarget();
        if (gt != null && minecraft.player != null) {
            double d = Math.sqrt(minecraft.player.blockPosition().distSqr(gt));
            if (!gt.equals(gotoFor) || d > gotoStartDist) { gotoFor = gt; gotoStartDist = Math.max(1, d); }
            float f = (float) Math.max(0, Math.min(1, 1 - d / gotoStartDist));
            bars.add(new Object[]{am.tripLabel + " → " + gt.toShortString(), String.format("%.0fm to go", d), f, Theme.OK});
        } else gotoFor = null;
        VillagerRoller vr = ModuleManager.get(VillagerRoller.class);
        if (vr.isEnabled()) bars.add(new Object[]{"VillagerRoller", vr.getAttempts() + " rolls · " + vr.getStatus(), -1f, 0xFFFFB74D});
        Xray xr = ModuleManager.get(Xray.class);
        var counts = xr.isEnabled() ? xr.counts() : java.util.Map.<String, Integer>of();
        if (bars.isEmpty() && counts.isEmpty()) return y;
        y = section(g, "Progress", x, y, w);
        long t = System.currentTimeMillis();
        for (Object[] bar : bars) {
            float f = (float) bar[2];
            int col = (int) bar[3];
            Render2D.roundedRect(g, x, y, w, 30, ROW_R, a(SURFACE));
            text(g, clip((String) bar[0], (int) w / 2), x + 9, y + 5, Theme.TEXT);
            String det = clip((String) bar[1], (int) w / 2 - 14);
            text(g, det, x + w - 9 - font.width(det), y + 5, 0xFF8A92A3);
            float bx = x + 9, bw = w - 18, by = y + 19;
            Render2D.roundedRect(g, bx, by, bw, 5, 2, a(0x22FFFFFF));
            if (f >= 0) {
                Object k = "prog:" + bar[0];
                float shown = anim(k, f, 6); // slides to the new value
                Render2D.roundedRect(g, bx, by, Math.max(5, bw * shown), 5, 2, a(col));
            } else {
                // no end in sight: a sliding stripe shows it's working
                float p = (t % 1600) / 1600f;
                Render2D.roundedRect(g, bx + (bw - bw * 0.25f) * p, by, bw * 0.25f, 5, 2, a(col));
            }
            y += 34;
        }
        if (!counts.isEmpty()) {
            // ores X-Ray sees right now, as a small bar chart
            int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            List<java.util.Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
            list.sort((p1, p2) -> p2.getValue() - p1.getValue());
            if (list.size() > 6) list = list.subList(0, 6);
            float rh = 13;
            Render2D.roundedRect(g, x, y, w, list.size() * rh + 22, ROW_R, a(SURFACE));
            text(g, "Ores X-Ray can see", x + 9, y + 5, Theme.TEXT_DIM);
            float ly = y + 18;
            for (var e : list) {
                String name = e.getKey().replace(" Ore", "");
                text(g, clip(name, 90), x + 9, ly + 2, 0xFFAEB6C4);
                float bx = x + 105, bw = w - 105 - 40;
                float f = anim("ore:" + e.getKey(), e.getValue() / (float) max, 8);
                Render2D.roundedRect(g, bx, ly + 3, Math.max(3, bw * f), 6, 2, a(oreColor(e.getKey())));
                text(g, String.valueOf(e.getValue()), x + w - 34, ly + 2, Theme.TEXT);
                ly += rh;
            }
            y += list.size() * rh + 28;
        }
        return y;
    }

    private static int oreColor(String name) {
        String n = name.toLowerCase();
        if (n.contains("diamond")) return 0xFF4DD0E1;
        if (n.contains("emerald")) return 0xFF66BB6A;
        if (n.contains("gold")) return 0xFFFFD54F;
        if (n.contains("iron")) return 0xFFD7A98C;
        if (n.contains("redstone")) return 0xFFEF5350;
        if (n.contains("lapis")) return 0xFF5C6BC0;
        if (n.contains("copper")) return 0xFFE0874F;
        if (n.contains("coal")) return 0xFF8D8D8D;
        if (n.contains("debris")) return 0xFFA1887F;
        if (n.contains("quartz")) return 0xFFEEEEEE;
        return Theme.accent();
    }

    /** Big, obvious buttons for the three things people do most. */
    private float drawQuickActions(GuiGraphics g, float x, float y, float w) {
        y = section(g, "Quick actions", x, y, w);
        AutoMiner am = ModuleManager.get(AutoMiner.class);
        String[][] acts = {
                {am.isEnabled() ? "\u25A0 Stop mining" : "\u26CF Start mining", am.isEnabled() ? am.getStatus() : "Your ore list"},
                {"\u279C Go to XYZ", "Safe auto-walk"},
                {"\u2316 Find structure", "Fortress, village\u2026"},
        };
        float bw = (w - 12) / 3f;
        for (int i = 0; i < 3; i++) {
            float bx = x + i * (bw + 6);
            Object key = "qa:" + i;
            float hv = anim(key, hov(key) ? 1 : 0, 16);
            boolean hot = i == 0 && am.isEnabled();
            Render2D.roundedRect(g, bx, y, bw, 34, ROW_R, a(ColorUtil.lerp(SURFACE, SURFACE_HI, hv)));
            Render2D.roundedOutline(g, bx, y, bw, 34, ROW_R, 1, a(hot ? ColorUtil.withAlpha(Theme.accent(), 200) : ColorUtil.mulAlpha(ColorUtil.withAlpha(Theme.accent(), 140), hv)));
            text(g, clip(acts[i][0], (int) bw - 14), bx + 8, y + 7, hot ? Theme.accent() : Theme.TEXT);
            text(g, clip(acts[i][1], (int) bw - 14), bx + 8, y + 20, 0xFF5C6373);
            final int which = i;
            chit(bx, y, bw, 34, key, b -> {
                if (which == 0) {
                    am.toggle();
                    ConfigManager.save();
                } else {
                    Module target = which == 1 ? ModuleManager.get(dev.magma.client.modules.Goto.class) : ModuleManager.get(dev.magma.client.modules.AutoFind.class);
                    switchPage(pageOf(target));
                    EXPANDED.clear();
                    EXPANDED.add(target);
                }
            }, which == 0 ? "Start/stop AutoMiner" : which == 1 ? "Open Goto: type X Y Z, then switch it on" : "Open AutoFind: pick a structure, then switch it on");
        }
        return y + 42;
    }

    /** Shown at the top of Home when GitHub has a newer version. One click updates. */
    private float drawUpdateBanner(GuiGraphics g, float x, float y, float w) {
        var U = dev.magma.client.util.Updater.class;
        var st = dev.magma.client.util.Updater.state();
        boolean show = (st == dev.magma.client.util.Updater.State.AVAILABLE || st == dev.magma.client.util.Updater.State.DOWNLOADING
                || st == dev.magma.client.util.Updater.State.READY) && !dev.magma.client.util.Updater.dismissed();
        if (!show) return y;
        float h = 46;
        boolean ready = st == dev.magma.client.util.Updater.State.READY;
        boolean downloading = st == dev.magma.client.util.Updater.State.DOWNLOADING;
        int accent = ready ? Theme.OK : Theme.accent();

        // card
        Render2D.roundedRect(g, x, y, w, h, ROW_R + 1, a(SURFACE));
        Render2D.roundedGradient(g, x, y, w * 0.55f, h, ROW_R + 1, a(ColorUtil.withAlpha(accent, 46)), 0x00000000, false);
        Render2D.roundedOutline(g, x, y, w, h, ROW_R + 1, 1, a(ColorUtil.withAlpha(accent, 120)));
        dev.magma.client.util.Logo.draw(g, x + 10, y + 11, 24, alpha);

        // layout: [logo] [title / version / what's new or progress] ......... [primary button / Later]
        String primary = ready ? "Restart now" : "Update & restart";
        float pw = font.width(primary) + 18;
        float btnArea = pw + 14;
        float tx = x + 42;
        float textW = w - (tx - x) - btnArea - 6;

        String title = ready ? "Ready to install" : downloading ? "Downloading update" : "Update available";
        text(g, clip(title, (int) textW), tx, y + 8, Theme.TEXT);
        String ver = "v" + dev.magma.client.util.Updater.installed() + " \u2192 v" + dev.magma.client.util.Updater.latest();
        text(g, clip(ver, (int) textW), tx, y + 20, accent);
        float after = tx + font.width(ver) + 6;
        if (downloading) {
            float p = dev.magma.client.util.Updater.progress();
            Render2D.roundedRect(g, tx, y + 34, textW, 3, 1.5f, a(0xFF2A2E38));
            Render2D.roundedRect(g, tx, y + 34, Math.max(3, textW * p), 3, 1.5f, a(accent));
            long t = dev.magma.client.util.Updater.total(), d = dev.magma.client.util.Updater.downloaded();
            String info = Math.round(p * 100) + "%" + (t > 0 ? String.format(" \u00B7 %.1f/%.1f MB", d / 1048576.0, t / 1048576.0) : "");
            if (after + font.width(info) < tx + textW) text(g, info, after, y + 20, 0xFF6B7282);
        } else {
            String sub = ready ? (cfg().reopenAfterUpdate.get() ? "Saves, closes and reopens by itself in seconds" : "Restarts in seconds, your world is saved first")
                    : dev.magma.client.util.Updater.notes().isEmpty() ? "A new version of Magma is out" : dev.magma.client.util.Updater.notes();
            text(g, clip(sub, (int) textW), tx, y + 32, 0xFF8B92A1);
        }

        float bx = x + w - 10;
        if (downloading) {
            String t = dev.magma.client.util.Updater.quitting() ? "Closes when done" : "Downloading\u2026";
            text(g, t, bx - font.width(t), y + 19, 0xFF8B92A1);
        } else {
            Object pk = "upd:primary";
            float ph = anim(pk, hov(pk) ? 1 : 0, 16);
            float px = bx - pw;
            Render2D.roundedRect(g, px, y + 7, pw, 16, 5, a(ColorUtil.lerp(accent, ColorUtil.lerp(accent, 0xFFFFFFFF, 0.25f), ph)));
            text(g, primary, px + pw / 2f - font.width(primary) / 2f, y + 11, 0xFF120A06);
            chit(px, y + 7, pw, 16, pk, b -> dev.magma.client.util.Updater.installAndRestart(),
                    "Saves your world, closes Minecraft and swaps in the new Magma (the old one is removed). Then press Play again.");
            Object lk = "upd:later";
            float lh = anim(lk, hov(lk) ? 1 : 0, 16);
            String later = "Later";
            float lx = px + pw / 2f - font.width(later) / 2f;
            text(g, later, lx, y + 29, ColorUtil.lerp(0xFF6B7282, Theme.TEXT, lh));
            chit(lx - 6, y + 26, font.width(later) + 12, 14, lk, b -> dev.magma.client.util.Updater.dismiss(),
                    "Hide this for now (Settings > Updates still has it)");
        }
        return y + h + 8;
    }

    private int pageOf(Module m) {
        for (int i = 0; i < PAGES.size(); i++) if (PAGES.get(i).cat == m.getCategory()) return i;
        return 0;
    }

    private void drawActiveRow(GuiGraphics g, Module m, float x, float y, float w) {
        Object key = "active:" + m.getName();
        float hv = anim(key, hov(key) ? 1 : 0, 16);
        Render2D.roundedRect(g, x, y, w, 21, ROW_R, a(ColorUtil.lerp(SURFACE, SURFACE_HI, hv)));
        Render2D.roundedRect(g, x + 4, y + 6, 2, 9, 1, a(Theme.accent()));
        text(g, m.getName(), x + 11, y + 7, Theme.TEXT);
        String info = m.getInfo();
        if (info != null) {
            float nx = x + 15 + font.width(m.getName());
            text(g, clip(info, (int) (x + w - 26 - nx)), nx, y + 7, 0xFF5C6373);
        }
        drawSwitch(g, x + w - 22, y + 6.5f, 1f);
        chit(x, y, w, 21, key, b -> {
            if (b == 0) { m.setEnabled(false); ConfigManager.save(); }
            else { switchPage(pageOf(m)); EXPANDED.add(m); }
        }, "Click to turn off · right-click for settings");
    }

    // ----------------------------------------------------------------- category

    private float drawCategory(GuiGraphics g, Category cat, float x, float y, float w) {
        boolean any = false;
        for (Module m : ModuleManager.byCategory(cat)) {
            if (!matches(m) || m instanceof ClickGui) continue;
            any = true;
            y = drawModuleRow(g, m, x, y, w) + 3;
        }
        if (!any) {
            text(g, "No modules match \"" + search + "\"", x, y + 4, 0xFF5C6373);
            y += 16;
        }
        return y;
    }

    private static final int CHIP_W = 92;

    private int addonRows(Module m, float w) {
        int n = m.addons().size();
        if (n == 0) return 0;
        int per = Math.max(1, (int) ((w - 24 - 54) / (CHIP_W + 4)));
        return (n + per - 1) / per;
    }

    private int advancedCount(Module m) {
        int n = 0;
        for (Setting<?> s : m.getSettings()) if (s.isAdvanced() && s.isVisibleIgnoringAdvanced()) n++;
        return n;
    }

    private float settingsHeight(Module m, float w) {
        float h = 6;
        for (Setting<?> s : m.getSettings()) if (s.isVisible()) h += rowHeight(s);
        if (advancedCount(m) > 0) h += 16;
        h += addonRows(m, w) * 16 + (addonRows(m, w) > 0 ? 4 : 0);
        return h + 16 + 6;
    }

    /** One-click companion modules ("Add-ons") so people discover what works well together. */
    private float drawAddons(GuiGraphics g, Module m, float x, float y, float w) {
        List<Class<? extends Module>> list = m.addons();
        if (list.isEmpty()) return y;
        text(g, "Add-ons", x, y + 4, 0xFF8B92A1);
        int per = Math.max(1, (int) ((w - 54) / (CHIP_W + 4)));
        for (int i = 0; i < list.size(); i++) {
            Module a = ModuleManager.get(list.get(i));
            float cx = x + 54 + (i % per) * (CHIP_W + 4), cy = y + (i / per) * 16 + 1;
            Object key = "addon:" + m.getName() + ":" + a.getName();
            float hv = anim(key, hov(key) ? 1 : 0, 16);
            float on = anim("on:" + a.getName(), a.isEnabled() ? 1 : 0, 14);
            Render2D.roundedRect(g, cx, cy, CHIP_W, 13, 4, a(ColorUtil.lerp(CHIP, 0xFF262A35, hv)));
            if (on > 0.01f) Render2D.roundedOutline(g, cx, cy, CHIP_W, 13, 4, 1, a(ColorUtil.withAlpha(Theme.accent(), (int) (200 * on))));
            String label = (a.isEnabled() ? "✔ " : "+ ") + (a.getName().equals("AutoTool") ? "Smart Tools" : a.getName());
            text(g, clip(label, CHIP_W - 8), cx + 5, cy + 3, a.isEnabled() ? Theme.TEXT : 0xFF8B92A1);
            chit(cx, cy, CHIP_W, 13, key, b -> { a.toggle(); ConfigManager.save(); },
                    (a.isEnabled() ? "Turn off " : "Recommended - turn on ") + a.getName() + ": " + a.getDescription());
        }
        return y + addonRows(m, w + 24) * 16 + 4;
    }

    private float drawAdvancedLink(GuiGraphics g, Module m, float x, float y, float w) {
        int n = advancedCount(m);
        if (n == 0) return y;
        boolean showing = cfg().advancedSettings.get();
        Object key = "adv:" + m.getName();
        float hv = anim(key, hov(key) ? 1 : 0, 16);
        String t = showing ? "▴ Hide advanced settings" : "▾ Show " + n + " advanced setting" + (n == 1 ? "" : "s");
        text(g, t, x, y + 4, ColorUtil.lerp(ColorUtil.withAlpha(Theme.accent(), 190), Theme.accent(), hv));
        chit(x - 4, y, font.width(t) + 12, 16, key, b -> { cfg().advancedSettings.toggle(); ConfigManager.save(); },
                "Advanced settings are hidden to keep things simple. This shows them for every module.");
        return y + 16;
    }

    private static final int MULTI_ROW = 15;
    /** Columns for multi-select grids; set per frame from the content width so long names never clip. */
    private int multiCols = 3;

    private static boolean isSlotSetting(Setting<?> s) {
        return s instanceof ModeSetting md && md.getModes().size() == 10 && md.getModes().get(0).equals("Auto") && md.getModes().get(9).equals("9");
    }

    private float rowHeight(Setting<?> s) {
        if (isSlotSetting(s)) return 18;
        if (s instanceof dev.magma.client.setting.MultiSetting ms) {
            return 16 + (float) Math.ceil(ms.getOptions().size() / (double) multiCols) * MULTI_ROW + 4;
        }
        return s instanceof NumberSetting ? 22 : 16;
    }

    private float drawModuleRow(GuiGraphics g, Module m, float x, float y, float w) {
        // rows slide in one after another when a page opens
        int idx = rowSeq++;
        float t = ((System.currentTimeMillis() - pageShownAt) / 1000f - idx * 0.035f) / 0.22f;
        float ra = Anim.easeOutCubic(t);
        float saved = alpha;
        alpha *= Math.max(0, ra);
        float rx = x + (1 - ra) * 12;
        float out;
        try {
            out = drawModuleRowInner(g, m, rx, y, w);
        } finally {
            alpha = saved;
        }
        // a ripple of accent colour when it's switched on/off
        Long f = FLASH.get(m.getName());
        if (f != null) {
            float e = (System.currentTimeMillis() - f) / 380f;
            if (e >= 1) FLASH.remove(m.getName());
            else {
                float grow = Anim.easeOutCubic(e) * 4;
                Render2D.roundedOutline(g, rx - grow, y - grow, w + grow * 2, ROW + grow * 2, ROW_R + grow, 1.5f,
                        ColorUtil.mulAlpha(Theme.accent(), (1 - e) * alpha));
            }
        }
        return out;
    }

    private float drawModuleRowInner(GuiGraphics g, Module m, float x, float y, float w) {
        Object key = "card:" + m.getName();
        float hv = anim(key, hov(key) ? 1 : 0, 16);
        float on = anim("on:" + m.getName(), m.isEnabled() ? 1 : 0, 14);
        float ex = anim("ex:" + m.getName(), EXPANDED.contains(m) ? 1 : 0, 14);

        Render2D.roundedRect(g, x, y, w, ROW, ROW_R, a(ColorUtil.lerp(SURFACE, SURFACE_HI, hv)));
        if (on > 0.01f) Render2D.roundedRect(g, x + 4, y + 14 - 6 * on, 2, 12 * on, 1, a(Theme.accent()));

        float rightLimit = x + w - 52 - (m.getKey() != -1 ? font.width(keyName(m.getKey())) + 14 : 0);
        text(g, clip(m.getName(), (int) (rightLimit - x - 12)), x + 12, y + 5, ColorUtil.lerp(0xFF9AA0AE, Theme.TEXT, Math.max(on, hv)));
        if (m.isEnabled() && m.getInfo() != null) {
            float ix = x + 18 + font.width(m.getName());
            if (rightLimit - ix > 20) text(g, clip(m.getInfo(), (int) (rightLimit - ix)), ix, y + 5, ColorUtil.withAlpha(Theme.accent(), 210));
        }
        text(g, clip(m.getDescription(), (int) (rightLimit - x - 12)), x + 12, y + 16, 0xFF5C6373);

        float sx = x + w - 26;
        boolean menuModule = m instanceof ClickGui; // opening the menu from the menu makes no sense: it just shows settings
        if (!menuModule) drawSwitch(g, sx, y + 10, on);
        Object ck = "chev:" + m.getName();
        float chv = anim(ck, hov(ck) ? 1 : 0, 16);
        float chx = sx - 18;
        if (chv > 0.01f || ex > 0.01f) Render2D.roundedRect(g, chx, y + 7, 14, 14, 3, a(ColorUtil.mulAlpha(0x14FFFFFF, Math.max(chv, ex))));
        text(g, ex > 0.5f ? "▴" : "▾", chx + 7 - font.width("▾") / 2f, y + 10, ex > 0.5f ? Theme.accent() : 0xFF6B7282);
        if (m.getKey() != -1) {
            String k = keyName(m.getKey());
            float kw = font.width(k) + 8;
            Render2D.roundedRect(g, chx - kw - 4, y + 8, kw, 12, 3, a(CHIP));
            text(g, k, chx - kw, y + 10, 0xFF8B92A1);
        }

        chit(x, y, w, ROW, key, b -> {
            if (b == 0 && menuModule) toggleExpand(m);
            else if (b == 0) { m.toggle(); ConfigManager.save(); FLASH.put(m.getName(), System.currentTimeMillis()); }
            else if (b == 1) toggleExpand(m);
            else if (b == 2) binding = m;
        }, m.getDescription() + "  ·  right-click: settings  ·  middle-click: bind");
        chit(chx, y + 7, 14, 14, ck, b -> toggleExpand(m), null);

        float yy = y + ROW;
        if (ex > 0.003f) {
            float full = settingsHeight(m, w);
            float vis = full * ex;
            g.flush();
            g.enableScissor((int) x, (int) yy, (int) (x + w), (int) Math.ceil(yy + vis));
            Render2D.roundedRect(g, x, yy + 2, w, full - 4, ROW_R, a(INSET));
            Render2D.roundedOutline(g, x, yy + 2, w, full - 4, ROW_R, 1, a(0x0AFFFFFF));
            float sy = yy + 5;
            for (Setting<?> s : m.getSettings()) {
                if (!s.isVisible()) continue;
                sy = drawSettingRow(g, s, x + 12, sy, w - 24);
            }
            sy = drawAdvancedLink(g, m, x + 12, sy, w - 24);
            sy = drawAddons(g, m, x + 12, sy, w - 24);
            drawBindRow(g, m, x + 12, sy, w - 24);
            g.flush();
            g.disableScissor();
            yy += vis;
        }
        return yy;
    }

    private void toggleExpand(Module m) {
        if (!EXPANDED.remove(m)) EXPANDED.add(m);
    }

    // ----------------------------------------------------------------- setting widgets

    private float drawSettingRow(GuiGraphics g, Setting<?> s, float x, float y, float w) {
        Object key = s;
        float hv = anim("h:" + System.identityHashCode(s), hov(key) ? 1 : 0, 16);
        int label = ColorUtil.lerp(0xFF8B92A1, Theme.TEXT, hv);

        if (s instanceof BoolSetting b) {
            float on = anim("b:" + System.identityHashCode(s), b.get() ? 1 : 0, 16);
            text(g, s.getName(), x, y + 4, label);
            drawSwitch(g, x + w - 16, y + 4, on);
            chit(x - 4, y, w + 8, 16, key, btn -> { b.toggle(); ConfigManager.save(); }, s.getDescription());
            return y + 16;
        }
        if (s instanceof NumberSetting n) {
            text(g, s.getName(), x, y + 3, label);
            String v = n.display();
            text(g, v, x + w - font.width(v), y + 3, Theme.TEXT);
            float tx = x, tw = w, ty = y + 15;
            float pct = (float) ((n.get() - n.getMin()) / (n.getMax() - n.getMin()));
            float shown = anim("n:" + System.identityHashCode(s), pct, 20);
            Render2D.roundedRect(g, tx, ty, tw, 2, 1, a(0xFF2A2E38));
            Render2D.roundedRect(g, tx, ty, Math.max(2, tw * shown), 2, 1, a(Theme.accent()));
            float kr = 2.6f + (slider == n ? 0.8f : hv * 0.5f);
            Render2D.circle(g, tx + tw * shown, ty + 1, kr, a(0xFFFFFFFF));
            chit(x - 4, y, w + 8, 22, key, btn -> {
                if (btn == 1) { n.reset(); ConfigManager.save(); return; }
                slider = n;
                sliderX = tx;
                sliderW = tw;
                applySlider(mouseX);
            }, s.getDescription() + " (right-click to reset)");
            return y + 22;
        }
        if (s instanceof dev.magma.client.setting.TextSetting ts) {
            text(g, s.getName(), x, y + 4, label);
            boolean focus = editing == ts;
            float bw = 84, bx = x + w - bw;
            Render2D.roundedRect(g, bx, y + 1, bw, 13, 4, a(ColorUtil.lerp(CHIP, 0xFF252934, Math.max(hv, focus ? 1 : 0))));
            if (focus) Render2D.roundedOutline(g, bx, y + 1, bw, 13, 4, 1, a(ColorUtil.withAlpha(Theme.accent(), 200)));
            if (focus && editingFresh && !ts.get().isEmpty()) {
                // "selected" highlight: typing replaces this value
                Render2D.roundedRect(g, bx + 3, y + 3, Math.min(bw - 6, font.width(ts.get()) + 4), 9, 2, a(ColorUtil.withAlpha(Theme.accent(), 110)));
            }
            String v = ts.get() + (focus && !editingFresh && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
            text(g, clip(v, (int) bw - 10), bx + 5, y + 4, Theme.TEXT);
            if (ts.get().startsWith("~")) text(g, "relative", bx - font.width("relative") - 6, y + 4, 0xFF5C6373);
            chit(x - 4, y, w + 8, 16, key, btn -> {
                if (btn == 1) { ts.set(""); }
                editing = ts;
                editingFresh = true;
                searchFocus = false;
            }, s.getDescription() + " (click to type, Enter to confirm)");
            return y + 16;
        }
        if (s instanceof dev.magma.client.setting.MultiSetting ms) {
            text(g, s.getName(), x, y + 4, label);
            String sum = ms.summary();
            text(g, sum, x + w - font.width(sum), y + 4, ms.get().isEmpty() ? Theme.DANGER : 0xFF8B92A1);
            List<String> opts = ms.getOptions();
            float cw = (w - (multiCols - 1) * 3) / multiCols;
            for (int i = 0; i < opts.size(); i++) {
                String o = opts.get(i);
                float cx = x + (i % multiCols) * (cw + 3);
                float cy = y + 16 + (i / multiCols) * MULTI_ROW;
                boolean sel = ms.has(o);
                Object ok = "multi:" + System.identityHashCode(s) + ":" + o;
                float oh = anim(ok, hov(ok) ? 1 : 0, 16);
                float so = anim("ms:" + System.identityHashCode(s) + ":" + o, sel ? 1 : 0, 16);
                Render2D.roundedRect(g, cx, cy, cw, 12, 3, a(ColorUtil.lerp(CHIP, 0xFF262A35, oh)));
                if (so > 0.01f) Render2D.roundedOutline(g, cx, cy, cw, 12, 3, 1, a(ColorUtil.withAlpha(Theme.accent(), (int) (200 * so))));
                // checkbox
                Render2D.roundedRect(g, cx + 3, cy + 3, 6, 6, 1.5f, a(ColorUtil.lerp(0xFF2E3340, Theme.accent(), so)));
                text(g, clip(o, (int) cw - 14), cx + 12, cy + 2, ColorUtil.lerp(0xFF8B92A1, Theme.TEXT, Math.max(so, oh)));
                chit(cx, cy, cw, 12, ok, btn -> { ms.toggle(o); ConfigManager.save(); }, (sel ? "Stop mining " : "Also mine ") + o);
            }
            return y + rowHeight(s);
        }
        if (s instanceof dev.magma.client.setting.ButtonSetting bs) {
            text(g, s.getName(), x, y + 4, label);
            if (bs == ModuleManager.get(AutoMiner.class).resetTotal) {
                String v = String.valueOf(ModuleManager.get(AutoMiner.class).getTotalMined());
                text(g, v, x + font.width(s.getName()) + 8, y + 4, Theme.TEXT);
            }
            String l = bs.get();
            float bw = font.width(l) + 12;
            Render2D.roundedRect(g, x + w - bw, y + 1, bw, 13, 4, a(ColorUtil.lerp(CHIP, 0xFF2C303C, hv)));
            text(g, l, x + w - bw + 6, y + 4, ColorUtil.lerp(0xFF8B92A1, Theme.TEXT, hv));
            chit(x - 4, y, w + 8, 16, key, btn -> { bs.press(); ConfigManager.save(); }, s.getDescription());
            return y + 16;
        }
        if (isSlotSetting(s)) return drawHotbarPicker(g, (ModeSetting) s, s.getName(), x, y, w);
        if (s instanceof ModeSetting md) {
            text(g, s.getName(), x, y + 4, label);
            List<String> modes = md.getModes();
            float segW = 0;
            for (String mo : modes) segW += font.width(mo) + 10;
            float maxW = w - font.width(s.getName()) - 12;
            if (modes.size() <= 5 && segW <= maxW) {
                float sx = x + w - segW;
                Render2D.roundedRect(g, sx - 1, y + 1, segW + 2, 13, 4, a(CHIP));
                for (String mo : modes) {
                    float mw = font.width(mo) + 10;
                    boolean sel = md.is(mo);
                    Object mk = "seg:" + System.identityHashCode(s) + ":" + mo;
                    float mh = anim(mk, hov(mk) ? 1 : 0, 16);
                    if (sel) Render2D.roundedRect(g, sx + 1, y + 2, mw - 2, 11, 3, a(ColorUtil.withAlpha(Theme.accent(), 210)));
                    text(g, mo, sx + 5, y + 4, sel ? 0xFFFFFFFF : ColorUtil.lerp(0xFF6B7282, Theme.TEXT, mh));
                    chit(sx, y + 1, mw, 13, mk, btn -> { md.set(mo); ConfigManager.save(); }, s.getDescription());
                    sx += mw;
                }
            } else {
                String v = md.get();
                float vw = font.width(v) + 22;
                float vx = x + w - vw;
                Render2D.roundedRect(g, vx, y + 1, vw, 13, 4, a(ColorUtil.lerp(CHIP, 0xFF252934, hv)));
                text(g, "‹", vx + 4, y + 4, 0xFF6B7282);
                text(g, v, vx + 11, y + 4, Theme.TEXT);
                text(g, "›", vx + vw - 8, y + 4, 0xFF6B7282);
                chit(x - 4, y, w + 8, 16, key, btn -> { md.cycle(btn == 1 ? -1 : 1); ConfigManager.save(); },
                        s.getDescription() + " (left/right click)");
            }
            return y + 16;
        }
        return y;
    }

    private void drawBindRow(GuiGraphics g, Module m, float x, float y, float w) {
        Object key = "bind:" + m.getName();
        float hv = anim(key, hov(key) ? 1 : 0, 16);
        text(g, "Keybind", x, y + 4, ColorUtil.lerp(0xFF8B92A1, Theme.TEXT, hv));
        boolean listening = binding == m;
        String v = listening ? "Press a key…" : keyName(m.getKey());
        float vw = font.width(v) + 10;
        Render2D.roundedRect(g, x + w - vw, y + 1, vw, 13, 4, a(listening ? ColorUtil.withAlpha(Theme.accent(), 200) : ColorUtil.lerp(CHIP, 0xFF252934, hv)));
        text(g, v, x + w - vw + 5, y + 4, listening ? 0xFFFFFFFF : Theme.TEXT);
        chit(x + w - vw - 4, y, vw + 8, 16, key, btn -> {
            if (btn == 1) { m.setKey(-1); ConfigManager.save(); }
            else binding = m;
        }, "Click, then press a key. Right-click to unbind.");

        Object rk = "reset:" + m.getName();
        float rh = anim(rk, hov(rk) ? 1 : 0, 16);
        String rl = "Reset settings";
        float rw = font.width(rl) + 10;
        float rx = x + w - vw - 6 - rw;
        Render2D.roundedRect(g, rx, y + 1, rw, 13, 4, a(ColorUtil.lerp(CHIP, 0xFF3A2A2A, rh)));
        text(g, rl, rx + 5, y + 4, ColorUtil.lerp(0xFF6B7282, 0xFFFF8A8A, rh));
        chit(rx, y, rw, 16, rk, btn -> {
            for (Setting<?> st : m.getSettings()) if (!(st instanceof dev.magma.client.setting.ButtonSetting)) st.reset();
            ConfigManager.save();
            dev.magma.client.util.Notify.show(m.getName() + " settings reset", "Every setting of " + m.getName() + " is back to its default value.", Theme.WARN);
        }, "Put every setting of " + m.getName() + " back to its default (keeps the keybind).");
    }

    // ----------------------------------------------------------------- appearance

    private static final Object[][] PRESETS = {
            {"Magma", 14, 28}, {"Ember", 0, 20}, {"Gold", 42, 14}, {"Toxic", 95, 40},
            {"Ocean", 195, 30}, {"Ice", 180, 20}, {"Amethyst", 265, 40}, {"Sakura", 325, 25},
    };

    private float group(GuiGraphics g, String title, float x, float y, float w, float bodyH) {
        y = section(g, title, x, y, w);
        Render2D.roundedRect(g, x, y, w, bodyH, ROW_R, a(SURFACE));
        return y;
    }

    private float groupHeight(List<Setting<?>> list, int extraRows, int extraPickers) {
        float h = 8 + extraRows * 16 + extraPickers * 18;
        for (Setting<?> s : list) if (s.isVisible()) h += rowHeight(s);
        return h;
    }

    private float drawAppearance(GuiGraphics g, float x, float y, float w) {
        ClickGui c = cfg();
        float ix = x + 10, iw = w - 20;

        // theme
        y = section(g, "Theme", x, y, w);
        float sw = 16;
        float gap = (w - PRESETS.length * sw) / (PRESETS.length - 1);
        gap = Math.min(gap, 40);
        for (int i = 0; i < PRESETS.length; i++) {
            String name = (String) PRESETS[i][0];
            int hue = (Integer) PRESETS[i][1], spread = (Integer) PRESETS[i][2];
            float sx = x + i * (sw + gap);
            Object key = "preset:" + name;
            float hv = anim(key, hov(key) ? 1 : 0, 16);
            boolean sel = !c.rainbow.get() && c.hue.getInt() == hue && c.spread.getInt() == spread;
            int c1 = ColorUtil.hsb(hue / 360f, 0.78f, 1f), c2 = ColorUtil.hsb((hue + spread) / 360f, 0.85f, 1f);
            if (sel || hv > 0.01f) Render2D.roundedOutline(g, sx - 2, y - 2, sw + 4, sw + 4, 6, 1, a(sel ? 0xFFFFFFFF : ColorUtil.mulAlpha(0x70FFFFFF, hv)));
            Render2D.roundedGradient(g, sx, y, sw, sw, 4, a(c1), a(c2), true);
            chit(sx - 2, y - 2, sw + 4, sw + 4, key, b -> {
                c.hue.set((double) hue);
                c.spread.set((double) spread);
                c.rainbow.set(false);
                ConfigManager.save();
            }, name);
        }
        y += sw + 10;
        y = groupOf(g, List.of(c.hue, c.rainbow), x, y, w);

        y = section(g, "Interface", x, y, w);
        y = groupOf(g, List.of(c.style, c.blur, c.descriptions, c.advancedSettings), x, y, w);
        // menu key, right here instead of hunting for the ClickGUI module
        y -= 10;
        Render2D.roundedRect(g, x, y, w, 24, ROW_R, a(SURFACE));
        drawBindRow(g, c, x + 10, y + 4, w - 20);
        y += 24 + 14;

        y = section(g, "Updates", x, y, w);
        var st = dev.magma.client.util.Updater.state();
        boolean avail = st == dev.magma.client.util.Updater.State.AVAILABLE || st == dev.magma.client.util.Updater.State.READY
                || st == dev.magma.client.util.Updater.State.DOWNLOADING;
        float boxH = 8 + 20 + (avail ? 14 : 0) + 22 + groupRows(List.of(c.checkUpdates, c.autoUpdate, c.reopenAfterUpdate));
        Render2D.roundedRect(g, x, y, w, boxH, ROW_R, a(SURFACE));
        float yy = y + 6;
        // status line: coloured dot + what's going on, "checked x ago" on the right
        int dot = switch (st) {
            case UP_TO_DATE -> Theme.OK;
            case AVAILABLE, DOWNLOADING -> Theme.accent();
            case READY -> Theme.OK;
            case ERROR -> Theme.DANGER;
            default -> 0xFF5C6373;
        };
        String line = switch (st) {
            case UP_TO_DATE -> "Up to date \u00B7 v" + dev.magma.client.util.Updater.installed();
            case AVAILABLE -> "v" + dev.magma.client.util.Updater.latest() + " available (you have v" + dev.magma.client.util.Updater.installed() + ")";
            case DOWNLOADING -> "Downloading v" + dev.magma.client.util.Updater.latest() + " \u00B7 " + Math.round(dev.magma.client.util.Updater.progress() * 100) + "%";
            case READY -> "v" + dev.magma.client.util.Updater.latest() + " ready \u00B7 restart to finish";
            case CHECKING -> "Checking GitHub\u2026";
            case ERROR -> dev.magma.client.util.Updater.message();
            default -> "v" + dev.magma.client.util.Updater.installed();
        };
        Render2D.circle(g, x + 13, yy + 7, 2.5f, a(dot));
        text(g, clip(line, (int) w - 120), x + 21, yy + 3, Theme.TEXT);
        long at = dev.magma.client.util.Updater.checkedAt();
        if (at > 0) {
            long secs = (System.currentTimeMillis() - at) / 1000;
            String ago = secs < 60 ? "checked just now" : secs < 3600 ? "checked " + secs / 60 + " min ago" : "checked " + secs / 3600 + " h ago";
            text(g, ago, x + w - 10 - font.width(ago), yy + 3, 0xFF5C6373);
        }
        yy += 20;
        if (avail) {
            String notes = dev.magma.client.util.Updater.notes();
            text(g, clip(notes.isEmpty() ? "New version on GitHub" : "What's new: " + notes, (int) w - 30), x + 21, yy, 0xFF8B92A1);
            yy += 14;
        }
        // buttons
        float bx = x + 10;
        if (avail && st != dev.magma.client.util.Updater.State.DOWNLOADING) {
            String pl = st == dev.magma.client.util.Updater.State.READY ? "Restart now" : "Update & restart";
            Object pk = "set:upd";
            float ph = anim(pk, hov(pk) ? 1 : 0, 16);
            float pw = font.width(pl) + 16;
            Render2D.roundedRect(g, bx, yy, pw, 15, 4, a(ColorUtil.lerp(Theme.accent(), Theme.accent2(), ph)));
            text(g, pl, bx + pw / 2f - font.width(pl) / 2f, yy + 4, 0xFF120A06);
            chit(bx, yy, pw, 15, pk, b -> dev.magma.client.util.Updater.installAndRestart(), "Saves, closes Minecraft and swaps in the new version");
            bx += pw + 6;
        }
        Object ck = "set:check";
        float chh = anim(ck, hov(ck) ? 1 : 0, 16);
        String cl = st == dev.magma.client.util.Updater.State.CHECKING ? "Checking\u2026" : "Check now";
        float cw = font.width(cl) + 16;
        Render2D.roundedRect(g, bx, yy, cw, 15, 4, a(ColorUtil.lerp(CHIP, 0xFF2C303C, chh)));
        text(g, cl, bx + cw / 2f - font.width(cl) / 2f, yy + 4, Theme.TEXT);
        chit(bx, yy, cw, 15, ck, b -> dev.magma.client.util.Updater.checkAsync(false), "Look for a new version on GitHub now");
        yy += 22;
        for (Setting<?> st2 : List.of(c.checkUpdates, c.autoUpdate, c.reopenAfterUpdate)) yy = drawSettingRow(g, st2, x + 10, yy, w - 20);
        return y + boxH + 18;
    }

    private float groupRows(List<Setting<?>> list) {
        float h = 0;
        for (Setting<?> st : list) if (st.isVisibleIgnoringAdvanced()) h += rowHeight(st);
        return h;
    }

    /** A boxed group of settings (shown even if they're "advanced": this page is the place for looks). */
    private float groupOf(GuiGraphics g, List<Setting<?>> list, float x, float y, float w) {
        Render2D.roundedRect(g, x, y, w, 8 + groupRows(list), ROW_R, a(SURFACE));
        y += 4;
        for (Setting<?> st : list) if (st.isVisibleIgnoringAdvanced()) y = drawSettingRow(g, st, x + 10, y, w - 20);
        return y + 18;
    }

    private float drawModuleToggleRow(GuiGraphics g, Module m, String label, float x, float y, float w) {
        Object key = "mt:" + m.getName();
        float hv = anim(key, hov(key) ? 1 : 0, 16);
        float on = anim("on:" + m.getName(), m.isEnabled() ? 1 : 0, 14);
        text(g, label, x, y + 4, ColorUtil.lerp(Theme.TEXT_DIM, Theme.TEXT, hv));
        drawSwitch(g, x + w - 16, y + 4, on);
        chit(x - 4, y, w + 8, 16, key, b -> { m.toggle(); ConfigManager.save(); }, m.getDescription());
        return y + 16;
    }

    /** Compact hotbar: real items in each slot, click one to choose it. */
    private float drawHotbarPicker(GuiGraphics g, ModeSetting setting, String label, float x, float y, float w) {
        text(g, label, x, y + 4, 0xFF8B92A1);
        float cell = 16, gap = 1;
        float total = 26 + 4 + (cell + gap) * 9;
        float sx = x + w - total;
        boolean auto = setting.is("Auto");
        Object ak = "hb:" + System.identityHashCode(setting) + ":auto";
        float ah = anim(ak, hov(ak) ? 1 : 0, 16);
        Render2D.roundedRect(g, sx, y, 26, cell, 3, a(auto ? ColorUtil.withAlpha(Theme.accent(), 210) : ColorUtil.lerp(CHIP, 0xFF252934, ah)));
        text(g, "Auto", sx + 13 - font.width("Auto") / 2f, y + 4, auto ? 0xFFFFFFFF : 0xFF8B92A1);
        chit(sx, y, 26, cell, ak, b -> { setting.set("Auto"); ConfigManager.save(); }, "Pick the best item automatically");
        sx += 30;
        var inv = minecraft.player != null ? minecraft.player.getInventory() : null;
        for (int i = 0; i < 9; i++) {
            float cx = sx + i * (cell + gap);
            String val = String.valueOf(i + 1);
            boolean sel = setting.is(val);
            Object k = "hb:" + System.identityHashCode(setting) + ":" + i;
            float hv = anim(k, hov(k) ? 1 : 0, 16);
            Render2D.roundedRect(g, cx, y, cell, cell, 3, a(ColorUtil.lerp(CHIP, 0xFF262A35, hv)));
            if (sel) Render2D.roundedOutline(g, cx, y, cell, cell, 3, 1, a(Theme.accent()));
            boolean has = inv != null && !inv.getItem(i).isEmpty();
            if (has && alpha > 0.6f) {
                g.pose().pushPose();
                g.pose().translate(cx + 1, y + 1, 0);
                g.pose().scale(0.875f, 0.875f, 1f);
                g.renderItem(inv.getItem(i), 0, 0);
                g.pose().popPose();
            } else {
                text(g, val, cx + cell / 2f - font.width(val) / 2f, y + 4, 0xFF4A505D);
            }
            String tip = has ? "Slot " + val + ": " + inv.getItem(i).getHoverName().getString() : "Slot " + val + " (empty)";
            chit(cx, y, cell, cell, k, b -> { setting.set(val); ConfigManager.save(); }, tip);
        }
        return y + cell + 2;
    }

    // ----------------------------------------------------------------- overlays

    private void drawTooltip(GuiGraphics g, String t) {
        List<FormattedCharSequence> lines = font.split(Component.literal(t), 220);
        int w = 0;
        for (var l : lines) w = Math.max(w, font.width(l));
        int h = lines.size() * 10 + 6;
        float x = Math.min(width - w - 16, Math.max(4, mouseX + 10));
        float y = Math.min(height - h - 4, mouseY + 12);
        Render2D.roundedRect(g, x, y, w + 12, h, 4, a(0xF5101217));
        Render2D.roundedOutline(g, x, y, w + 12, h, 4, 1, a(0x1EFFFFFF));
        int ly = (int) y + 4;
        for (var l : lines) {
            g.drawString(font, l, (int) x + 6, ly, ta(0xFFC9CED8), false);
            ly += 10;
        }
    }

    private void drawBindOverlay(GuiGraphics g) {
        Render2D.rect(g, 0, 0, width, height, 0x70000000);
        String t = "Press a key for " + binding.getName();
        String s = "Esc cancel · Delete unbind";
        int w = Math.max(font.width(t), font.width(s)) + 28;
        float x = width / 2f - w / 2f, y = height / 2f - 18;
        Render2D.roundedRect(g, x, y, w, 34, 6, 0xFA101217);
        Render2D.roundedOutline(g, x, y, w, 34, 6, 1, ColorUtil.withAlpha(Theme.accent(), 140));
        g.drawString(font, t, (int) (width / 2f - font.width(t) / 2f), (int) y + 7, Theme.TEXT, false);
        g.drawString(font, s, (int) (width / 2f - font.width(s) / 2f), (int) y + 20, 0xFF5C6373, false);
    }

    private void applySlider(double mx) {
        if (slider == null) return;
        double pct = Math.max(0, Math.min(1, (mx - sliderX) / sliderW));
        slider.set(slider.getMin() + pct * (slider.getMax() - slider.getMin()));
    }

    // ================================================================= input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (binding != null) {
            binding = null;
            return true;
        }
        searchFocus = false;
        if (editing != null) { editing = null; ConfigManager.save(); }
        for (int i = lastHits.size() - 1; i >= 0; i--) {
            Hit h = lastHits.get(i);
            if (h.contains(mx, my)) {
                h.onClick.accept(button);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (slider != null) {
            applySlider(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (slider != null) {
            slider = null;
            ConfigManager.save();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (hasShiftDown() && hovered instanceof Setting<?> st) {
            int dir = sy > 0 ? 1 : -1;
            if (st instanceof NumberSetting n) n.set(n.get() + n.getStep() * dir);
            else if (st instanceof ModeSetting md) md.cycle(-dir);
            else if (st instanceof BoolSetting b) b.set(dir > 0);
            ConfigManager.save();
            return true;
        }
        scrollTarget += (float) sy * 24f;
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
        if (editing != null) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (editingFresh) { editing.set(""); editingFresh = false; return true; }
                String v = editing.get();
                if (!v.isEmpty()) editing.set(v.substring(0, v.length() - 1));
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_TAB) {
                editing = null;
                ConfigManager.save();
            }
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
        if (key == GLFW.GLFW_KEY_F && hasControlDown()) {
            searchFocus = true;
            if (PAGES.get(page).cat == null && page != 0) switchPage(0);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            switchPage((page + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? PAGES.size() - 1 : 1)) % PAGES.size());
            return true;
        }
        if (key == cfg().getKey() && !searchFocus) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (binding != null) return true;
        if (editing != null) {
            if (editing.accepts(c)) {
                if (editingFresh) { editing.set(""); editingFresh = false; }
                if (editing.get().length() < editing.getMaxLength()) editing.set(editing.get() + c);
            }
            return true;
        }
        if (!searchFocus && Character.isLetterOrDigit(c)) {
            searchFocus = true;
            if (PAGES.get(page).cat == null && page != 0) switchPage(0);
        }
        if (searchFocus && c >= 32 && search.length() < 24) {
            search += c;
            scrollTarget = 0;
            return true;
        }
        return super.charTyped(c, mods);
    }

    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        slider = null;
        ConfigManager.save();
    }

    @Override
    public void removed() {
        ConfigManager.save();
    }
}
