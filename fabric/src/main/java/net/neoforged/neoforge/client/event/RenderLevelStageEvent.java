// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.client.event;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.neoforged.bus.api.Event;
import org.joml.Matrix4f;

public class RenderLevelStageEvent extends Event {
    public static final class Stage {
        public static final Stage AFTER_TRANSLUCENT_BLOCKS = new Stage();
        public static final Stage AFTER_LEVEL = new Stage();
        private Stage() {}
    }

    private final Stage stage;
    private final Matrix4f modelView, projection;
    private final Camera camera;
    private final DeltaTracker delta;

    public RenderLevelStageEvent(Stage stage, Matrix4f modelView, Matrix4f projection, Camera camera, DeltaTracker delta) {
        this.stage = stage;
        this.modelView = modelView;
        this.projection = projection;
        this.camera = camera;
        this.delta = delta;
    }

    public Stage getStage() { return stage; }
    public Matrix4f getModelViewMatrix() { return modelView; }
    public Matrix4f getProjectionMatrix() { return projection; }
    public Camera getCamera() { return camera; }
    public DeltaTracker getPartialTick() { return delta; }
}
