package dev.magma.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

public class ModeSetting extends Setting<String> {
    private final List<String> modes;

    public ModeSetting(String name, String description, String defaultValue, String... modes) {
        super(name, description, defaultValue);
        this.modes = List.of(modes);
    }

    public List<String> getModes() { return modes; }
    public boolean is(String mode) { return value.equalsIgnoreCase(mode); }

    public void cycle(int dir) {
        int i = modes.indexOf(value);
        i = Math.floorMod(i + dir, modes.size());
        value = modes.get(i);
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }
    @Override public void fromJson(JsonElement e) {
        if (e.isJsonPrimitive() && modes.contains(e.getAsString())) value = e.getAsString();
    }
}
