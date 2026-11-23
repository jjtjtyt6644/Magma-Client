package dev.magma.client.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pick any number of options (e.g. which ores to mine). */
public class MultiSetting extends Setting<Set<String>> {
    private final List<String> options;
    private final Set<String> defaults;

    public MultiSetting(String name, String description, List<String> options, String... defaults) {
        super(name, description, new LinkedHashSet<>(List.of(defaults)));
        this.options = options;
        this.defaults = new LinkedHashSet<>(List.of(defaults));
        this.value = new LinkedHashSet<>(this.defaults);
    }

    public List<String> getOptions() { return options; }
    public boolean has(String o) { return value.contains(o); }

    public void toggle(String o) {
        if (!value.remove(o)) value.add(o);
    }

    @Override public void reset() { value = new LinkedHashSet<>(defaults); }

    public String summary() {
        if (value.isEmpty()) return "None";
        if (value.size() == 1) return value.iterator().next();
        return value.size() + " selected";
    }

    @Override
    public JsonElement toJson() {
        JsonArray a = new JsonArray();
        for (String s : value) a.add(s);
        return a;
    }

    @Override
    public void fromJson(JsonElement e) {
        if (!e.isJsonArray()) return;
        Set<String> v = new LinkedHashSet<>();
        for (JsonElement x : e.getAsJsonArray()) if (options.contains(x.getAsString())) v.add(x.getAsString());
        value = v;
    }
}
