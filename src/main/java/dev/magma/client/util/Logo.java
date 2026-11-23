package dev.magma.client.util;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** The Magma logo (assets/magmaclient/textures/gui/logo.png, 128x128). */
public final class Logo {
    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("magmaclient", "textures/gui/logo.png");

    private Logo() {}

    public static void draw(GuiGraphics g, float x, float y, int size, float alpha) {
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0f, Math.min(1f, alpha)));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.blit(TEXTURE, 0, 0, size, size, 0f, 0f, 128, 128, 128, 128);
        g.pose().popPose();
        g.flush();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
