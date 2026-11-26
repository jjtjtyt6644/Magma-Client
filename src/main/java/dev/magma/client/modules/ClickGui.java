package dev.magma.client.modules;

import dev.magma.client.gui.ClickGuiScreen;
import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.setting.BoolSetting;
import dev.magma.client.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

public class ClickGui extends Module {
    public final dev.magma.client.setting.ModeSetting style = add(new dev.magma.client.setting.ModeSetting("Menu Style", "Window = main menu with dashboard. Panels = floating category panels.", "Window", "Window", "Panels"));
    public final NumberSetting hue = add(new NumberSetting("Accent Hue", "Main accent color (0 = red, 20 = magma orange, 200 = blue).", 14, 0, 360, 1));
    public final NumberSetting spread = add(new NumberSetting("Gradient Spread", "How far the accent gradient shifts in hue.", 28, 0, 120, 1));
    public final BoolSetting rainbow = add(new BoolSetting("Rainbow", "Cycle the accent through every color.", false));
    public final BoolSetting blur = add(new BoolSetting("Blur", "Blur the game behind the GUI.", true));
    public final BoolSetting advancedSettings = add(new BoolSetting("Advanced Settings", "Show every fine-tuning option. Off = only the key settings (simpler).", false));
    public final BoolSetting checkUpdates = add(new BoolSetting("Check For Updates", "Look for a new Magma version on GitHub when the game starts.", true));
    public final BoolSetting autoUpdate = add(new BoolSetting("Auto Update", "Download new versions automatically; they install when you restart Minecraft.", true));
    public final BoolSetting reopenAfterUpdate = add(new BoolSetting("Reopen After Update", "After Update & restart, start Minecraft again by itself.", true));
    public final dev.magma.client.setting.ButtonSetting installNow = add(new dev.magma.client.setting.ButtonSetting("Install Update",
            "Download the newest version, save and close Minecraft, and swap the old Magma jar for the new one. Then press Play again.",
            "Update & restart", dev.magma.client.util.Updater::installAndRestart));
    public final dev.magma.client.setting.ButtonSetting checkNow = add(new dev.magma.client.setting.ButtonSetting("Updates", "Check GitHub for a new version right now.", "Check now",
            () -> dev.magma.client.util.Updater.checkAsync(false)));
    public final BoolSetting descriptions = add(new BoolSetting("Descriptions", "Show a tooltip for the hovered module/setting.", true));

    public ClickGui() {
        super("ClickGUI", "Opens this menu. Drag panels, right-click modules for settings, middle-click to bind.", Category.CLIENT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        dev.magma.client.setting.Setting.showAdvanced = () -> this.advancedSettings.get();
        advanced(spread, rainbow, blur, descriptions);
    }

    @Override public boolean showInArrayList() { return false; }

    @Override
    protected void onEnable() {
        mc.setScreen(style.is("Panels") ? new ClickGuiScreen() : new dev.magma.client.gui.MenuScreen());
        setEnabled(false);
    }
}
