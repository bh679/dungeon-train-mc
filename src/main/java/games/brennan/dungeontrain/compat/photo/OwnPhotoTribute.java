package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.DisposableCamera;
import games.brennan.dungeontrain.discord.TributePhotoReporter;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.OwnPhotoTributeCostPacket;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Tribute to a player's own fresh print: pay emeralds and the photo is posted to the public passenger
 * log ({@link TributePhotoReporter#postOwn}), its upload is boosted to {@link SharedPhotos#OWN_BOOST_FACTOR}×
 * the views on the relay ({@link SharedPhotos#boostOwnUpload}), and it burns in green flames. The first
 * one in a life costs {@link #BASE_COST}; each one after triples it, and a new life starts over.
 *
 * <p>Offered only when the post can actually happen — a clean run (or any run on a dev build, which
 * posts to the dev channel), and a player who shares ({@link SharedBookGate#canContribute}) — so no
 * one pays for nothing. The client is told the current
 * price ({@link OwnPhotoTributeCostPacket}; 0 = not offered) on login, respawn, every print and every
 * Tribute.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class OwnPhotoTribute {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Emeralds the first own-photo Tribute of a life costs. */
    public static final int BASE_COST = 3;
    /** Each own-photo Tribute this life multiplies the next one's cost by this. */
    public static final int MULTIPLIER = 3;

    private static final int PAID_LINES = 3;

    private OwnPhotoTribute() {}

    /** {@code BASE_COST × MULTIPLIER^tributesThisLife}, capped at {@link Integer#MAX_VALUE}. */
    public static int cost(int tributesThisLife) {
        long cost = BASE_COST;
        for (int i = 0; i < Math.max(0, tributesThisLife) && cost < Integer.MAX_VALUE; i++) {
            cost *= MULTIPLIER;
        }
        return (int) Math.min(cost, Integer.MAX_VALUE);
    }

    /**
     * What this player's next own-photo Tribute costs, or 0 when it isn't offered. Free Play runs are
     * kept out of the public feed on a release build; a dev build offers it anyway, and its post lands
     * in the dev channel ({@link DungeonTrain#manifestWebhookOverride()} is null off {@code main}) —
     * the same rule as the run-ended manifest.
     */
    public static int currentCost(ServerPlayer player) {
        if (RunIntegrity.isCheated(player) && !DungeonTrain.isDevBuild()) return 0;
        if (!SharedBookGate.canContribute(player)) return 0;
        return cost(player.getData(ModDataAttachments.OWN_PHOTO_TRIBUTES_THIS_LIFE.get()));
    }

    /** Tell the client the current price. */
    public static void sync(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, new OwnPhotoTributeCostPacket(currentCost(player)));
    }

    /** True for a fresh, burn-after-viewing print taken by {@code playerName} — not a found photo. */
    public static boolean isOwnPrint(ItemStack stack, String playerName) {
        if (!DisposableCamera.burnsAfterViewing(stack) || SharedPhotos.sharedId(stack) > 0) return false;
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        return frame != null && playerName.equals(frame.photographer().name());
    }

    private static Optional<InteractionHand> heldOwnPrintHand(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        return Stream.of(InteractionHand.values()).filter(hand -> isOwnPrint(player.getItemInHand(hand), name)).findFirst();
    }

    /**
     * The player chose Tribute for their own print in hand: the emeralds are taken, the photo is
     * posted to the passenger log and burns in green flames, and the next one costs triple.
     */
    public static void pay(ServerPlayer player) {
        Optional<InteractionHand> hand = heldOwnPrintHand(player);
        MinecraftServer server = player.getServer();
        int cost = currentCost(player);
        if (hand.isEmpty() || server == null || cost <= 0) return;
        ItemStack held = player.getItemInHand(hand.get());
        Frame frame = held.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (frame == null || !frame.identifier().isId()) return;
        Optional<ExposureData> data = ExposureServer.exposureRepository().load(frame.identifier().id()).getData();
        if (data.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Own photo {} has no image on the server; Tribute not taken.", frame.identifier().id());
            return;
        }
        if (!TributePayment.canPay(player.getInventory(), cost)) {
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.photo_tribute.cannot_afford").withStyle(ChatFormatting.GRAY));
            return;
        }
        // The photo leaves the hand first: any change from a broken emerald block lands in its slot.
        player.setItemInHand(hand.get(), ItemStack.EMPTY);
        TributePayment.pay(player, cost);
        int tributeNumber = player.getData(ModDataAttachments.OWN_PHOTO_TRIBUTES_THIS_LIFE.get()) + 1;
        player.setData(ModDataAttachments.OWN_PHOTO_TRIBUTES_THIS_LIFE.get(), tributeNumber);
        if (!SharedPhotos.boostOwnUpload(player, held)) {
            LOGGER.debug("[DungeonTrain] Own photo {} was never uploaded; posted, but nothing to boost.", frame.identifier().id());
        }
        SharedPhotos.encodeForDiscord(server, data.get(), PhotoPaperTextures.paper(PhotographType.REGULAR), frame.identifier().id(),
                png -> TributePhotoReporter.postOwn(player, tributeNumber, cost, png));
        StartingBookEvents.dropAndBurnApproved(player, held);
        int n = 1 + player.getRandom().nextInt(PAID_LINES);
        player.sendSystemMessage(Component.translatable("chat.dungeontrain.own_photo_tribute.paid." + n).withStyle(ChatFormatting.GRAY));
        sync(player);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }
}
