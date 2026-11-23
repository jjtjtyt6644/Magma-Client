package dev.magma.client.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.function.Consumer;

/** Batched through-wall box/line rendering for ESP style overlays. */
public final class WorldRender {
    private static final ByteBufferBuilder LINE_BUF = new ByteBufferBuilder(1 << 18);
    private static final ByteBufferBuilder FILL_BUF = new ByteBufferBuilder(1 << 18);

    private WorldRender() {}

    public static final class Batch {
        final BufferBuilder lines;
        final BufferBuilder fills;
        final Vec3 cam;
        final Matrix4f id = new Matrix4f();

        Batch(Vec3 cam) {
            this.cam = cam;
            this.lines = new BufferBuilder(LINE_BUF, VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            this.fills = new BufferBuilder(FILL_BUF, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        }

        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int color) {
            lines.addVertex(id, (float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z)).setColor(color);
            lines.addVertex(id, (float) (x2 - cam.x), (float) (y2 - cam.y), (float) (z2 - cam.z)).setColor(color);
        }

        public void block(BlockPos pos, int outline, int fill) {
            box(new AABB(pos), outline, fill);
        }

        public void box(AABB bb, int outline, int fill) {
            double x1 = bb.minX, y1 = bb.minY, z1 = bb.minZ, x2 = bb.maxX, y2 = bb.maxY, z2 = bb.maxZ;
            if (ColorUtil.alpha(outline) > 0) {
                line(x1, y1, z1, x2, y1, z1, outline); line(x2, y1, z1, x2, y1, z2, outline);
                line(x2, y1, z2, x1, y1, z2, outline); line(x1, y1, z2, x1, y1, z1, outline);
                line(x1, y2, z1, x2, y2, z1, outline); line(x2, y2, z1, x2, y2, z2, outline);
                line(x2, y2, z2, x1, y2, z2, outline); line(x1, y2, z2, x1, y2, z1, outline);
                line(x1, y1, z1, x1, y2, z1, outline); line(x2, y1, z1, x2, y2, z1, outline);
                line(x2, y1, z2, x2, y2, z2, outline); line(x1, y1, z2, x1, y2, z2, outline);
            }
            if (ColorUtil.alpha(fill) > 0) {
                float ax = (float) (x1 - cam.x), ay = (float) (y1 - cam.y), az = (float) (z1 - cam.z);
                float bx = (float) (x2 - cam.x), by = (float) (y2 - cam.y), bz = (float) (z2 - cam.z);
                quad(ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz, fill);
                quad(ax, by, az, ax, by, bz, bx, by, bz, bx, by, az, fill);
                quad(ax, ay, az, ax, by, az, bx, by, az, bx, ay, az, fill);
                quad(ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz, fill);
                quad(ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az, fill);
                quad(bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz, fill);
            }
        }

        private void quad(float x1, float y1, float z1, float x2, float y2, float z2,
                          float x3, float y3, float z3, float x4, float y4, float z4, int c) {
            fills.addVertex(id, x1, y1, z1).setColor(c);
            fills.addVertex(id, x2, y2, z2).setColor(c);
            fills.addVertex(id, x3, y3, z3).setColor(c);
            fills.addVertex(id, x4, y4, z4).setColor(c);
        }
    }

    public static void draw(RenderLevelStageEvent event, Consumer<Batch> fn) {
        Vec3 cam = event.getCamera().getPosition();
        Batch batch = new Batch(cam);
        fn.accept(batch);
        MeshData fillMesh = batch.fills.build();
        MeshData lineMesh = batch.lines.build();
        if (fillMesh == null && lineMesh == null) return;

        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if (fillMesh != null) BufferUploader.drawWithShader(fillMesh);
        RenderSystem.lineWidth(2f);
        if (lineMesh != null) BufferUploader.drawWithShader(lineMesh);
        RenderSystem.lineWidth(1f);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        RenderSystem.restoreProjectionMatrix();
        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
