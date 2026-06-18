package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.player.PlayerMobAppearance;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;

/**
 * Renders an animated 3D portrait of one PlayerMob — befriended or killed this
 * run — on the death-screen DEEDS page.
 *
 * <p>Reconstructs a throwaway client-side {@link PlayerMobEntity} from the
 * {@link PlayerMobAppearance} carried in the death packet (skin + equipment),
 * then draws it with vanilla's {@code InventoryScreen.renderEntityInInventory}
 * transform <em>minus the scissor</em> — so the full body shows uncropped, feet
 * pinned at a chosen Y and the head free to rise above. Skin resolution (echo
 * profile / URL / vanilla index) and equipment layers come from playermob's own
 * registered renderer.</p>
 *
 * <p>Instead of following the mouse, the mob plays a short idle performance that
 * differs by subject: a befriended mob looks around, glances at you, and
 * crouch-bobs; a killed mob mostly stares and cycles menacing beats (swing /
 * shield-block / crouch-strike). Pose is driven each frame from the world clock;
 * the entity is never added to a level or ticked.</p>
 *
 * <p>Per-screen state — call {@link #clear()} from the owning screen's
 * {@code removed()} so the cached entity can't pin a stale {@link Level}.</p>
 */
public final class DeathPortraitRenderer {

    /** Full-bright packed light (sky+block 15) — see {@code LightTexture}. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** GUI pixels per block at render scale; sets the portrait's on-screen height. */
    private static final int SIZE = 36;
    /** Body yaw that faces the viewer (matches vanilla GUI entity convention). */
    private static final float FACE_YOU = 180.0f;

    private PlayerMobAppearance current;
    private PlayerMobEntity entity;
    /** Whether the cached entity currently holds its shield up (start/stop transition memo). */
    private boolean blocking;

    /**
     * Whether a portrait can be drawn this frame — a client level exists and the
     * playermob entity type is registered. The DEEDS layout consults this BEFORE
     * reserving space, so a failure falls back to the full-width grid.
     */
    public boolean available() {
        return Minecraft.getInstance().level != null && PlayerMobRegistry.PLAYER_MOB != null;
    }

    /**
     * Draw {@code appearance}'s portrait centred on {@code centerX} with its feet
     * at {@code feetY}; the body extends upward (uncropped). {@code side} 1 =
     * befriended (friendly gestures), 2 = killed (menacing). No-op if the entity
     * can't be built this frame.
     */
    public void render(GuiGraphics g, PlayerMobAppearance appearance, int side,
                       int centerX, int feetY, float partialTick) {
        if (appearance == null) return;
        PlayerMobEntity mob = obtain(appearance);
        if (mob == null) return;

        Level level = Minecraft.getInstance().level;
        float t = (level.getGameTime() + partialTick) / 20.0f; // seconds
        if (side == 1) animateFriend(mob, t);
        else animateKilled(mob, t);

        // Feet pinned at feetY; the figure (~SIZE*bbHeight tall) rises above it.
        int renderY = feetY - Math.round(SIZE * mob.getBbHeight() / 2.0f);
        drawEntity(g, mob, centerX, renderY);
    }

