package games.brennan.dungeontrain.client.live.deathpages;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The crowd page's first place, drawn as the whole player in their skin rather than a face: a
 * throwaway client-side {@link RemotePlayer} (never added to the level, never ticked) rendered with
 * vanilla's inventory-entity transform minus the scissor, like the deeds page portrait. It faces the
 * viewer and turns its head slowly. A website viewer whose Discord is linked to a game account wears
 * that account's skin; any other website, Twitch or Discord viewer stands in the default one.
 *
 * <p>Per-screen state: call {@link #clear()} from the owning screen's {@code removed()} so the cached
 * entity cannot pin a stale level.</p>
 */
public final class WatcherFigure {

    /** Full-bright packed light (sky+block 15). */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Body yaw that faces the viewer (the vanilla GUI entity convention). */
    private static final float FACE_YOU = 180.0f;
    private static final byte ALL_SKIN_LAYERS = 0x7F;
    private static final String NAMESPACE = "dungeontrain:crowd-figure:";

    @Nullable private Puppet puppet;
    @Nullable private String key;
    private long startMs;

    /**
     * Draw the figure with its feet at {@code feetY}, {@code height} px tall. False (nothing drawn)
     * without a level, in which case the caller shows the face instead.
     */
    public boolean draw(GuiGraphics g, @Nullable String uuid, @Nullable String name, int centerX, int feetY, int height) {
        Puppet p = obtain(uuid, name);
        if (p == null) return false;
        float t = (Util.getMillis() - startMs) / 1000.0f;
        float headYaw = FACE_YOU + Mth.sin(t * 0.6f) * 25.0f;
        p.yBodyRot = FACE_YOU; p.yBodyRotO = FACE_YOU;
        p.setYRot(FACE_YOU); p.yRotO = FACE_YOU;
        p.yHeadRot = headYaw; p.yHeadRotO = headYaw;
        p.setXRot(-5.0f); p.xRotO = -5.0f;

        float size = height / p.getBbHeight();
        int renderY = feetY - Math.round(height / 2.0f);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(centerX, renderY, 50.0);
        pose.scale(size, size, -size);
        pose.translate(0.0, p.getBbHeight() / 2.0, 0.0);
        pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));
        Lighting.setupForEntityInInventory();
        EntityRenderDispatcher erd = Minecraft.getInstance().getEntityRenderDispatcher();
        erd.setRenderShadow(false);
        MultiBufferSource.BufferSource buf = g.bufferSource();
        RenderSystem.runAsFancy(() -> erd.render(p, 0.0, 0.0, 0.0, 0.0f, 1.0f, pose, buf, FULL_BRIGHT));
        buf.endBatch();
        erd.setRenderShadow(true);
        Lighting.setupFor3DItems();
        pose.popPose();
        return true;
    }

    /** Drop the cached entity (and its level). */
    public void clear() {
        puppet = null;
        key = null;
    }

    @Nullable
    private Puppet obtain(@Nullable String uuid, @Nullable String name) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        String k = uuid + "|" + name;
        if (puppet != null && Objects.equals(key, k) && puppet.level() == level) return puppet;
        UUID source = WatcherFaces.parse(uuid);
        String shown = name == null || name.isBlank() ? "viewer" : name;
        puppet = new Puppet(level, source, shown.length() > 16 ? shown.substring(0, 16) : shown);
        key = k;
        startMs = Util.getMillis();
        return puppet;
    }

    /** The stand-in player: a derived id so it never collides with a real entity, the source's skin. */
    private static final class Puppet extends RemotePlayer {
        @Nullable private final UUID source;

        Puppet(ClientLevel level, @Nullable UUID source, String name) {
            super(level, new GameProfile(UUID.nameUUIDFromBytes((NAMESPACE + source + name).getBytes(StandardCharsets.UTF_8)), name));
            this.source = source;
            this.noPhysics = true;
            this.setNoGravity(true);
            this.setSilent(true);
            this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, ALL_SKIN_LAYERS);
        }

        @Override
        public PlayerSkin getSkin() {
            return WatcherFaces.skinOf(source);
        }

        @Override
        public boolean shouldShowName() {
            return false;
        }
    }
}
