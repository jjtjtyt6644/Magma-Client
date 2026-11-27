package dev.magma.client.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;

import java.util.LinkedHashMap;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * Through-wall outlines / boxes / tracers drawn with proper vanilla render types (the same line shader
 * Minecraft uses for block outlines). The old raw-buffer approach could be garbled by Iris/Sodium
 * (white lines shooting from the crosshair).
 */
public final class WorldRender {
    /** Lines that ignore depth (seen through walls). Uses the vanilla line shader, so it needs per-vertex normals. */
    public static final RenderType LINES = RenderType.create("magma_lines_see_through", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /** Translucent filled boxes, also seen through walls. */
    public static final RenderType FILL = RenderType.create("magma_fill_see_through", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 4096, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /**
     * Real block textures drawn through walls (X-Ray ore blocks): ignores depth, so no depth-buffer wiping is needed,
     * and it lives in our own buffer so shader mods (Iris) can't defer it behind the world.
     */
    public static final RenderType ORE = RenderType.create("magma_ore_see_through", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 262144, false, true,
            RenderType.CompositeState.builder()
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(true));

    /** Our own buffers (ore blocks, then fills, then lines on top) - never shares state with the level's buffers. */
    private static final MultiBufferSource.BufferSource SOURCE;

    static {
        LinkedHashMap<RenderType, ByteBufferBuilder> fixed = new LinkedHashMap<>();
        fixed.put(ORE, new ByteBufferBuilder(ORE.bufferSize()));
        fixed.put(FILL, new ByteBufferBuilder(FILL.bufferSize()));
        fixed.put(LINES, new ByteBufferBuilder(LINES.bufferSize()));
        SOURCE = MultiBufferSource.immediateWithBuffers(fixed, new ByteBufferBuilder(256));
    }

    private WorldRender() {}

    public static final class Batch {
        private final Vec3 cam;
        private final PoseStack.Pose pose = new PoseStack().last(); // identity: positions are already camera-relative

        Batch(Vec3 cam) {
            this.cam = cam;
        }

        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int color) {
            float ax = (float) (x1 - cam.x), ay = (float) (y1 - cam.y), az = (float) (z1 - cam.z);
            float bx = (float) (x2 - cam.x), by = (float) (y2 - cam.y), bz = (float) (z2 - cam.z);
            float nx = bx - ax, ny = by - ay, nz = bz - az;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) return;
            nx /= len; ny /= len; nz /= len;
            VertexConsumer v = SOURCE.getBuffer(LINES);
            v.addVertex(pose, ax, ay, az).setColor(color).setNormal(pose, nx, ny, nz);
            v.addVertex(pose, bx, by, bz).setColor(color).setNormal(pose, nx, ny, nz);
        }

        public void block(BlockPos pos, int outline, int fill) {
            box(new AABB(pos), outline, fill);
        }

        public void box(AABB bb, int outline, int fill) {
            double x1 = bb.minX, y1 = bb.minY, z1 = bb.minZ, x2 = bb.maxX, y2 = bb.maxY, z2 = bb.maxZ;
            if (ColorUtil.alpha(fill) > 0) {
                float ax = (float) (x1 - cam.x), ay = (float) (y1 - cam.y), az = (float) (z1 - cam.z);
                float bx = (float) (x2 - cam.x), by = (float) (y2 - cam.y), bz = (float) (z2 - cam.z);
                VertexConsumer f = SOURCE.getBuffer(FILL);
                quad(f, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz, fill);
                quad(f, ax, by, az, ax, by, bz, bx, by, bz, bx, by, az, fill);
                quad(f, ax, ay, az, ax, by, az, bx, by, az, bx, ay, az, fill);
                quad(f, ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz, fill);
                quad(f, ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az, fill);
                quad(f, bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz, fill);
            }
            if (ColorUtil.alpha(outline) > 0) {
                line(x1, y1, z1, x2, y1, z1, outline); line(x2, y1, z1, x2, y1, z2, outline);
                line(x2, y1, z2, x1, y1, z2, outline); line(x1, y1, z2, x1, y1, z1, outline);
                line(x1, y2, z1, x2, y2, z1, outline); line(x2, y2, z1, x2, y2, z2, outline);
                line(x2, y2, z2, x1, y2, z2, outline); line(x1, y2, z2, x1, y2, z1, outline);
                line(x1, y1, z1, x1, y2, z1, outline); line(x2, y1, z1, x2, y2, z1, outline);
                line(x2, y1, z2, x2, y2, z2, outline); line(x1, y1, z2, x1, y2, z2, outline);
            }
        }

        private void quad(VertexConsumer f, float x1, float y1, float z1, float x2, float y2, float z2,
                          float x3, float y3, float z3, float x4, float y4, float z4, int c) {
            f.addVertex(pose, x1, y1, z1).setColor(c);
            f.addVertex(pose, x2, y2, z2).setColor(c);
            f.addVertex(pose, x3, y3, z3).setColor(c);
            f.addVertex(pose, x4, y4, z4).setColor(c);
        }
    }

    /** Draw real block models (X-Ray) through walls with the given opacity. {@code fn} gets a buffer source to render into. */
    public static void drawBlocks(RenderLevelStageEvent event, float opacity, Consumer<MultiBufferSource> fn) {
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        // no fog: at this point of the frame the fog settings are whatever the sky/clouds/weather left behind,
        // which made ores fade out or flash. X-Ray should always be fully visible.
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        // NO_DEPTH_TEST ("always") doesn't switch depth testing off - it assumes it's already off. At this point
        // Minecraft has it ON, so everything was still hidden behind walls. Switch it off ourselves.
        RenderSystem.disableDepthTest();
        try {
            fn.accept(type -> new AlphaVertexConsumer(SOURCE.getBuffer(ORE), opacity));
        } finally {
            SOURCE.endBatch();
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.enableDepthTest();
            RenderSystem.restoreProjectionMatrix();
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    public static void draw(RenderLevelStageEvent event, Consumer<Batch> fn) {
        Vec3 cam = event.getCamera().getPosition();
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        // no fog: at this point of the frame the fog settings are whatever the sky/clouds/weather left behind,
        // which made ores fade out or flash. X-Ray should always be fully visible.
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        // NO_DEPTH_TEST ("always") doesn't switch depth testing off - it assumes it's already off. At this point
        // Minecraft has it ON, so everything was still hidden behind walls. Switch it off ourselves.
        RenderSystem.disableDepthTest();
        try {
            fn.accept(new Batch(cam));
        } finally {
            // always flush, even if something above threw, so nothing half-built lingers into the next frame
            SOURCE.endBatch();
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.enableDepthTest();
            RenderSystem.restoreProjectionMatrix();
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }
}
