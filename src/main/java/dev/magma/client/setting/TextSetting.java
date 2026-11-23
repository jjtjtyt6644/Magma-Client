package dev.magma.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Short editable text (used for coordinates). */
public class TextSetting extends Setting<String> {
    private final String allowed;
    private final int maxLength;

    public TextSetting(String name, String description, String defaultValue, String allowedChars, int maxLength) {
        super(name, description, defaultValue);
        this.allowed = allowedChars;
        this.maxLength = maxLength;
    }

    public boolean accepts(char c) { return allowed == null || allowed.indexOf(c) >= 0; }
    public int getMaxLength() { return maxLength; }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }
    @Override public void fromJson(JsonElement e) { if (e.isJsonPrimitive()) value = e.getAsString(); }
}
