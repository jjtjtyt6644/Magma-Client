package dev.magma.client.fabric;

import dev.magma.client.MagmaClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Fabric entry point: the same Magma, with Fabric API wired to the hooks Magma listens to. */
public class FabricEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MagmaClient.LOADER = "fabric";
        MagmaClient.init();
        var bus = NeoForge.EVENT_BUS;

        ClientLifecycleEvents.CLIENT_STARTED.register(c -> MagmaClient.setup());
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> bus.post(new GameShuttingDownEvent()));
        ClientTickEvents.END_CLIENT_TICK.register(c -> bus.post(new ClientTickEvent.Post()));

        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> bus.post(new RenderLevelStageEvent(
                RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS, ctx.positionMatrix(), ctx.projectionMatrix(), ctx.camera(), ctx.tickCounter())));
        WorldRenderEvents.LAST.register(ctx -> bus.post(new RenderLevelStageEvent(
                RenderLevelStageEvent.Stage.AFTER_LEVEL, ctx.positionMatrix(), ctx.projectionMatrix(), ctx.camera(), ctx.tickCounter())));
        HudRenderCallback.EVENT.register((g, delta) -> bus.post(new RenderGuiEvent.Post(g, delta)));

        // ".goto x y z", ".build ..." etc. stay on your side and never reach the server
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> !bus.post(new ClientChatEvent(msg)).isCanceled());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> bus.post(new ClientPlayerNetworkEvent.LoggingIn()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> bus.post(new ClientPlayerNetworkEvent.LoggingOut()));

        // the title-screen update card
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> bus.post(new ScreenEvent.Render.Post(s, g, mx, my, delta)));
            ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) ->
                    !bus.post(new ScreenEvent.MouseButtonPressed.Pre(s, mx, my, button)).isCanceled());
        });

        // LavaGuard / VeinMiner: you start mining a block
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            return bus.post(new PlayerInteractEvent.LeftClickBlock(player, pos, dir)).isCanceled() ? InteractionResult.FAIL : InteractionResult.PASS;
        });
    }
}
