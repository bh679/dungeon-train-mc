package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.echo.EchoPersonality;
import games.brennan.dungeontrain.player.PlayerMobAppearance;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.Util;
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
    /** One weapon-swing arc lasts this long (seconds) — short so it reads as a real strike, not slow-mo. */
    private static final float SWING_SECONDS = 0.4f;
    /** Full crouches per second during a befriended mob's eye-contact bob. */
    private static final float CROUCH_HZ = 3.0f;
    /** Minimum crouches a befriended mob holds eye contact for before looking away. */
    private static final int MIN_CROUCHES = 4;

    private PlayerMobAppearance current;
    private PlayerMobEntity entity;
    /** Whether the cached entity currently holds its shield up (start/stop transition memo). */
    private boolean blocking;
    /** Wall-clock millis when the cached entity was built; animation time is measured from here. */
    private long animStartMillis;

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
        render(g, appearance, side, centerX, feetY, partialTick, SIZE);
    }

    /**
     * As {@link #render(GuiGraphics, PlayerMobAppearance, int, int, int, float)}, at {@code size}
     * GUI pixels per block — the echo page draws its portrait larger than DEEDS does.
     */
    public void render(GuiGraphics g, PlayerMobAppearance appearance, int side,
                       int centerX, int feetY, float partialTick, int size) {
        if (appearance == null) return;
        PlayerMobEntity mob = obtain(appearance);
        if (mob == null) return;

        // Wall-clock seconds since the portrait appeared — independent of the server
        // tick rate, so the performance plays at true speed even when the world is
        // mid-lag (e.g. the post-death spawn storm), unlike a getGameTime() clock.
        float t = (Util.getMillis() - animStartMillis) / 1000.0f;
        if (side == 1) animateFriend(mob, t);
        else animateKilled(mob, t);

        // Feet pinned at feetY; the figure (~SIZE*bbHeight tall) rises above it.
        int renderY = feetY - Math.round(size * mob.getBbHeight() / 2.0f);
        drawEntity(g, mob, centerX, renderY, size);
    }

    /**
     * Draw an echo's portrait acting out its personality ({@code kind}), at a tempo set by its
     * reaction speed (0–10): half speed at 0, true speed at 5, double at 10.
     */
    public void renderEcho(GuiGraphics g, PlayerMobAppearance appearance, EchoPersonality.Kind kind,
                           int reactionSpeed, int centerX, int feetY, int size) {
        if (appearance == null) return;
        PlayerMobEntity mob = obtain(appearance);
        if (mob == null) return;
        float tempo = (float) Math.pow(2.0, (EchoPersonality.clamp(reactionSpeed) - 5) / 5.0);
        float t = tempo * (Util.getMillis() - animStartMillis) / 1000.0f;
        switch (kind) {
            case GUARDIAN -> animateGuardian(mob, t);
            case GENTLE_SOUL -> animateFriend(mob, t);
            case SENTINEL -> animateSentinel(mob, t);
            case SKEPTIC -> animateSkeptic(mob, t);
            case BRUTE -> animateKilled(mob, t);
            case RECLUSE -> animateRecluse(mob, t);
        }
        int renderY = feetY - Math.round(size * mob.getBbHeight() / 2.0f);
        drawEntity(g, mob, centerX, renderY, size);
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
        this.animStartMillis = Util.getMillis();
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
     * Friendly idle: the mob wanders its gaze (looking around) with a gentle body
     * sway, then turns to make eye contact and crouch-bobs at you — the classic
     * friendly sneak-spam. It crouches ONLY while looking right at you, and holds
     * that eye contact long enough for at least {@link #MIN_CROUCHES} crouches
     * before glancing away again.
     */
    private void animateFriend(PlayerMobEntity mob, float t) {
        if (blocking) { mob.lowerShield(); blocking = false; }
        clearSwing(mob);

        float contact = MIN_CROUCHES / CROUCH_HZ + 0.3f;      // eye-contact seconds (≥ MIN_CROUCHES bobs)
        float lookAround = 2.6f;                              // seconds wandering between contacts
        float local = t % (lookAround + contact);
        float bodyYaw = FACE_YOU + 6.0f * Mth.sin(t * 0.9f);

        if (local >= lookAround) {
            // Eye contact: stare right at you and crouch-bob.
            setFacing(mob, bodyYaw, FACE_YOU, 0.0f);
            float c = local - lookAround;                     // seconds into the contact
            mob.setCrouching((((int) (c * CROUCH_HZ * 2.0f)) & 1) == 0);
        } else {
            // Looking around — standing, no crouch.
            setFacing(mob, bodyYaw, FACE_YOU + 45.0f * Mth.sin(t * 2.0f), 10.0f * Mth.sin(t * 1.6f));
            mob.setCrouching(false);
        }
    }

    /**
     * Menacing idle: body squared to you, head mostly staring at you (only slight
     * drift — "look around much less"). Cycles hostile beats: stare, swing the
     * weapon at you, hold the shield up, or crouch-then-strike.
     */
    private void animateKilled(PlayerMobEntity mob, float t) {
        setFacing(mob, FACE_YOU, FACE_YOU + 7.0f * Mth.sin(t * 0.8f), 3.0f * Mth.sin(t * 0.6f));

        float cycle = 2.6f;                                   // seconds per hostile beat
        int beat = ((int) (t / cycle)) & 3;                   // 0..3
        float local = t % cycle;                              // seconds into the beat

        boolean wantBlock = false;
        boolean wantCrouch = false;
        float swing = 0.0f;
        switch (beat) {
            case 1 -> swing = strike(local, 0.15f);            // a quick swing at you
            case 2 -> wantBlock = local < 1.9f;                // raise & hold the shield
            case 3 -> { wantCrouch = local < 0.6f; swing = strike(local, 0.7f); } // crouch, then strike
            default -> { /* just stare */ }
        }

        mob.setCrouching(wantCrouch);
        if (wantBlock && !blocking) { mob.raiseShieldIfHeld(); blocking = true; }
        else if (!wantBlock && blocking) { mob.lowerShield(); blocking = false; }
        if (swing > 0.0f) setSwing(mob, swing); else clearSwing(mob);
    }

    /**
     * Guardian: the friendly crouch-bob, broken now and then by a swing past you — at whatever
     * would hurt a friend.
     */
    private void animateGuardian(PlayerMobEntity mob, float t) {
        float cycle = 6.0f;
        float local = t % cycle;
        if (local > 4.6f) {
            mob.setCrouching(false);
            if (blocking) { mob.lowerShield(); blocking = false; }
            setFacing(mob, FACE_YOU + 20.0f, FACE_YOU + 35.0f, 0.0f);
            float swing = strike(local - 4.6f, 0.2f);
            if (swing > 0.0f) setSwing(mob, swing); else clearSwing(mob);
            return;
        }
        animateFriend(mob, t);
    }

    /** Sentinel: squared up and staring, shield raised for half of each beat, barely moving. */
    private void animateSentinel(PlayerMobEntity mob, float t) {
        clearSwing(mob);
        mob.setCrouching(false);
        setFacing(mob, FACE_YOU, FACE_YOU + 12.0f * Mth.sin(t * 0.5f), 4.0f);
        boolean wantBlock = (t % 4.0f) < 2.0f;
        if (wantBlock && !blocking) { mob.raiseShieldIfHeld(); blocking = true; }
        else if (!wantBlock && blocking) { mob.lowerShield(); blocking = false; }
    }

    /** Skeptic: mostly looks away, with brief wary glances at you behind a raised shield. */
    private void animateSkeptic(PlayerMobEntity mob, float t) {
        clearSwing(mob);
        mob.setCrouching(false);
        float cycle = 3.5f;
        float local = t % cycle;
        boolean glance = local > 2.6f;
        float away = (((int) (t / cycle)) & 1) == 0 ? 55.0f : -55.0f;
        setFacing(mob, FACE_YOU + away * 0.3f, glance ? FACE_YOU : FACE_YOU + away, glance ? 0.0f : 8.0f);
        if (glance && !blocking) { mob.raiseShieldIfHeld(); blocking = true; }
        else if (!glance && blocking) { mob.lowerShield(); blocking = false; }
    }

    /** Recluse: turned away, head down, often crouched, with a quick nervous glance now and then. */
    private void animateRecluse(PlayerMobEntity mob, float t) {
        clearSwing(mob);
        if (blocking) { mob.lowerShield(); blocking = false; }
        float cycle = 4.0f;
        float local = t % cycle;
        boolean glance = local > 3.3f;
        float body = FACE_YOU + 35.0f;
        setFacing(mob, body, glance ? FACE_YOU : body + 15.0f * Mth.sin(t * 0.7f), glance ? 5.0f : 28.0f);
        mob.setCrouching(!glance && local > 1.0f);
    }

    /** A sharp 0→1 weapon-swing ramp lasting {@link #SWING_SECONDS}s from {@code start} (seconds into the beat). */
    private static float strike(float local, float start) {
        float p = (local - start) / SWING_SECONDS;
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

    private void drawEntity(GuiGraphics g, PlayerMobEntity mob, int x, int y, int size) {
        float bb = mob.getBbHeight();
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 50.0);
        pose.scale(size, size, -size);
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
