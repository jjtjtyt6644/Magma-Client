package dev.magma.client.module;

import dev.magma.client.modules.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();
    private static boolean initialized;

    private ModuleManager() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        MODULES.add(new LavaGuard());
        MODULES.add(new SafeWalk());
        MODULES.add(new AutoMiner());
        MODULES.add(new Goto());
        MODULES.add(new Base());
        MODULES.add(new AutoFind());
        MODULES.add(new Builder());
        MODULES.add(new Fly());
        MODULES.add(new NoFall());
        MODULES.add(new Freecam());
        MODULES.add(new VillagerRoller());
        MODULES.add(new FastBreak());
        MODULES.add(new AutoTool());
        MODULES.add(new InvCleaner());
        MODULES.add(new AutoEat());
        MODULES.add(new AutoWalk());
        MODULES.add(new Xray());
        MODULES.add(new Fullbright());
        MODULES.add(new Hud());
        MODULES.add(new ClickGui());
    }

    public static boolean ready() { return initialized; }

    public static List<Module> all() { return Collections.unmodifiableList(MODULES); }

    public static List<Module> byCategory(Category c) {
        List<Module> list = new ArrayList<>();
        for (Module m : MODULES) if (m.getCategory() == c) list.add(m);
        return list;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Module> T get(Class<T> clazz) {
        for (Module m : MODULES) if (m.getClass() == clazz) return (T) m;
        throw new IllegalArgumentException("No module " + clazz);
    }

    public static Module byName(String name) {
        for (Module m : MODULES) if (m.getName().equalsIgnoreCase(name)) return m;
        return null;
    }
}
