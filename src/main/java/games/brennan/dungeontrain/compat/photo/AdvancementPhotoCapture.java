package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.net.EarnedPhotoPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Links an Enchiridion camera advancement to the photo that earned it.
 *
 * <p>Every photo advancement is granted synchronously inside the trigger call that a photo causes —
 * the shot itself, a PlayerMob's print being picked up, a found photo being opened or tributed. Those
 * callers run their triggers {@link #during} the photo's frame; the advancement-earn handler then asks
 * {@link #onEarn} whether a frame is current and, if so, tells the earning player's client which
 * exposure it was. The client keeps its own copy (see {@code client.EarnedPhotos}), so the photo
 * follows the advancement into every later world.</p>
 */
public final class AdvancementPhotoCapture {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AdvancementPhotoCapture() {}

    /** Run {@code triggers} with {@code frame}'s exposure as the current photo. */
    public static void during(Frame frame, Runnable triggers) {
        String exposureId = exposureId(frame);
        if (exposureId == null) {
            triggers.run();
            return;
        }
        String previous = CURRENT.get();
        CURRENT.set(exposureId);
        try {
            triggers.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** {@link #during} the frame a photograph stack carries (none for anything else). */
    public static void during(ItemStack photograph, Runnable triggers) {
        during(photograph == null ? null : photograph.get(Exposure.DataComponents.PHOTOGRAPH_FRAME), triggers);
    }

    /** Called for every advancement earn: sends the earning photo when one is current. */
    public static void onEarn(ServerPlayer player, ResourceLocation advancement, boolean replaying) {
        String exposureId = CURRENT.get();
        if (shouldSend(advancement, exposureId, replaying)) {
            LOGGER.info("[DungeonTrain] {} earned {} with photo {}", player.getName().getString(), advancement, exposureId);
            DungeonTrainNet.sendTo(player, new EarnedPhotoPacket(advancement, exposureId));
        }
    }

    /** A genuine earn of a camera advancement on The Enchiridion, with a photo behind it. */
    static boolean shouldSend(ResourceLocation advancement, String exposureId, boolean replaying) {
        return !replaying
                && exposureId != null && !exposureId.isBlank()
                && advancement != null
                && DungeonTrain.MOD_ID.equals(advancement.getNamespace())
                && advancement.getPath().startsWith(EnchiridionAdvancements.PATH_PREFIX);
    }

    private static String exposureId(Frame frame) {
        if (frame == null || !frame.identifier().isId()) return null;
        return frame.identifier().id();
    }
}
