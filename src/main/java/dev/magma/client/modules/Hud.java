package dev.magma.client.modules;

import dev.magma.client.MagmaClient;
import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.util.ColorUtil;
import dev.magma.client.util.Render2D;
import dev.magma.client.util.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Hud extends Module {
    public final BoolSetting watermark = add(new BoolSetting("Watermark", "Client logo, FPS and ping in the corner.", true));
    public final BoolSetting arrayList = add(new BoolSetting("Module List", "List active modules on the right.", true));
    public final BoolSetting coords = add(new BoolSetting("Coordinates", "Show XYZ (with nether/overworld conversion).", true));
    public final BoolSetting minerCard = add(new BoolSetting("Miner Card", "Status card while AutoMiner runs.", true));

    public Hud() {
        super("HUD", "On-screen overlay: watermark, module list, coordinates.", Category.CLIENT, -1);
    }

    @Override public boolean showInArrayList() { return false; }

    @Override
    public void onRenderHud(GuiGraphics g, float pt) {
        Font font = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();

        if (watermark.get()) {
            String fps = mc.getFps() + " fps";
            var info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(mc.player.getUUID()) : null;
            String ping = info != null ? info.getLatency() + " ms" : "";
            String rest = "  " + fps + (ping.isEmpty() ? "" : "  " + ping);
            int nameW = font.width(MagmaClient.NAME);
            int w = 23 + nameW + font.width(rest) + 6;
            Render2D.shadow(g, 4, 4, w, 16, 5, 5, 0x50000000);
            Render2D.roundedRect(g, 4, 4, w, 16, 5, 0xD80F1117);
            dev.magma.client.util.Logo.draw(g, 6, 6, 12, 1f);
            Render2D.gradientText(g, MagmaClient.NAME, 21, 8, 1f, false);
            g.drawString(font, rest, 21 + nameW, 8, Theme.TEXT_DIM, false);
        }

        if (arrayList.get()) {
            List<Module> mods = new ArrayList<>();
            for (Module m : ModuleManager.all()) if (m.showInArrayList() && m.anim > 0.01f) mods.add(m);
            mods.sort(Comparator.comparingInt((Module m) -> font.width(label(m))).reversed());
            float y = 4;
            int i = 0;
            for (Module m : mods) {
                String name = m.getName();
                String info = m.getInfo();
                int w = font.width(label(m)) + 10;
                float a = m.anim;
                float x = sw - 4 - w * a;
                int h = 12;
                Render2D.rect(g, x, y, w, h * a, ColorUtil.mulAlpha(0xC80F1117, a));
                int acc = Theme.accent(i * 0.08f);
                Render2D.rect(g, sw - 4, y, 2, h * a, acc);
                g.drawString(font, name, (int) x + 5, (int) y + 2, ColorUtil.textAlpha(acc, a), false);
                if (info != null) g.drawString(font, " " + info, (int) x + 5 + font.width(name), (int) y + 2, ColorUtil.textAlpha(Theme.TEXT_DIM, a), false);
                y += h * a;
                i++;
            }
        }

        if (coords.get()) {
            double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
            boolean nether = mc.level.dimension() == net.minecraft.world.level.Level.NETHER;
            String main = (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z); // the block you're in, like F3
            String alt = nether ? "  OW " + (int) Math.floor(x * 8) + ", " + (int) Math.floor(z * 8) : "  N " + (int) Math.floor(x / 8) + ", " + (int) Math.floor(z / 8);
            int chatOffset = mc.gui.getChat().isChatFocused() ? 16 : 0;
            float cy = sh - 16 - chatOffset;
            String lbl = "XYZ ";
            int w = font.width(lbl + main + alt) + 12;
            Render2D.roundedRect(g, 4, cy, w, 13, 4, 0xC80F1117);
            g.drawString(font, lbl, 10, (int) cy + 3, Theme.accent(), false);
            g.drawString(font, main, 10 + font.width(lbl), (int) cy + 3, Theme.TEXT, false);
            g.drawString(font, alt, 10 + font.width(lbl + main), (int) cy + 3, Theme.TEXT_MUTED, false);
        }

        AutoMiner engine = ModuleManager.get(AutoMiner.class);
        BlockPos trip = engine.getGotoTarget();
        if (minerCard.get() && trip != null && !engine.isEnabled()) {
            boolean finding = ModuleManager.get(AutoFind.class).isEnabled();
            String title = finding ? "AutoFind \u00B7 " + ModuleManager.get(AutoFind.class).structure.get() : "Goto";
            int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(trip));
            int eta = engine.gotoEtaSeconds();
            String line1 = trip.getX() + " " + trip.getY() + " " + trip.getZ() + "  \u00B7  " + dist + "m" + (eta > 0 ? "  \u00B7  ~" + (eta >= 60 ? eta / 60 + "m " + eta % 60 + "s" : eta + "s") : "");
            String line2 = engine.getStatus();
            int w = Math.max(font.width(title) + 30, Math.max(font.width(line1), font.width(line2)) + 16);
            float x = 4, y = watermark.get() ? 26 : 4;
            Render2D.shadow(g, x, y, w, 38, 5, 5, 0x50000000);
            Render2D.roundedRect(g, x, y, w, 38, 5, 0xD80F1117);
            Render2D.roundedGradient(g, x, y, w, 2, 1, Theme.accent(), Theme.accent2(), false);
            g.drawString(font, title, (int) x + 8, (int) y + 6, Theme.TEXT, false);
            g.drawString(font, line1, (int) x + 8, (int) y + 17, Theme.accent(), false);
            g.drawString(font, line2, (int) x + 8, (int) y + 27, Theme.TEXT_DIM, false);
        }
        AutoMiner miner = engine;
        if (minerCard.get() && miner.isEnabled()) {
            String title = "AutoMiner · " + miner.speed.get();
            String st = miner.getStatus();
            String pick = "Tool " + miner.pickaxeDurability();
            String count = "Run " + miner.getMined() + "  ·  Total " + miner.getTotalMined() + "  ·  " + miner.getRate() + "/h";
            int w = Math.max(font.width(title) + 30, Math.max(Math.max(font.width(st), font.width(pick)), font.width(count)) + 16);
            float x = 4, y = watermark.get() ? 26 : 4;
            Render2D.shadow(g, x, y, w, 48, 5, 5, 0x50000000);
            Render2D.roundedRect(g, x, y, w, 48, 5, 0xD80F1117);
            Render2D.roundedGradient(g, x, y, w, 2, 1, Theme.accent(), Theme.accent2(), false);
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 200.0));
            Render2D.circle(g, x + w - 9, y + 9, 2.5f, ColorUtil.withAlpha(Theme.OK, 120 + (int) (135 * pulse)));
            g.drawString(font, title, (int) x + 8, (int) y + 6, Theme.TEXT, false);
            g.drawString(font, st, (int) x + 8, (int) y + 17, Theme.accent(), false);
            g.drawString(font, count, (int) x + 8, (int) y + 27, Theme.TEXT_DIM, false);
            g.drawString(font, pick, (int) x + 8, (int) y + 37, miner.pickaxeDurability().equals("none") ? Theme.DANGER : Theme.TEXT_MUTED, false);
        }
    }

    private static String label(Module m) {
        String info = m.getInfo();
        return info == null ? m.getName() : m.getName() + " " + info;
    }
}
