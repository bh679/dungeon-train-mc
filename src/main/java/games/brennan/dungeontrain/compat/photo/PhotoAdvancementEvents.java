package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.compat.EchoIdentity;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.narrative.TechnobladePigNames;
import games.brennan.pigmanvillagers.api.PigmanVillagersApi;
import games.brennan.playermob.entity.PlayerMobEntity;
import io.github.mortuusars.exposure.neoforge.api.event.FrameAddedEvent;
import io.github.mortuusars.exposure.util.ExtraData;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.neoforged.neoforge.common.Tags;
import games.brennan.dungeontrain.event.RunStatsEvents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PhotoBiomesPacket;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Feeds The Enchiridion's camera advancements ({@link EnchiridionAdvancements}).
 *
 * <p>A photo is read off Exposure's server-side {@link FrameAddedEvent}, which every camera posts —
 * the Polaroid and DT's disposable camera alike — the moment the frame lands on the camera. Only a
 * player's shot counts; a PlayerMob's own photo (it photographs its giver) is credited to the subject
 * instead, when they pick the print up ({@link #onPickup}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PhotoAdvancementEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Player persistent-data key: the distinct biome ids this player has photographed. */
    static final String PHOTO_BIOMES_KEY = "dungeontrain_photo_biomes";

    /** {@code gameplay_action} fired once every biome has been photographed. */
    static final String PHOTOGRAPHED_EVERY_BIOME = "photographed_every_biome";

    private PhotoAdvancementEvents() {}

    @SubscribeEvent
    public static void onFrameAdded(FrameAddedEvent event) {
        if (!(event.getCameraHolderEntity() instanceof ServerPlayer player)) return;
        rememberSubjects(player, event);
        // Every advancement this shot earns remembers it — see AdvancementPhotoCapture.
        AdvancementPhotoCapture.during(event.getFrame(), () -> photoTriggers(player, event));
    }

    private static void photoTriggers(ServerPlayer player, FrameAddedEvent event) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, EnchiridionAdvancements.TOOK_PHOTO);
        try {
            ExtraData data = event.getFrame().extraData();
            List<LivingEntity> inFrame = event.getEntitiesInFrame();
            ModAdvancementTriggers.PHOTO_SUBJECT.get().trigger(player,
                    PhotoSubjects.keys(facts(player, data, inFrame), PhotoScene.of(player, inFrame)));
            PhotoCounts.record(player, PhotoSubjectTally.of(player, inFrame));
            PhotoFinalMoments.remember(player, event.getFrame(), inFrame);
            data.get(Frame.BIOME).ifPresent(biome -> recordBiome(player, biome));
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read photo subjects for {}: {}", player.getName().getString(), e.toString());
        }
    }

    /** Hold what the shot shows until its print uploads — the relay's photo-subject boards. */
    private static void rememberSubjects(ServerPlayer player, FrameAddedEvent event) {
        try {
            Frame frame = event.getFrame();
            if (!frame.identifier().isId()) return;
            PhotoSubjectTally.record(frame.identifier().id(), PhotoSubjectTally.of(player, event.getEntitiesInFrame()));
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Couldn't tally photo subjects for {}: {}", player.getName().getString(), e.toString());
        }
    }

    /** Tell the client which biomes this world's photos already cover, so the biome album matches. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncPhotographedBiomes(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncPhotographedBiomes(player);
    }

    private static void syncPhotographedBiomes(ServerPlayer player) {
        ListTag seen = player.getPersistentData().getList(PHOTO_BIOMES_KEY, Tag.TAG_STRING);
        List<String> biomes = new java.util.ArrayList<>(seen.size());
        for (Tag t : seen) biomes.add(t.getAsString());
        DungeonTrainNet.sendTo(player, new PhotoBiomesPacket(biomes));
    }

    /** Each animal or mob a collection gains keeps its own photo (when a photo is behind it). */
    @SubscribeEvent
    public static void onProgress(AdvancementEvent.AdvancementProgressEvent event) {
        if (event.getProgressType() != AdvancementEvent.AdvancementProgressEvent.ProgressType.GRANT) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AdvancementPhotoCapture.onCriterion(player, event.getAdvancement(), event.getCriterionName());
    }

    /** A print a PlayerMob took and tossed to its subject: whoever catches it earned "Candid". */
    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!event.getItemEntity().getPersistentData().getBoolean(StartingBookEvents.ENTITY_TAG_HANDED_PHOTO)) return;
        AdvancementPhotoCapture.during(event.getOriginalStack(), () ->
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, EnchiridionAdvancements.PHOTOGRAPHED_BY_PLAYERMOB));
    }

    private static PhotoSubjects.Facts facts(ServerPlayer player, ExtraData data, List<LivingEntity> inFrame) {
        Set<String> types = new HashSet<>();
        boolean playerMob = false, friend = false, echo = false, ownEcho = false, otherEcho = false;
        boolean pigman = false, killerBunny = false, techno = false;
        for (LivingEntity e : inFrame) {
            if (e == player) continue;
            types.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            if (e instanceof PlayerMobEntity mob) {
                playerMob = true;
                if (mob.feelingToward(player) > RunStatsEvents.FRIEND_FEELING_MIN) friend = true;
                if (EchoIdentity.sourcePlayer(e).isPresent()) {
                    echo = true;
                    if (EchoIdentity.isOwnEcho(e, player.getUUID())) ownEcho = true;
                    else otherEcho = true;
                }
            }
            if (PigmanVillagersApi.isPigman(e)) pigman = true;
            if (e instanceof Rabbit rabbit && rabbit.getVariant() == Rabbit.Variant.EVIL) killerBunny = true;
            if (e instanceof Pig pig && pig.hasCustomName()
                    && TechnobladePigNames.names().contains(pig.getCustomName().getString())) techno = true;
        }
        String dimension = data.get(Frame.DIMENSION).map(ResourceLocation::toString)
                .orElse(player.level().dimension().location().toString());
        return new PhotoSubjects.Facts(types, playerMob, friend, echo, ownEcho, otherEcho, pigman, killerBunny,
                techno, data.getOrDefault(Frame.IN_CAVE, false) || inCave(player), dimension, bandAt(player));
    }

    /**
     * Underground, without Exposure's own test's catch: it wants the photographer below sea level with
     * no skylight at all, which a cave inside one of the train's raised mountains rarely is. A cave biome
     * counts outright; otherwise no skylight and no sky above.
     */
    private static boolean inCave(ServerPlayer player) {
        Level level = player.level();
        BlockPos pos = player.blockPosition();
        if (level.getBiome(pos).is(Tags.Biomes.IS_UNDERGROUND)) return true;
        return level.getBrightness(LightLayer.SKY, pos) == 0 && !level.canSeeSky(pos);
    }

    /** The dimensional band the photographer stands in — the bands are overworld X ranges. */
    private static String bandAt(ServerPlayer player) {
        if (player.level().dimension() != Level.OVERWORLD) return null;
        return BandAdvancements.bandAt((ServerLevel) player.level(), player.getBlockX());
    }

    private static void recordBiome(ServerPlayer player, ResourceLocation biome) {
        ListTag seen = player.getPersistentData().getList(PHOTO_BIOMES_KEY, Tag.TAG_STRING);
        String id = biome.toString();
        for (Tag t : seen) {
            if (id.equals(t.getAsString())) {
                ModAdvancementTriggers.PHOTO_BIOMES.get().trigger(player, seen.size());
                checkEveryBiome(player, seen.size());
                return;
            }
        }
        ListTag updated = seen.copy();
        updated.add(StringTag.valueOf(id));
        player.getPersistentData().put(PHOTO_BIOMES_KEY, updated);
        // A new biome: its photo joins the biome tiers' album (the frame is current — see onFrameAdded).
        AdvancementPhotoCapture.sendEntry(player,
                ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, EnchiridionAdvancements.BIOME_ALBUM), id);
        ModAdvancementTriggers.PHOTO_BIOMES.get().trigger(player, updated.size());
        checkEveryBiome(player, updated.size());
    }

    /** "Coffee Table Book": a photo taken in every biome the game has. */
    private static void checkEveryBiome(ServerPlayer player, int photographed) {
        int total = player.registryAccess().registryOrThrow(Registries.BIOME).size();
        if (total > 0 && photographed >= total) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, PHOTOGRAPHED_EVERY_BIOME);
        }
    }
}
