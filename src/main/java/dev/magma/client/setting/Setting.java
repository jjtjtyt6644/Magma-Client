package dev.magma.client.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    private final String name;
    private final String description;
    protected T value;
    private final T defaultValue;
    private BooleanSupplier visible = () -> true;
    private boolean advanced;
    /** Set by the menu: whether "Advanced settings" are shown. Kept simple by default. */
    public static BooleanSupplier showAdvanced = () -> false;

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.value = defaultValue;
        this.defaultValue = defaultValue;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public T get() { return value; }
    public void set(T value) { this.value = value; }
    public void reset() { this.value = defaultValue; }
    public boolean isVisible() { return visible.getAsBoolean() && (!advanced || showAdvanced.getAsBoolean()); }
    public boolean isAdvanced() { return advanced; }
    /** Visible if it would be shown with advanced settings on (used to count "N more advanced settings"). */
    public boolean isVisibleIgnoringAdvanced() { return visible.getAsBoolean(); }
    public void setAdvanced(boolean advanced) { this.advanced = advanced; }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier supplier) {
        this.visible = supplier;
        return (S) this;
    }

    public abstract JsonElement toJson();
    public abstract void fromJson(JsonElement element);
}
