package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import io.github.mortuusars.exposure.neoforge.api.event.FrameAddedEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;

/**
 * Nudges toward the hidden "Selfie with an Unstoppable Force And Immovable Object" challenge, which
 * wants a Warden and the Wither in one selfie. A photo of both that isn't a selfie says so; a selfie
 * where a Warden or Wither was in the picture but didn't count says why: too far away, or its eyes
 * out of frame. Silent once the challenge is earned.
 *
 * <p>The reasons mirror Exposure's own test ({@code EntitiesInFrame.get}): a mob counts when its eyes
 * are inside the camera's view, it's near enough for its size, and nothing solid is in the way. The
 * camera's point of view and field of view are recomputed from the camera the same way it took the
 * shot ({@link CameraView}).</p>
 */
public final class SelfieChallengeHints {

    static final ResourceLocation ADVANCEMENT =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "enchiridion/unstoppable_force");

    static final String NOT_SELFIE = "chat.dungeontrain.selfie_hint.not_selfie";
    private static final String KEY_PREFIX = "chat.dungeontrain.selfie_hint.";

    /** Why a mob in the picture didn't count. */
    enum Diagnosis { NONE, TOO_FAR, EYES }

    private SelfieChallengeHints() {}

    /** The not-a-selfie hint: both bosses counted, but the camera faced away from the photographer. */
    static Optional<String> hintKey(boolean selfie, boolean warden, boolean wither) {
        return !selfie && warden && wither ? Optional.of(NOT_SELFIE) : Optional.empty();
    }

    /** The reason, in Exposure's own order: in view at all, then distance, then the eyes. */
    static Diagnosis diagnose(boolean bodyInFrame, boolean tooFar, boolean eyesInFrame) {
        if (!bodyInFrame) return Diagnosis.NONE;
        if (tooFar) return Diagnosis.TOO_FAR;
        if (!eyesInFrame) return Diagnosis.EYES;
        return Diagnosis.NONE;
    }

    /** {@code warden}/{@code wither} + the diagnosis, or empty when there is nothing to say. */
    static Optional<String> diagnosisKey(String mob, Diagnosis diagnosis) {
        return switch (diagnosis) {
            case TOO_FAR -> Optional.of(KEY_PREFIX + mob + "_too_far");
            case EYES -> Optional.of(KEY_PREFIX + mob + "_eyes");
            case NONE -> Optional.empty();
        };
    }

    /** Tell the photographer what the shot is missing, until they've earned the challenge. */
    static void send(ServerPlayer player, FrameAddedEvent event, boolean selfie) {
        List<LivingEntity> inFrame = event.getEntitiesInFrame();
        boolean warden = false, wither = false;
        for (LivingEntity e : inFrame) {
            if (e.getType() == EntityType.WARDEN) warden = true;
            if (e.getType() == EntityType.WITHER) wither = true;
        }
        Optional<String> notSelfie = hintKey(selfie, warden, wither);
        if (notSelfie.isEmpty() && !selfie) return;
        if (isEarned(player, ADVANCEMENT)) return;
        if (notSelfie.isPresent()) {
            tell(player, Component.translatable(notSelfie.get(), Component.keybind("key.togglePerspective")));
            return;
        }
        CameraView view = CameraView.of(player, event).orElse(null);
        if (view == null) return;
        for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(player.blockPosition()).inflate(CameraView.SEARCH_RADIUS), LivingEntity::isAlive)) {
            String mob = e.getType() == EntityType.WARDEN ? "warden" : e.getType() == EntityType.WITHER ? "wither" : null;
            if (mob == null || inFrame.contains(e)) continue;
            diagnosisKey(mob, diagnose(view.bodyInFrame(e), view.tooFar(e), view.eyesInFrame(e)))
                    .ifPresent(key -> tell(player, Component.translatable(key)));
        }
    }

    /** A gray hint line, as the camera hints all say it. */
    static void tell(ServerPlayer player, Component line) {
        player.sendSystemMessage(line.copy().withStyle(ChatFormatting.GRAY));
    }

    /** True once {@code advancement} is earned (or missing): the camera hints fall silent. */
    static boolean isEarned(ServerPlayer player, ResourceLocation advancement) {
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        AdvancementHolder holder = server.getAdvancements().get(advancement);
        return holder == null || player.getAdvancements().getOrStartProgress(holder).isDone();
    }
}
