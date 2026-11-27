package dev.magma.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.magma.client.config.ConfigManager;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.modules.LavaGuard;
import dev.magma.client.util.Anim;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

public class ClientEvents {
    private final Minecraft mc = Minecraft.getInstance();
    private final Anim.Clock hudClock = new Anim.Clock();

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        dev.magma.client.util.PlayerControl.forceAttack = false;
        if (SelfTest.ENABLED) SelfTest.tick();
        if (!ModuleManager.ready() || mc.player == null || mc.level == null) return;
        if (mc.player.tickCount % 40 == 0) announceUpdate();
        if (mc.player.isDeadOrDying() && !wasDead) {
            dev.magma.client.modules.Goto.deathPos = mc.player.blockPosition();
            dev.magma.client.modules.Goto.deathDim = mc.level.dimension();
        }
        wasDead = mc.player.isDeadOrDying();
        for (Module m : ModuleManager.all()) {
            if (m.isEnabled()) {
                try {
                    m.onTick();
                } catch (Exception e) {
                    MagmaClient.LOGGER.error("[Magma] Error ticking {}", m.getName(), e);
                }
            }
        }
    }

    @SubscribeEvent
    public void onKey(InputEvent.Key event) {
        if (!ModuleManager.ready() || event.getAction() != GLFW.GLFW_PRESS) return;
        if (mc.screen != null || mc.player == null) return;
        if (InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_F3)) return;
        for (Module m : ModuleManager.all()) {
            if (m.getKey() != -1 && m.getKey() == event.getKey()) {
                m.toggle();
                if (!(m instanceof dev.magma.client.modules.ClickGui)) {
                    dev.magma.client.util.Notify.show(m.getName() + (m.isEnabled() ? " enabled" : " disabled"),
                            m.isEnabled() ? dev.magma.client.util.Theme.OK : 0xFF6B7282);
                }
                ConfigManager.save();
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderLevelStageEvent event) {
        if (!ModuleManager.ready() || mc.player == null || mc.level == null) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            for (Module m : ModuleManager.all()) if (m.isEnabled()) m.onRenderWorld(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            for (Module m : ModuleManager.all()) if (m.isEnabled()) m.onRenderLast(event);
        }
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        if (!ModuleManager.ready() || mc.player == null || mc.options.hideGui) return;
        float dt = hudClock.tick();
        for (Module m : ModuleManager.all()) {
            m.anim = Anim.approach(m.anim, m.isEnabled() ? 1f : 0f, dt * 6f);
            if (m.isEnabled()) m.onRenderHud(event.getGuiGraphics(), event.getPartialTick().getGameTimeDeltaPartialTick(true));
        }
        dev.magma.client.util.Notify.render(event.getGuiGraphics());
    }

    /** Fires when the attack key starts (and keeps) breaking a block. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (ModuleManager.ready() && dev.magma.client.modules.Freecam.active()) { // no hitting/placing from the free camera
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }
        if (!ModuleManager.ready() || !event.isAttack()) return;
        LavaGuard guard = ModuleManager.get(LavaGuard.class);
        if (guard.isEnabled() && guard.shouldBlockCurrentTarget()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /** Second line of defence: also catches held-down mining and anything that calls the game mode directly. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!ModuleManager.ready() || !event.getLevel().isClientSide()) return;
        LavaGuard guard = ModuleManager.get(LavaGuard.class);
        if (guard.isEnabled() && guard.isBlocked(event.getPos())) {
            event.setCanceled(true);
            guard.notifyBlocked(event.getPos());
            if (mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        }
    }

    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        dev.magma.client.util.OreTypes.clearCache();
        announcedUpdate = null;
    }

    private dev.magma.client.util.Updater.State announcedUpdate;
    private boolean wasDead;

    /** Tell the player once when an update is available / downloaded. */
    private void announceUpdate() {
        var st = dev.magma.client.util.Updater.state();
        if (st == announcedUpdate) return;
        if (st == dev.magma.client.util.Updater.State.AVAILABLE) {
            dev.magma.client.util.Chat.info("Magma " + dev.magma.client.util.Updater.latest() + " is available - open the menu (Right Shift) and click Update.");
            dev.magma.client.util.Notify.show("Magma update available", dev.magma.client.util.Theme.accent());
        } else if (st == dev.magma.client.util.Updater.State.READY) {
            dev.magma.client.util.Chat.info("Magma " + dev.magma.client.util.Updater.latest() + " downloaded - press Right Shift, then Restart now (or just restart Minecraft).");
            dev.magma.client.util.Notify.show("Update ready - restart to finish", dev.magma.client.util.Theme.OK);
        }
        announcedUpdate = st;
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ConfigManager.save();
    }

    /** ".goto x y z" etc. - handled locally, never sent to the server. */
    @SubscribeEvent
    public void onChat(net.neoforged.neoforge.client.event.ClientChatEvent event) {
        if (!ModuleManager.ready()) return;
        String m = event.getMessage().trim();
        if (m.startsWith(".toggle ") || m.startsWith(".t ")) { // .toggle fly
            Module mod = ModuleManager.byName(m.substring(m.indexOf(' ') + 1).trim());
            if (mod == null) dev.magma.client.util.Chat.info("No module called \"" + m.substring(m.indexOf(' ') + 1).trim() + "\".");
            else {
                mod.toggle();
                dev.magma.client.util.Chat.info(mod.getName() + (mod.isEnabled() ? " on" : " off"));
            }
            event.setCanceled(true);
            return;
        }
        if (dev.magma.client.modules.Goto.handleCommand(event.getMessage())
                || dev.magma.client.modules.Builder.handleCommand(event.getMessage())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onShutdown(net.neoforged.neoforge.event.GameShuttingDownEvent event) {
        ConfigManager.save();
    }
}
