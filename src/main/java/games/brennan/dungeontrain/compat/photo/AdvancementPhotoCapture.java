package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.net.EarnedPhotoPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.item.PhotographItem;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
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
    /** The photo behind the triggers now running: its exposure, and the paper it is printed on. */
    private record Photo(String exposureId, ResourceLocation type) {}

    private static final ThreadLocal<Photo> CURRENT = new ThreadLocal<>();

    private AdvancementPhotoCapture() {}

    /** Run {@code triggers} with {@code frame}'s exposure as the current photo — a fresh shot, on regular paper. */
    public static void during(Frame frame, Runnable triggers) {
        during(frame, PhotographType.REGULAR, triggers);
    }

    /**
     * {@link #during} the frame a photograph stack carries (none for anything else), on the paper the
     * stack is printed on — a found photo wears one of DT's worn papers ({@code WornPhotographItem}).
     */
    public static void during(ItemStack photograph, Runnable triggers) {
        if (photograph == null) {
            triggers.run();
            return;
        }
        Frame frame = photograph.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        PhotographType type = photograph.getItem() instanceof PhotographItem item ? item.getType(photograph) : null;
        during(frame, type, triggers);
    }

    private static void during(Frame frame, PhotographType type, Runnable triggers) {
        String exposureId = exposureId(frame);
        if (exposureId == null) {
            triggers.run();
            return;
        }
        Photo previous = CURRENT.get();
        CURRENT.set(new Photo(exposureId, (type == null ? PhotographType.REGULAR : type).id()));
        try {
            triggers.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** Called for every advancement earn: sends the earning photo when one is current. */
    public static void onEarn(ServerPlayer player, ResourceLocation advancement, boolean replaying) {
        Photo photo = CURRENT.get();
        if (photo != null && shouldSend(advancement, photo.exposureId(), replaying)) {
            LOGGER.info("[DungeonTrain] {} earned {} with photo {} ({})", player.getName().getString(), advancement,
                    photo.exposureId(), photo.type());
            DungeonTrainNet.sendTo(player, new EarnedPhotoPacket(advancement, photo.exposureId(), photo.type()));
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
