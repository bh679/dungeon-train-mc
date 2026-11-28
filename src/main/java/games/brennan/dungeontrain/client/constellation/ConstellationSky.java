package games.brennan.dungeontrain.client.constellation;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.client.SkyDomeDraw;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import games.brennan.dungeontrain.client.perks.SkyViewAccess;
import games.brennan.dungeontrain.client.tributeroom.ScreenToTexture;
import net.minecraft.Util;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * The constellation patch's picture and its draw.
 *
 * <p>The picture is the Perks menu's own sky ({@link PerksScreen#forSky()}) drawn into a texture by
 * the tribute TV's {@link ScreenToTexture}, so what hangs in the sky is exactly what the menu shows:
 * the same stars, links, class titles and lit perks, and names only on hover. The crosshair is the
 * mouse: {@link #aim} turns the look direction into a point on the menu's canvas.</p>
 *
 * <p>The disc is the menu's square sky region (the map's 800 x 800 fit), mapped round onto
 * {@link ConstellationGeometry}. See {@link #drawPatch} for how it meets the sky, clouds and blocks:
 * a block is either in front of a star and hidden whole, or drawn as normal, never partway. The
 * patch's passes force the fragment depth to the far plane with {@code glDepthRange(1, 1)} so the
 * depth test does the sorting; depth is never written.</p>
 *
 * <p>Render thread only.</p>
 */
public final class ConstellationSky {

    private static final int SEGMENTS = 72;
    /** Rings across the feather, for a smooth falloff with per-vertex alpha. */
    private static final int FEATHER_RINGS = 8;
    /** Picture redraws at most this often while nothing changes (the menu twinkles and glows). */
    private static final long REDRAW_MS = 50;
    private static final int OFF = -10_000;

    private static ScreenToTexture texture;
    private static long lastDrawnMs = Long.MIN_VALUE;
    private static int mouseX = OFF, mouseY = OFF;
    private static int drawnMouseX = OFF - 1, drawnMouseY = OFF - 1;

    private ConstellationSky() {}

    /** Point the menu's mouse at where {@code look} meets the disc, or nowhere. */
    static void aim(Vector3f look) {
        if (texture == null) return;
        float[] hit = look == null ? null : ConstellationGeometry.hit(look);
        if (hit == null || hit[0] * hit[0] + hit[1] * hit[1] > 1f) {
            mouseX = OFF;
            mouseY = OFF;
            return;
        }
        float[] r = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        mouseX = Math.round(r[0] + hit[0] * r[2]);
        mouseY = Math.round(r[1] - hit[1] * r[2]);
    }

    /** Draw the picture if it is due. Frame start only, before the level pass. */
    static void refresh(float partialTick) {
        ScreenToTexture t = texture();
        long now = Util.getMillis();
        boolean moved = mouseX != drawnMouseX || mouseY != drawnMouseY;
        if (!moved && now - lastDrawnMs < REDRAW_MS && t.hasPicture()) return;
        t.renderFrame(mouseX, mouseY, partialTick);
        lastDrawnMs = now;
        drawnMouseX = mouseX;
        drawnMouseY = mouseY;
    }

    static void tick() {
        if (texture != null) texture.tick();
    }

    /** Let the picture go: leaving a world, or the patch has closed. */
    static void release() {
        if (texture != null) texture.close();
        texture = null;
        lastDrawnMs = Long.MIN_VALUE;
        mouseX = mouseY = OFF;
    }

    /** Something wants the picture: the patch has been open, or a window block asked for it. */
    static boolean wanted() {
        return texture != null;
    }

    static boolean hasPicture() {
        return texture != null && texture.hasPicture();
    }

    private static ScreenToTexture texture() {
        if (texture == null) {
            texture = new ScreenToTexture(PerksScreen.SKY_BG & 0xFFFFFF).opaque();
            texture.attach(PerksScreen.forSky());
        }
        return texture;
    }

    /** Stencil bit the blocks in front of a star are stamped into, just for the draw. */
    static final int HIDE_BIT = 0x20;
    /** How far past the disc the hidden-block pass reaches, in radii: a hidden block is hidden whole. */
    private static final float HIDDEN_REACH = 3f;

    /**
     * Directions from the eye to every drawn star of the picture: its centre and four points round its
     * rim, so a block that covers any part of a star counts as in its way. Empty with no picture.
     */
    static java.util.List<Vector3f> starDirections() {
        if (texture == null || !(texture.screen() instanceof PerksScreen screen)) return java.util.List.of();
        float[] sq = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        java.util.List<Vector3f> out = new java.util.ArrayList<>();
        for (float[] star : screen.drawnStars()) {
            float r = star[2] * 0.8f;
            float[][] offsets = {{0, 0}, {r, 0}, {-r, 0}, {0, r}, {0, -r}};
            for (float[] o : offsets) {
                float x = (star[0] + o[0] - sq[0]) / sq[2];
                float y = -(star[1] + o[1] - sq[1]) / sq[2];
                if (x * x + y * y >= 1f) continue;
                out.add(ConstellationGeometry.point(x, y).normalize());
            }
        }
        return out;
    }

    /**
     * The patch, over the finished level, at {@code alpha}.
     *
     * <ol>
     *   <li>The real sky (depth still at the far plane) gets the rim ({@link ConstellationEdge}).</li>
     *   <li>Clouds ({@link ConstellationClouds}) count as sky and get the same rim.</li>
     *   <li>Blocks in front of a star ({@link ConstellationOcclusion}) are hidden whole: every pixel of
     *       them is painted as sky ({@code skyRgb}) and then gets the patch with the same rim, so they
     *       look exactly like the sky around them. Every other block draws as normal.</li>
     * </ol>
     *
     * @param view the level's camera-rotation matrix ({@code RenderLevelStageEvent#getModelViewMatrix})
     */
    static void drawPatch(Matrix4f view, Matrix4f projection, float alpha, ConstellationEdge edge,
                          net.minecraft.world.phys.Vec3 cam, float partialTick, int skyRgb) {
        if (!hasPicture() || alpha <= 0f) {
            ConstellationClouds.frameDone();
            return;
        }
        boolean clouds = ConstellationClouds.markedThisFrame();
        java.util.List<ConstellationOcclusion.Hidden> hidden = ConstellationOcclusion.hidden();
        boolean blocks = !hidden.isEmpty() && games.brennan.dungeontrain.client.skybox.SkyboxStencil.isAvailable()
            && !games.brennan.dungeontrain.client.ShaderCompat.active();
        // By the last level stage the model-view stack may already hold the camera rotation, and other
        // passes may have left their own projection: draw in exactly the level's frame, once.
        org.joml.Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            if (blocks) markHidden(view, hidden, cam, partialTick);

            GL11.glDepthRange(1.0, 1.0);

            // 1. The real sky: depth still at the far plane.
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            drawDisc(view, alpha, edge.soft(), 1f);

            // 2. Clouds are sky to the patch: wherever they stamped their bit, the same rim.
            if (clouds) {
                GL11.glEnable(GL11.GL_STENCIL_TEST);
                RenderSystem.stencilMask(0x00);
                RenderSystem.stencilFunc(GL11.GL_EQUAL, ConstellationClouds.BIT, ConstellationClouds.BIT);
                RenderSystem.depthFunc(GL11.GL_ALWAYS);
                drawDisc(view, alpha, edge.soft(), 1f);
            }

            // 3. Blocks in a star's way: hidden whole, as sky, with the patch over them.
            if (blocks) {
                GL11.glEnable(GL11.GL_STENCIL_TEST);
                RenderSystem.stencilMask(0x00);
                RenderSystem.stencilFunc(GL11.GL_EQUAL, HIDE_BIT, HIDE_BIT);
                RenderSystem.depthFunc(GL11.GL_GREATER);
                fillDisc(view, alpha, skyRgb, HIDDEN_REACH);
                drawDisc(view, alpha, edge.soft(), 1f);
            }
        } finally {
            RenderSystem.stencilMask(0xFF);
            RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
            RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.colorMask(true, true, true, true);
            ConstellationClouds.frameDone();
            GL11.glDepthRange(0.0, 1.0);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.restoreProjectionMatrix();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * Stamp {@link #HIDE_BIT} wherever a hidden block's cell is the nearest thing drawn: its cube,
     * a hair larger than the block so its faces win the depth test against the block's own, colour
     * writes off. Carriage blocks are placed with their carriage's render pose.
     */
    private static void markHidden(Matrix4f view, java.util.List<ConstellationOcclusion.Hidden> hidden,
                                   net.minecraft.world.phys.Vec3 cam, float partialTick) {
        games.brennan.dungeontrain.client.skybox.SkyboxStencil.beginMarkPass(HIDE_BIT);
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        java.util.Map<java.util.UUID, dev.ryanhcode.sable.sublevel.ClientSubLevel> subs = subLevels();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        org.joml.Vector3d local = new org.joml.Vector3d();
        org.joml.Vector3d world = new org.joml.Vector3d();
        float[] cx = new float[8], cy = new float[8], cz = new float[8];
        final double grow = 0.003;
        int quads = 0;
        for (ConstellationOcclusion.Hidden h : hidden) {
            dev.ryanhcode.sable.companion.math.Pose3dc pose = null;
            if (h.subLevel() != null) {
                dev.ryanhcode.sable.sublevel.ClientSubLevel sub = subs.get(h.subLevel());
                if (sub == null || (pose = sub.renderPose(partialTick)) == null) continue;
            }
            int bx = net.minecraft.core.BlockPos.getX(h.pos());
            int by = net.minecraft.core.BlockPos.getY(h.pos());
            int bz = net.minecraft.core.BlockPos.getZ(h.pos());
            for (int c = 0; c < 8; c++) {
                local.set(bx + ((c & 1) == 0 ? -grow : 1 + grow), by + ((c & 2) == 0 ? -grow : 1 + grow),
                    bz + ((c & 4) == 0 ? -grow : 1 + grow));
                if (pose != null) pose.transformPosition(local, world);
                else world.set(local);
                cx[c] = (float) (world.x - cam.x);
                cy[c] = (float) (world.y - cam.y);
                cz[c] = (float) (world.z - cam.z);
            }
            for (int[] face : CUBE_FACES) {
                for (int c : face) bb.addVertex(view, cx[c], cy[c], cz[c]);
                quads++;
            }
        }
        com.mojang.blaze3d.vertex.MeshData mesh = bb.build();
        if (mesh != null) {
            if (quads > 0) BufferUploader.drawWithShader(mesh);
            else mesh.close();
        }
        RenderSystem.colorMask(true, true, true, true);
    }

    /** Corner indices ({@code bit 0 = x, bit 1 = y, bit 2 = z}) of the cube's six faces. */
    private static final int[][] CUBE_FACES = {
        {0, 1, 3, 2}, {4, 6, 7, 5}, // -z, +z
        {0, 2, 6, 4}, {1, 5, 7, 3}, // -x, +x
        {0, 4, 5, 1}, {2, 3, 7, 6}, // -y, +y
    };

    private static java.util.Map<java.util.UUID, dev.ryanhcode.sable.sublevel.ClientSubLevel> subLevels() {
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return java.util.Map.of();
        dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer container =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return java.util.Map.of();
        java.util.Map<java.util.UUID, dev.ryanhcode.sable.sublevel.ClientSubLevel> out = new java.util.HashMap<>();
        for (dev.ryanhcode.sable.sublevel.ClientSubLevel sub : container.getAllSubLevels()) out.put(sub.getUniqueId(), sub);
        return out;
    }

    /** A flat disc of one colour out to {@code outer} radii, full strength to its edge. */
    private static void fillDisc(Matrix4f view, float alpha, int rgb, float outer) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        int color = SkyDomeDraw.argb(alpha, rgb);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int s = 0; s < SEGMENTS; s++) {
            double t0 = 2 * Math.PI * s / SEGMENTS, t1 = 2 * Math.PI * (s + 1) / SEGMENTS;
            Vector3f c = ConstellationGeometry.point(0f, 0f);
            Vector3f a = ConstellationGeometry.point(outer * (float) Math.cos(t0), outer * (float) Math.sin(t0));
            Vector3f b = ConstellationGeometry.point(outer * (float) Math.cos(t1), outer * (float) Math.sin(t1));
            bb.addVertex(view, c.x, c.y, c.z).setColor(color);
            bb.addVertex(view, a.x, a.y, a.z).setColor(color);
            bb.addVertex(view, b.x, b.y, b.z).setColor(color);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.disableBlend();
    }

    /**
     * The window block's sky: the night dome with the patch on it at full strength. Drawn into the
     * stencil-masked hole, depth test off, by {@code SkyboxStencil}.
     */
    public static void renderAsSkySource(Matrix4f view) {
        SkyDomeDraw.fillDome(view, PerksScreen.SKY_BG & 0xFFFFFF, 1.0F);
        if (!hasPicture()) {
            // Seen through a window before anyone has stood in a block: make the picture next frame.
            texture();
            return;
        }
        RenderSystem.disableDepthTest();
        drawDisc(view, 1f, true, 1f);
    }

    /**
     * One disc out to {@code outer} radii: a fan of rings, the texture's sky square on it. Soft: alpha
     * falls off at the rim ({@link ConstellationEdge#alphaAt}). Hard: full strength to {@code outer}.
     */
    private static void drawDisc(Matrix4f view, float alpha, boolean soft, float outer) {
        float[] sq = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        float gw = texture.guiWidth(), gh = texture.guiHeight();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture.location());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        float[] radii = soft ? ringRadii(true) : new float[] {0f, outer};
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int ring = 0; ring < radii.length - 1; ring++) {
            float r0 = radii[ring], r1 = radii[ring + 1];
            float a0 = soft ? alpha * ConstellationEdge.alphaAt(r0, true) : alpha;
            float a1 = soft ? alpha * ConstellationEdge.alphaAt(r1, true) : alpha;
            for (int s = 0; s < SEGMENTS; s++) {
                double t0 = 2 * Math.PI * s / SEGMENTS, t1 = 2 * Math.PI * (s + 1) / SEGMENTS;
                float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0);
                float c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
                if (r0 == 0f) {
                    vertex(bb, view, 0f, 0f, sq, gw, gh, a0);
                } else {
                    vertex(bb, view, r0 * c0, r0 * s0, sq, gw, gh, a0);
                    vertex(bb, view, r1 * c0, r1 * s0, sq, gw, gh, a1);
                    vertex(bb, view, r1 * c1, r1 * s1, sq, gw, gh, a1);
                    vertex(bb, view, r0 * c0, r0 * s0, sq, gw, gh, a0);
                    vertex(bb, view, r1 * c1, r1 * s1, sq, gw, gh, a1);
                    vertex(bb, view, r0 * c1, r0 * s1, sq, gw, gh, a0);
                    continue;
                }
                vertex(bb, view, r1 * c0, r1 * s0, sq, gw, gh, a1);
                vertex(bb, view, r1 * c1, r1 * s1, sq, gw, gh, a1);
            }
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** Centre, the start of the feather, rings through it, the rim. Hard: centre and rim only. */
    static float[] ringRadii(boolean soft) {
        if (!soft) return new float[] {0f, 1f};
        float[] r = new float[FEATHER_RINGS + 2];
        r[0] = 0f;
        for (int i = 0; i <= FEATHER_RINGS; i++) {
            r[i + 1] = ConstellationEdge.FEATHER_START + (1f - ConstellationEdge.FEATHER_START) * i / FEATHER_RINGS;
        }
        return r;
    }

    private static void vertex(BufferBuilder bb, Matrix4f view, float x, float y, float[] sq, float gw, float gh, float a) {
        Vector3f p = ConstellationGeometry.point(x, y);
        float u = (sq[0] + x * sq[2]) / gw;
        float v = (sq[1] - y * sq[2]) / gh;
        bb.addVertex(view, p.x, p.y, p.z).setUv(u, v).setColor(SkyDomeDraw.argb(a, 0xFFFFFF));
    }
}
