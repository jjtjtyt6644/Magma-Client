package dev.magma.client.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Smooth vector shapes for the GUI (rounded rects, gradients, outlines, glows).
 * Shapes draw immediately, so GuiGraphics is flushed first to keep layering correct.
 */
public final class Render2D {
    private static final int CORNER_SEGMENTS = 8;

    private Render2D() {}

    private static void setup() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void finish(BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    public static void rect(GuiGraphics g, float x, float y, float w, float h, int color) {
        gradientRect(g, x, y, w, h, color, color, color, color);
    }

    /** Colors: top-left, top-right, bottom-right, bottom-left. */
    public static void gradientRect(GuiGraphics g, float x, float y, float w, float h, int tl, int tr, int br, int bl) {
        if (w <= 0 || h <= 0) return;
        g.flush();
        setup();
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        b.addVertex(m, x, y + h, 0).setColor(bl);
        b.addVertex(m, x + w, y + h, 0).setColor(br);
        b.addVertex(m, x + w, y, 0).setColor(tr);
        b.addVertex(m, x, y, 0).setColor(tl);
        finish(b);
    }

    public static void hGradient(GuiGraphics g, float x, float y, float w, float h, int left, int right) {
        gradientRect(g, x, y, w, h, left, right, right, left);
    }

    public static void vGradient(GuiGraphics g, float x, float y, float w, float h, int top, int bottom) {
        gradientRect(g, x, y, w, h, top, top, bottom, bottom);
    }

    public static void roundedRect(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        roundedGradient(g, x, y, w, h, r, color, color, false);
    }

    /** Rounded rect with a horizontal (or vertical) two-color gradient. */
    public static void roundedGradient(GuiGraphics g, float x, float y, float w, float h, float r, int c1, int c2, boolean vertical) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2f));
        g.flush();
        setup();
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        float cx = x + w / 2f, cy = y + h / 2f;
        b.addVertex(m, cx, cy, 0).setColor(ColorUtil.lerp(c1, c2, 0.5f));
        float[][] corners = {
                {x + w - r, y + r, -90},     // top-right
                {x + w - r, y + h - r, 0},   // bottom-right
                {x + r, y + h - r, 90},      // bottom-left
                {x + r, y + r, 180},         // top-left
        };
        float firstX = 0, firstY = 0;
        boolean first = true;
        for (float[] c : corners) {
            for (int i = 0; i <= CORNER_SEGMENTS; i++) {
                double a = Math.toRadians(c[2] + 90.0 * i / CORNER_SEGMENTS);
                float px = (float) (c[0] + Math.cos(a) * r);
                float py = (float) (c[1] + Math.sin(a) * r);
                float t = vertical ? (py - y) / h : (px - x) / w;
                b.addVertex(m, px, py, 0).setColor(ColorUtil.lerp(c1, c2, t));
                if (first) { firstX = px; firstY = py; first = false; }
            }
        }
        float t = vertical ? (firstY - y) / h : (firstX - x) / w;
        b.addVertex(m, firstX, firstY, 0).setColor(ColorUtil.lerp(c1, c2, t));
        finish(b);
    }

    /** Rounded outline of the given thickness. */
    public static void roundedOutline(GuiGraphics g, float x, float y, float w, float h, float r, float t, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2f));
        float ix = x + t, iy = y + t, iw = w - 2 * t, ih = h - 2 * t;
        float ri = Math.max(0, r - t);
        g.flush();
        setup();
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        float[][] outer = {{x + w - r, y + r}, {x + w - r, y + h - r}, {x + r, y + h - r}, {x + r, y + r}};
        float[][] inner = {{ix + iw - ri, iy + ri}, {ix + iw - ri, iy + ih - ri}, {ix + ri, iy + ih - ri}, {ix + ri, iy + ri}};
        float[] start = {-90, 0, 90, 180};
        for (int pass = 0; pass < 2; pass++) {
            for (int c = 0; c < 4; c++) {
                for (int i = 0; i <= CORNER_SEGMENTS; i++) {
                    double a = Math.toRadians(start[c] + 90.0 * i / CORNER_SEGMENTS);
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    b.addVertex(m, outer[c][0] + cos * r, outer[c][1] + sin * r, 0).setColor(color);
                    b.addVertex(m, inner[c][0] + cos * ri, inner[c][1] + sin * ri, 0).setColor(color);
                    if (pass == 1) break;
                }
                if (pass == 1) break;
            }
        }
        finish(b);
    }

    /** Soft drop-shadow / glow made of stacked translucent rounded rects. */
    public static void shadow(GuiGraphics g, float x, float y, float w, float h, float r, float size, int color) {
        int steps = 6;
        int baseA = ColorUtil.alpha(color);
        for (int i = steps; i >= 1; i--) {
            float grow = size * i / steps;
            int a = Math.round(baseA * (1f - (float) i / (steps + 1)) / steps * 1.6f);
            roundedRect(g, x - grow, y - grow, w + grow * 2, h + grow * 2, r + grow, ColorUtil.withAlpha(color, a));
        }
    }

    public static void circle(GuiGraphics g, float cx, float cy, float radius, int color) {
        g.flush();
        setup();
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        b.addVertex(m, cx, cy, 0).setColor(color);
        int seg = 24;
        for (int i = 0; i <= seg; i++) {
            double a = Math.PI * 2 * i / seg;
            b.addVertex(m, (float) (cx + Math.cos(a) * radius), (float) (cy + Math.sin(a) * radius), 0).setColor(color);
        }
        finish(b);
    }

    /** Draws text where each character is tinted along the accent gradient. */
    public static int gradientText(GuiGraphics g, String text, float x, float y, float alpha, boolean shadow) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int col = ColorUtil.textAlpha(Theme.accent(i * 0.06f), alpha);
            g.drawString(font, ch, Math.round(cx), Math.round(y), col, shadow);
            cx += font.width(ch);
        }
        return Math.round(cx - x);
    }
}
