package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.difficulty.BoardingProgressData;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.NapSavedPacket;
import games.brennan.dungeontrain.net.ShowNapPromptPacket;
import games.brennan.dungeontrain.world.RunWorldNames;
import games.brennan.dungeontrain.worldgen.BandLabel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The server half of a nap: offer it, and on Main Menu save the player to a {@link NapSnapshot} and
 * tell the client to leave and delete the world ({@link NapSavedPacket}).
 *
 * <p>The offer is remembered per player with the bed it came from, so an answer only counts for a
 * prompt the server actually sent, and the bed is checked again before saving: it could have been
 * broken while the screen was open.</p>
 */
public final class NapService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code TrainRelocation}'s calibrated carriage length, for the carriage number only. */
    private static final double BLOCKS_PER_CARRIAGE = 35676.0 / 1041.0;

    private record Offer(BlockPos bedPos, boolean night) {}

    /** Server-thread only. */
    private static final Map<UUID, Offer> OFFERS = new HashMap<>();

    private NapService() {}

    /** What a bed or anchor use came to, for the caller's feedback. */
    public enum Outcome {
        /** The prompt is open. */
        OFFERED,
        /** No nap here; vanilla carries on as if this class did not exist. */
        NONE,
        /** A natural spot, but monsters are nearby: vanilla's "monsters nearby" refusal. */
        MONSTERS,
        /** A natural bed's own item placed again: "tampered with". */
        MOVED,
        /** A bed the player made: "a bed of your making". */
        MADE
    }

    /** Ask {@code player} about a nap at the bed or anchor at {@code spot}, if it and the run allow one. */
    public static Outcome offer(ServerPlayer player, BlockPos spot, boolean night) {
        if (!NapEligibility.runAllowsNap(player) || levelIdOf(player.getServer()) == null) return Outcome.NONE;
        switch (NapEligibility.spotAt(player.serverLevel(), spot)) {
            case NONE -> { return Outcome.NONE; }
            case MOVED -> { return Outcome.MOVED; }
            case MADE -> { return Outcome.MADE; }
            default -> { }
        }
        if (NapEligibility.monstersNearby(player, spot)) return Outcome.MONSTERS;
        OFFERS.put(player.getUUID(), new Offer(spot.immutable(), night));
        LOGGER.info("[DungeonTrain] Nap offered to {} ({}) at {}", player.getName().getString(),
            night ? "night" : "day", spot);
        DungeonTrainNet.sendTo(player, new ShowNapPromptPacket(night));
        return Outcome.OFFERED;
    }

    /** The action-bar line for a tampered bed, or null for any other outcome. */
    public static Component tamperedMessage(Outcome outcome) {
        return switch (outcome) {
            case MOVED -> Component.translatable("chat.dungeontrain.nap.tampered.moved");
            case MADE -> Component.translatable("chat.dungeontrain.nap.tampered.made");
            default -> null;
        };
    }

    public static void forget(UUID player) {
        OFFERS.remove(player);
    }

    static void clear() {
        OFFERS.clear();
    }

    public static void onResponse(ServerPlayer player, boolean accepted) {
        Offer offer = OFFERS.remove(player.getUUID());
        if (offer == null) return;
        if (!accepted) {
            if (offer.night() && player.isSleeping()) player.stopSleepInBed(true, true);
            return;
        }
        String levelId = levelIdOf(player.getServer());
        if (levelId == null || !NapEligibility.canNap(player, offer.bedPos())) {
            refuse(player, "no longer eligible");
            return;
        }
        NapSnapshot nap = capture(player, levelId);
        try {
            NapStore.local().save(player.getUUID(), NapSlot.key(nap.cheated(), player.serverLevel().getDifficulty()), nap);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Nap for {} could not be saved", player.getName().getString(), e);
            refuse(player, "save failed");
            return;
        }
        LOGGER.info("[DungeonTrain] Nap {} saved for {}: x={} carriage={} band={} direction={} cheated={}",
            nap.napId(), player.getName().getString(), Math.round(nap.x()), nap.carriage(), nap.band(),
            nap.direction(), nap.cheated());
        DungeonTrainNet.sendTo(player, new NapSavedPacket(levelId));
    }

    static NapSnapshot capture(ServerPlayer player, String levelId) {
        MinecraftServer server = player.getServer();
        CompoundTag playerTag = player.saveWithoutId(new CompoundTag());
        double x = player.getX();
        String band = "";
        try {
            band = BandLabel.bandAt(server.overworld(), Mth.floor(x));
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Nap: no band label at x={}", x, e);
        }
        // Flushed first, so the files read below are this moment's.
        player.getAdvancements().save();
        player.getStats().save();
        String advancements = readPlayerFile(server, LevelResource.PLAYER_ADVANCEMENTS_DIR, player.getUUID());
        String stats = readPlayerFile(server, LevelResource.PLAYER_STATS_DIR, player.getUUID());
        CompoundTag narrative = new CompoundTag();
        try {
            narrative = NarrativeProgressData.get(server.overworld()).exportFor(player.getUUID());
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap: narrative progress not carried", e);
        }
        return new NapSnapshot(UUID.randomUUID(), System.currentTimeMillis(), modVersion(),
            levelId, playerTag, x, (int) Math.floor(x / BLOCKS_PER_CARRIAGE), band, facingAlongX(player),
            BoardingProgressData.get(server.overworld()).travelledCarriageIndex(), RunIntegrity.isCheated(player),
            server.getWorldData().getDifficulty().getKey(), advancements, stats, narrative);
    }

    /** {@code <dir>/<uuid>.json} of this world as text, or empty when it is missing or unreadable. */
    private static String readPlayerFile(MinecraftServer server, LevelResource dir, UUID player) {
        Path file = server.getWorldPath(dir).resolve(player + ".json");
        if (!Files.isRegularFile(file)) return "";
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap: could not read {}; not carried", file, e);
            return "";
        }
    }

    private static String modVersion() {
        return net.neoforged.fml.ModList.get().getModContainerById(DungeonTrain.MOD_ID)
            .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }

    /** +1 facing +X, -1 facing -X: the X part of the look vector is {@code -sin(yaw)}. */
    static int facingAlongX(ServerPlayer player) {
        return -Mth.sin(player.getYRot() * Mth.DEG_TO_RAD) >= 0 ? 1 : -1;
    }

    /** The world's save folder, when it is one DT generated (and so may delete); otherwise null. */
    static String levelIdOf(MinecraftServer server) {
        if (server == null) return null;
        try {
            String id = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
            return RunWorldNames.isAutoGenerated(id) ? id : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void refuse(ServerPlayer player, String why) {
        LOGGER.info("[DungeonTrain] Nap for {} refused: {}", player.getName().getString(), why);
        player.displayClientMessage(Component.translatable("chat.dungeontrain.nap.failed")
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
        if (player.isSleeping()) player.stopSleepInBed(true, true);
    }
}
