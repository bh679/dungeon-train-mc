package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Optional;

/**
 * A nudge toward the hidden "Selfie with an Unstoppable Force And Immovable Object" challenge, which
 * wants a Warden and the Wither in one selfie. A photo of both that isn't a selfie, or a selfie that
 * caught only one of them, gets a chat line saying what's missing. Nothing for a selfie with neither,
 * so the challenge stays hidden until a player is already close.
 *
 * <p>Exposure counts a mob as in the shot only when its eyes are in view, it's near enough for its
 * size, and nothing solid is between the camera and its eyes ({@code EntitiesInFrame}) — the
 * missing-boss lines spell that out.</p>
 */
public final class SelfieChallengeHints {

    static final ResourceLocation ADVANCEMENT =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "enchiridion/unstoppable_force");

    static final String NOT_SELFIE = "chat.dungeontrain.selfie_hint.not_selfie";
    static final String MISSING_WITHER = "chat.dungeontrain.selfie_hint.missing_wither";
    static final String MISSING_WARDEN = "chat.dungeontrain.selfie_hint.missing_warden";

    private SelfieChallengeHints() {}

    /** The hint a photo earns, if any. */
    static Optional<String> hintKey(boolean selfie, boolean warden, boolean wither) {
        if (!selfie) return warden && wither ? Optional.of(NOT_SELFIE) : Optional.empty();
        if (warden && !wither) return Optional.of(MISSING_WITHER);
        if (wither && !warden) return Optional.of(MISSING_WARDEN);
        return Optional.empty();
    }

    /** Tell the photographer what the shot is missing, until they've earned the challenge. */
    static void send(ServerPlayer player, boolean selfie, List<LivingEntity> inFrame) {
        boolean warden = false, wither = false;
        for (LivingEntity e : inFrame) {
            if (e == player) continue;
            if (e.getType() == EntityType.WARDEN) warden = true;
            if (e.getType() == EntityType.WITHER) wither = true;
        }
        Optional<String> key = hintKey(selfie, warden, wither);
        if (key.isEmpty() || isEarned(player)) return;
        Component line = NOT_SELFIE.equals(key.get())
                ? Component.translatable(key.get(), Component.keybind("key.togglePerspective"))
                : Component.translatable(key.get());
        player.sendSystemMessage(line.copy().withStyle(ChatFormatting.GRAY));
    }

    private static boolean isEarned(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        AdvancementHolder holder = server.getAdvancements().get(ADVANCEMENT);
        return holder == null || player.getAdvancements().getOrStartProgress(holder).isDone();
    }
}
