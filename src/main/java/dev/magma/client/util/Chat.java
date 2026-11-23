package dev.magma.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

public final class Chat {
    private Chat() {}

    public static void info(String msg) {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        MutableComponent prefix = Component.literal("Magma ").withStyle(s -> s.withColor(TextColor.fromRgb(Theme.accent() & 0xFFFFFF)).withBold(true));
        p.displayClientMessage(prefix.append(Component.literal("» ").withStyle(s -> s.withColor(0x5C6475).withBold(false)))
                .append(Component.literal(msg).withStyle(s -> s.withColor(0xECEFF4).withBold(false))), false);
    }
}
