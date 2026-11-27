package dev.magma.client.module;

public enum Category {
    SAFETY("Safety", "⛨"),
    WORLD("World", "⛏"),
    MOVEMENT("Movement", "✈"),
    AUTOMATION("Automation", "⟳"),
    RENDER("Render", "◉"),
    CLIENT("Client", "⚙");

    public final String display;
    public final String icon;

    Category(String display, String icon) {
        this.display = display;
        this.icon = icon;
    }
}
