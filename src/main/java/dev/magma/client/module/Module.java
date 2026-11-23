package dev.magma.client.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.magma.client.setting.Setting;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    protected static final Minecraft mc = Minecraft.getInstance();

    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private boolean enabled;
    private int key;
    /** Smoothed 0..1 value used by HUD/GUI animations. */
    public float anim;

    protected Module(String name, String description, Category category, int defaultKey) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.key = defaultKey;
    }

    protected <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public List<Setting<?>> getSettings() { return settings; }
    public boolean isEnabled() { return enabled; }
    public int getKey() { return key; }
    public void setKey(int key) { this.key = key; }
    /** Hide fine-tuning settings behind "Advanced settings" so the menu stays simple. */
    protected void advanced(Setting<?>... list) {
        for (Setting<?> s : list) s.setAdvanced(true);
    }

    /** Companion modules that make this one better; shown as one-click "Add-ons" in the menu. */
    public List<Class<? extends Module>> addons() { return List.of(); }

    /** Automation modules start OFF every session so nothing moves your character on login. */
    public boolean persistEnabled() { return true; }

    /** Whether this module is listed on the HUD array list. */
    public boolean showInArrayList() { return true; }
    /** Extra info shown in grey next to the name on the HUD. */
    public String getInfo() { return null; }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled) onEnable(); else onDisable();
        // remember the user's last state (no-op until the config has been loaded, so defaults never overwrite it)
        dev.magma.client.config.ConfigManager.save();
    }

    protected void onEnable() {}
    protected void onDisable() {}
    public void onTick() {}
    public void onRenderWorld(RenderLevelStageEvent event) {}
    /** Fired after the whole level is drawn (depth can be cleared here safely). */
    public void onRenderLast(RenderLevelStageEvent event) {}
    public void onRenderHud(GuiGraphics g, float partialTick) {}

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("key", key);
        JsonObject s = new JsonObject();
        for (Setting<?> setting : settings) s.add(setting.getName(), setting.toJson());
        o.add("settings", s);
        return o;
    }

    public void fromJson(JsonObject o) {
        if (o.has("key")) key = o.get("key").getAsInt();
        if (o.has("settings")) {
            JsonObject s = o.getAsJsonObject("settings");
            for (Setting<?> setting : settings) {
                JsonElement e = s.get(setting.getName());
                if (e != null) {
                    try { setting.fromJson(e); } catch (Exception ignored) {}
                }
            }
        }
        if (o.has("enabled") && persistEnabled()) setEnabled(o.get("enabled").getAsBoolean());
    }
}