    /** Lazily build / refresh the cached entity (skin + equipment). */
    private PlayerMobEntity obtain(PlayerMobAppearance appearance) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        EntityType<PlayerMobEntity> type = PlayerMobRegistry.PLAYER_MOB;
        if (type == null) return null;
        // Reference-equality cache: the screen reads the same cached packet (hence
        // the same appearance instance) every frame, so this is stable and only
        // rebuilds on a new packet or a level swap. (Record .equals would compare
        // ItemStacks by identity and rebuild every frame.)
        if (entity != null && appearance == current && entity.level() == level) {
            return entity;
        }
        PlayerMobEntity mob = new PlayerMobEntity(type, level);
        mob.setNoAi(true);
        mob.setSkinIndex(appearance.skinIndex());
        mob.setSkinTextureUrl(appearance.skinTextureUrl());
        mob.setSkinSlim(appearance.slim());
        mob.setItemSlot(EquipmentSlot.MAINHAND, appearance.mainHand().copy());
        mob.setItemSlot(EquipmentSlot.OFFHAND, appearance.offHand().copy());
        mob.setItemSlot(EquipmentSlot.HEAD, appearance.head().copy());
        mob.setItemSlot(EquipmentSlot.CHEST, appearance.chest().copy());
        mob.setItemSlot(EquipmentSlot.LEGS, appearance.legs().copy());
        mob.setItemSlot(EquipmentSlot.FEET, appearance.feet().copy());
        this.entity = mob;
        this.current = appearance;
        this.blocking = false;
        return mob;
    }

    /** Drop the cached entity (and its {@link Level} reference). Call from {@code Screen.removed()}. */
    public void clear() {
        entity = null;
        current = null;
        blocking = false;
    }

    // ---- Animation ----

    /**
     * Friendly idle: body squared to you with a gentle sway; the head mostly
     * wanders (looking around), snapping to look right at you for a beat every
     * few seconds; and frequent crouch-bobbing (the classic friendly sneak-spam).
     */
    private void animateFriend(PlayerMobEntity mob, float t) {
        if (blocking) { mob.lowerShield(); blocking = false; }
        clearSwing(mob);

        float bodyYaw = FACE_YOU + 6.0f * Mth.sin(t * 0.5f);
        boolean lookAtYou = (t % 5.0f) < 1.6f;                 // ~1.6s every 5s
        float headYaw = lookAtYou ? FACE_YOU : FACE_YOU + 55.0f * Mth.sin(t * 1.2f);
        float headPitch = lookAtYou ? 0.0f : 12.0f * Mth.sin(t * 0.9f);
        setFacing(mob, bodyYaw, headYaw, headPitch);

        // Crouch-bob: a ~1.5s burst of fast sneak toggles every ~3.5s.
        boolean burst = (t % 3.5f) < 1.5f;
        mob.setCrouching(burst && (((int) (t * 6.0f)) & 1) == 0);
    }

    /**
     * Menacing idle: body squared to you, head mostly staring at you (only slight
     * drift — "look around much less"). Cycles hostile beats: stare, swing the
     * weapon at you, hold the shield up, or crouch-then-strike.
     */
    private void animateKilled(PlayerMobEntity mob, float t) {
        setFacing(mob, FACE_YOU, FACE_YOU + 8.0f * Mth.sin(t * 0.4f), 3.0f * Mth.sin(t * 0.3f));

        float period = 3.6f;
        int beat = ((int) (t / period)) & 3;                  // 0..3
        float phase = (t % period) / period;                  // 0..1 within the beat

        boolean wantBlock = false;
        boolean wantCrouch = false;
        float swing = 0.0f;
        switch (beat) {
            case 1 -> swing = strike(phase, 0.0f);             // swing at you
            case 2 -> wantBlock = phase < 0.75f;               // raise & hold shield
            case 3 -> { wantCrouch = phase < 0.45f; if (!wantCrouch) swing = strike(phase, 0.45f); }
            default -> { /* just stare */ }
        }

        mob.setCrouching(wantCrouch);
        if (wantBlock && !blocking) { mob.raiseShieldIfHeld(); blocking = true; }
        else if (!wantBlock && blocking) { mob.lowerShield(); blocking = false; }
        if (swing > 0.0f) setSwing(mob, swing); else clearSwing(mob);
    }

    /** A 0→1 swing ramp over ~0.45 of the beat, starting at fraction {@code start}. */
    private static float strike(float phase, float start) {
        float p = (phase - start) / 0.45f;
        return (p <= 0.0f || p >= 1.0f) ? 0.0f : p;
    }

    private static void setFacing(PlayerMobEntity mob, float bodyYaw, float headYaw, float headPitch) {
        mob.yBodyRot = bodyYaw; mob.yBodyRotO = bodyYaw;
        mob.setYRot(bodyYaw);   mob.yRotO = bodyYaw;
        mob.yHeadRot = headYaw; mob.yHeadRotO = headYaw;
        mob.setXRot(headPitch); mob.xRotO = headPitch;
    }

    private static void setSwing(PlayerMobEntity mob, float progress) {
        mob.swinging = true;
        mob.swingingArm = InteractionHand.MAIN_HAND;
        mob.attackAnim = progress; mob.oAttackAnim = progress;
    }

    private static void clearSwing(PlayerMobEntity mob) {
        mob.swinging = false;
        mob.attackAnim = 0.0f; mob.oAttackAnim = 0.0f;
    }

    // ---- Render: vanilla renderEntityInInventory transform, minus the scissor ----

    private void drawEntity(GuiGraphics g, PlayerMobEntity mob, int x, int y) {
        float bb = mob.getBbHeight();
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 50.0);
        pose.scale(SIZE, SIZE, -SIZE);
        pose.translate(0.0, bb / 2.0, 0.0);
        pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));
        Lighting.setupForEntityInInventory();
        EntityRenderDispatcher erd = Minecraft.getInstance().getEntityRenderDispatcher();
        erd.setRenderShadow(false);
        MultiBufferSource.BufferSource buf = g.bufferSource();
        RenderSystem.runAsFancy(() -> erd.render(mob, 0.0, 0.0, 0.0, 0.0f, 1.0f, pose, buf, FULL_BRIGHT));
        buf.endBatch();
        erd.setRenderShadow(true);
        Lighting.setupFor3DItems();
        pose.popPose();
    }
}
