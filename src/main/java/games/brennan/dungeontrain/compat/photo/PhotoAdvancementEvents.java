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
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
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

    private PhotoAdvancementEvents() {}

    @SubscribeEvent
    public static void onFrameAdded(FrameAddedEvent event) {
        if (!(event.getCameraHolderEntity() instanceof ServerPlayer player)) return;
        // Every advancement this shot earns remembers it — see AdvancementPhotoCapture.
        AdvancementPhotoCapture.during(event.getFrame(), () -> photoTriggers(player, event));
    }

    private static void photoTriggers(ServerPlayer player, FrameAddedEvent event) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, EnchiridionAdvancements.TOOK_PHOTO);
        try {
            ExtraData data = event.getFrame().extraData();
            ModAdvancementTriggers.PHOTO_SUBJECT.get().trigger(player,
                    PhotoSubjects.keys(facts(player, data, event.getEntitiesInFrame())));
            data.get(Frame.BIOME).ifPresent(biome -> recordBiome(player, biome));
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read photo subjects for {}: {}", player.getName().getString(), e.toString());
        }
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
        boolean playerMob = false, echo = false, pigman = false, killerBunny = false, techno = false;
        for (LivingEntity e : inFrame) {
            if (e == player) continue;
            types.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            if (e instanceof PlayerMobEntity) {
                playerMob = true;
                if (EchoIdentity.sourcePlayer(e).isPresent()) echo = true;
            }
            if (PigmanVillagersApi.isPigman(e)) pigman = true;
            if (e instanceof Rabbit rabbit && rabbit.getVariant() == Rabbit.Variant.EVIL) killerBunny = true;
            if (e instanceof Pig pig && pig.hasCustomName()
                    && TechnobladePigNames.names().contains(pig.getCustomName().getString())) techno = true;
        }
        String dimension = data.get(Frame.DIMENSION).map(ResourceLocation::toString)
                .orElse(player.level().dimension().location().toString());
        return new PhotoSubjects.Facts(types, playerMob, echo, pigman, killerBunny, techno,
                data.getOrDefault(Frame.SELFIE, false), data.getOrDefault(Frame.IN_CAVE, false),
                dimension, bandAt(player));
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
                return;
            }
        }
        ListTag updated = seen.copy();
        updated.add(StringTag.valueOf(id));
        player.getPersistentData().put(PHOTO_BIOMES_KEY, updated);
        ModAdvancementTriggers.PHOTO_BIOMES.get().trigger(player, updated.size());
    }
}
