package dev.magma.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A clickable action shown as a button in the menus. Not saved. */
public class ButtonSetting extends Setting<String> {
    private final Runnable action;

    public ButtonSetting(String name, String description, String label, Runnable action) {
        super(name, description, label);
        this.action = action;
    }

    public void press() { action.run(); }

    @Override public JsonElement toJson() { return JsonNull.INSTANCE; }
    @Override public void fromJson(JsonElement e) {}
}
