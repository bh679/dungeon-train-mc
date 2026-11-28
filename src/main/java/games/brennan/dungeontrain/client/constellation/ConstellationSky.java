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

    /** The sky copy of the Perks menu behind the picture, or null before there is one. */
    static PerksScreen screen() {
        return texture != null && texture.screen() instanceof PerksScreen s ? s : null;
    }

    /** The server refused a point put in from the sky: the bar over the hotbar says why. */
    public static void onRefused(String perkId, String reasonKey) {
        PerksScreen s = screen();
        if (s != null) s.skyRefused(perkId, reasonKey);
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
    /**
     * How far out the hidden-block pass reaches, in radii (about 80 degrees off the patch's centre): the
     * patch is solid all the way over a hidden block, so it is hidden whole and never crossfaded.
     */
    private static final float HIDDEN_REACH = 10f;

    /**
     * Directions from the eye across everything blocks must clear for: each star and a padding round it
     * twice its size, and round the Engine a disc twice as wide as the tier 1 ring, so the middle of the
     * constellation is clear too. A block any of these rays meets is hidden whole. Empty with no picture.
     */
    static java.util.List<Vector3f> starDirections() {
        if (texture == null || !(texture.screen() instanceof PerksScreen screen)) return java.util.List.of();
        float[] sq = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        java.util.List<float[]> stars = screen.drawnStars();
        java.util.List<float[]> samples = new java.util.ArrayList<>();
        for (float[] star : stars) StarPadding.around(star[0], star[1], star[2], samples);
        float[] engine = null;
        float tierOne = 0f;
        for (float[] star : stars) if (star.length > 3 && star[3] == 0) engine = star;
        if (engine != null) {
            for (float[] star : stars) {
                if (star.length > 3 && star[3] == 1) tierOne = Math.max(tierOne, (float) Math.hypot(star[0] - engine[0], star[1] - engine[1]));
            }
            if (tierOne > 0) StarPadding.disc(engine[0], engine[1], 2f * tierOne, engine[2], samples);
        }
        java.util.List<Vector3f> out = new java.util.ArrayList<>();
        for (float[] at : samples) {
            float x = (at[0] - sq[0]) / sq[2];
            float y = -(at[1] - sq[1]) / sq[2];
            if (x * x + y * y >= 1f) continue;
            out.add(ConstellationGeometry.point(x, y).normalize());
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
     *       them gets the patch at full strength, with no rim fade: the crossfade happens over open sky
     *       only. Every other block draws as normal.</li>
     * </ol>
     *
     * @param view the level's camera-rotation matrix ({@code RenderLevelStageEvent#getModelViewMatrix})
     */
    static void drawPatch(Matrix4f view, Matrix4f projection, float alpha, ConstellationEdge edge,
                          net.minecraft.world.phys.Vec3 cam, float partialTick) {
        if (!hasPicture() || alpha <= 0f) {
            ConstellationClouds.frameDone();
            return;
        }
        boolean clouds = ConstellationClouds.markedThisFrame();
        java.util.Map<Float, java.util.List<ConstellationOcclusion.Hidden>> fading =
            fadeSteps(ConstellationBlockFade.levels());
        boolean blocks = !fading.isEmpty() && games.brennan.dungeontrain.client.skybox.SkyboxStencil.isAvailable()
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
            GL11.glDepthRange(1.0, 1.0);

            // 1. The real sky: depth still at the far plane.
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            drawDisc(view, alpha, edge.soft());

            // 2. Clouds are sky to the patch: wherever they stamped their bit, the same rim.
            if (clouds) {
                GL11.glEnable(GL11.GL_STENCIL_TEST);
                RenderSystem.stencilMask(0x00);
                RenderSystem.stencilFunc(GL11.GL_EQUAL, ConstellationClouds.BIT, ConstellationClouds.BIT);
                RenderSystem.depthFunc(GL11.GL_ALWAYS);
                drawDisc(view, alpha, edge.soft());
            }

            // 3. Blocks in a star's way: hidden whole, the patch solid over them (the rim's fade belongs
            //    to open sky only). A block crossing over fades by time ({@link ConstellationBlockFade}):
            //    each fade step is stamped and drawn on its own, at its own strength.
            if (blocks) {
                for (java.util.Map.Entry<Float, java.util.List<ConstellationOcclusion.Hidden>> step : fading.entrySet()) {
                    GL11.glDepthRange(0.0, 1.0);
                    RenderSystem.depthMask(false);
                    markHidden(view, step.getValue(), cam, partialTick);
                    GL11.glDepthRange(1.0, 1.0);
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    RenderSystem.stencilMask(0x00);
                    RenderSystem.stencilFunc(GL11.GL_EQUAL, HIDE_BIT, HIDE_BIT);
                    RenderSystem.depthFunc(GL11.GL_GREATER);
                    float a = alpha * step.getKey();
                    // The picture first, clearing the bit where it lands; then night over every hidden
                    // pixel left, the whole screen over, so a block at any angle (underfoot too) is
                    // covered whole, with nothing laid down twice.
                    RenderSystem.stencilMask(HIDE_BIT);
                    RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_ZERO);
                    drawPicture(view, a);
                    RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    RenderSystem.stencilMask(0x00);
                    fillScreen(a);
                }
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

    /** How finely a crossing block's fade is drawn: this many strengths, each its own stamp and draw. */
    static final int FADE_STEPS = 12;

    /**
     * The fading blocks grouped by strength, rounded to one of {@link #FADE_STEPS}: each group is one
     * stamp-and-draw. Whole ones together under 1.
     */
    static java.util.Map<Float, java.util.List<ConstellationOcclusion.Hidden>> fadeSteps(
            java.util.Map<ConstellationOcclusion.Hidden, Float> levels) {
        java.util.Map<Float, java.util.List<ConstellationOcclusion.Hidden>> out = new java.util.TreeMap<>();
        for (java.util.Map.Entry<ConstellationOcclusion.Hidden, Float> e : levels.entrySet()) {
            float v = Math.round(Math.min(1f, e.getValue()) * FADE_STEPS) / (float) FADE_STEPS;
            if (v <= 0f) continue;
            out.computeIfAbsent(v, k -> new java.util.ArrayList<>()).add(e.getKey());
        }
        return out;
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
        drawDisc(view, 1f, true);
    }

    /**
     * One disc: solid out to the current {@link #core}, then the rim ({@link ConstellationEdge#alphaAt}).
     * A night-coloured disc goes down first, and the menu's picture over it as far as the picture
     * reaches: once the core grows past the picture's edge, the patch carries on as plain night sky
     * instead of smearing the picture's last row of pixels outward.
     */
    private static void drawDisc(Matrix4f view, float alpha, boolean soft) {
        drawDisc(view, alpha, soft, core);
    }

    /** {@link #drawDisc(Matrix4f, float, boolean)} with the solid core out to {@code c} radii. */
    private static void drawDisc(Matrix4f view, float alpha, boolean soft, float c) {
        if (soft) {
            drawCrumble(view, alpha, c);
            return;
        }
        float[] sq = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        float gw = texture.guiWidth(), gh = texture.guiHeight();
        float[] radii = ringRadii(soft, c);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // Night past the picture's edge only: under the picture too, a part-faded disc would lay its
        // alpha down twice there and show the picture's circle darker than the rest.
        float limit = Math.min(Math.min(sq[0], gw - sq[0]), Math.min(sq[1], gh - sq[1])) / sq[2];
        float[] fillRadii = beyondRadii(radii, limit);
        if (fillRadii.length >= 2) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder fill = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            rings(fillRadii, soft, c, alpha, (x, y, a) -> {
                Vector3f p = ConstellationGeometry.point(x, y);
                fill.addVertex(view, p.x, p.y, p.z).setColor(SkyDomeDraw.argb(a, PerksScreen.SKY_BG & 0xFFFFFF));
            });
            BufferUploader.drawWithShader(fill.buildOrThrow());
        }

        // the picture, as far as it goes
        float[] texRadii = clipRadii(radii, limit);
        if (texRadii.length >= 2) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, texture.location());
            BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            rings(texRadii, soft, c, alpha, (x, y, a) -> vertex(bb, view, x, y, sq, gw, gh, a));
            BufferUploader.drawWithShader(bb.buildOrThrow());
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /**
     * The soft patch as crumbling blocks ({@link ConstellationCrumble}): every night cell one square,
     * the picture where the picture reaches, plain night past it. Cells never overlap, so a patch
     * fading in or out fades evenly, with no ring showing through.
     */
    private static void drawCrumble(Matrix4f view, float alpha, float core) {
        float[] sq = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        float gw = texture.guiWidth(), gh = texture.guiHeight();
        float cell = ConstellationCrumble.CELL;
        int n = (int) Math.ceil(ConstellationCrumble.extent(core) / cell);
        double seconds = ConstellationCrumble.clock();
        ConstellationCrumble.Arm[] arms = ConstellationCrumble.arms(core, seconds);
        int night = SkyDomeDraw.argb(alpha, PerksScreen.SKY_BG & 0xFFFFFF);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        // Which cells are night, and which of those lie on the picture. One builder at a time: they
        // share the Tesselator's buffer.
        java.util.List<float[]> onPic = new java.util.ArrayList<>(), offPic = new java.util.ArrayList<>();
        for (int i = -n; i < n; i++) {
            for (int j = -n; j < n; j++) {
                if (!ConstellationCrumble.on(i, j, core, arms, seconds)) continue;
                float x0 = i * cell, y0 = j * cell;
                (onPicture(x0, y0, sq, gw, gh) && onPicture(x0 + cell, y0 + cell, sq, gw, gh) ? onPic : offPic)
                    .add(new float[] {x0, y0});
            }
        }
        if (!offPic.isEmpty()) {
            BufferBuilder fill = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (float[] c : offPic) {
                for (float[] p : new float[][] {{c[0], c[1]}, {c[0] + cell, c[1]}, {c[0] + cell, c[1] + cell}, {c[0], c[1] + cell}}) {
                    Vector3f v = ConstellationGeometry.point(p[0], p[1]);
                    fill.addVertex(view, v.x, v.y, v.z).setColor(night);
                }
            }
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(fill.buildOrThrow());
        }
        if (!onPic.isEmpty()) {
            BufferBuilder pic = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (float[] c : onPic) {
                vertex(pic, view, c[0], c[1], sq, gw, gh, alpha);
                vertex(pic, view, c[0] + cell, c[1], sq, gw, gh, alpha);
                vertex(pic, view, c[0] + cell, c[1] + cell, sq, gw, gh, alpha);
                vertex(pic, view, c[0], c[1] + cell, sq, gw, gh, alpha);
            }
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, texture.location());
            BufferUploader.drawWithShader(pic.buildOrThrow());
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** The menu's picture alone, solid, as far as it reaches; no night past it. */
    private static void drawPicture(Matrix4f view, float alpha) {
        float[] sq = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        float gw = texture.guiWidth(), gh = texture.guiHeight();
        float limit = Math.min(Math.min(sq[0], gw - sq[0]), Math.min(sq[1], gh - sq[1])) / sq[2];
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture.location());
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        rings(new float[] {0f, limit}, false, limit + 1f, alpha, (x, y, a) -> vertex(bb, view, x, y, sq, gw, gh, a));
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Night over the whole screen, at the far plane (the caller's stencil and depth tests pick where). */
    private static void fillScreen(float alpha) {
        Matrix4f keepProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(new Matrix4f(), com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        int c = SkyDomeDraw.argb(alpha, PerksScreen.SKY_BG & 0xFFFFFF);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(-1f, -1f, 0f).setColor(c);
        bb.addVertex(1f, -1f, 0f).setColor(c);
        bb.addVertex(1f, 1f, 0f).setColor(c);
        bb.addVertex(-1f, 1f, 0f).setColor(c);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setProjectionMatrix(keepProjection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
    }

    /** Is the patch point {@code (x, y)} (radii) on the picture's canvas? */
    private static boolean onPicture(float x, float y, float[] sq, float gw, float gh) {
        float u = sq[0] + x * sq[2], v = sq[1] - y * sq[2];
        return u >= 0 && u <= gw && v >= 0 && v <= gh;
    }

    private interface Emit {
        void at(float x, float y, float alpha);
    }

    /** Triangles for the rings between consecutive {@code radii}, each vertex at its falloff alpha. */
    private static void rings(float[] radii, boolean soft, float core, float alpha, Emit emit) {
        for (int ring = 0; ring < radii.length - 1; ring++) {
            float r0 = radii[ring], r1 = radii[ring + 1];
            float a0 = alpha * ConstellationEdge.alphaAt(r0, soft, core);
            float a1 = alpha * ConstellationEdge.alphaAt(Math.min(r1, ConstellationEdge.rim(soft, core) - 1.0e-4f), soft, core);
            for (int s = 0; s < SEGMENTS; s++) {
                double t0 = 2 * Math.PI * s / SEGMENTS, t1 = 2 * Math.PI * (s + 1) / SEGMENTS;
                float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0);
                float c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
                if (r0 == 0f) {
                    emit.at(0f, 0f, a0);
                    emit.at(r1 * c0, r1 * s0, a1);
                    emit.at(r1 * c1, r1 * s1, a1);
                } else {
                    emit.at(r0 * c0, r0 * s0, a0);
                    emit.at(r1 * c0, r1 * s0, a1);
                    emit.at(r1 * c1, r1 * s1, a1);
                    emit.at(r0 * c0, r0 * s0, a0);
                    emit.at(r1 * c1, r1 * s1, a1);
                    emit.at(r0 * c1, r0 * s1, a0);
                }
            }
        }
    }

    /** Centre, the core's edge, rings across the feather to the rim. Hard: centre and rim only. */
    static float[] ringRadii(boolean soft, float core) {
        if (!soft) return new float[] {0f, ConstellationEdge.rim(false, core)};
        float[] r = new float[FEATHER_RINGS + 2];
        r[0] = 0f;
        for (int i = 0; i <= FEATHER_RINGS; i++) r[i + 1] = core + ConstellationEdge.FEATHER_WIDTH * i / FEATHER_RINGS;
        return r;
    }

    /** The part of {@code radii} past {@code limit}, starting at the limit itself; empty when none is. */
    static float[] beyondRadii(float[] radii, float limit) {
        java.util.List<Float> out = new java.util.ArrayList<>();
        out.add(limit);
        for (float r : radii) if (r > limit) out.add(r);
        if (out.size() < 2) return new float[0];
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** {@code radii} cut off at {@code limit}, with the limit itself as the last ring. */
    static float[] clipRadii(float[] radii, float limit) {
        java.util.List<Float> out = new java.util.ArrayList<>();
        for (float r : radii) {
            if (r >= limit) {
                out.add(limit);
                break;
            }
            out.add(r);
        }
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    // ---- the solid core, grown to cover the hidden blocks ----------------------------------------------

    /** How far out the patch is solid, in radii; eased toward {@link #coreTarget}. */
    private static float core = ConstellationEdge.FEATHER_START;
    /** How fast the core follows its target, per second (exponential). */
    private static final float CORE_RATE = 6f;

    /**
     * Ease the core toward covering the hidden blocks: at least {@link ConstellationEdge#FEATHER_START},
     * else just past the farthest hidden block ({@code reach}), so every hidden block sits inside the
     * solid part before the rim starts to fade.
     */
    static void easeCore(float reach, float dtSeconds) {
        float target = coreTarget(reach);
        float k = 1f - (float) Math.exp(-CORE_RATE * Math.max(0f, dtSeconds));
        core += (target - core) * k;
    }

    static float coreTarget(float reach) {
        return Math.max(ConstellationEdge.FEATHER_START, Math.min(reach, ConstellationOcclusion.MAX_REACH));
    }

    /** Back to the smallest core: the patch has closed. */
    static void resetCore() {
        core = ConstellationEdge.FEATHER_START;
    }

    private static void vertex(BufferBuilder bb, Matrix4f view, float x, float y, float[] sq, float gw, float gh, float a) {
        Vector3f p = ConstellationGeometry.point(x, y);
        float u = (sq[0] + x * sq[2]) / gw;
        float v = (sq[1] - y * sq[2]) / gh;
        bb.addVertex(view, p.x, p.y, p.z).setUv(u, v).setColor(SkyDomeDraw.argb(a, 0xFFFFFF));
    }
}
