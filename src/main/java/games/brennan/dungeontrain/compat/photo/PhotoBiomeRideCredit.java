package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.TerraOmnia;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.event.AchievementEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The one-time credit from Coffee Table Book to Terra Omnia: a biome photographed before Coffee Table Book
 * was trimmed to Terra Omnia's list counts as ridden through. It runs once per world (that world's photos, as
 * they stood on its first load since) and once per player (the photos banked across worlds,
 * {@link GlobalPhotoProgress}, which covers worlds since deleted). Both are taken before any new photo can
 * land, so only older photos are ever credited. It only ever adds to Terra Omnia's list.
 */
final class PhotoBiomeRideCredit {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Player persistent-data flag: this world's photographed biomes have been credited. */
    static final String WORLD_CREDITED_KEY = "dungeontrain_photo_biomes_ride_credited";

    private PhotoBiomeRideCredit() {}

    /**
     * At login, after the photo tallies are merged: credit what has not been credited yet.
     *
     * @param worldBiomes this world's photographed biomes as read before the cross-world merge
     */
    static void apply(ServerPlayer player, Set<String> worldBiomes) {
        Set<String> photographed = new LinkedHashSet<>();
        boolean creditWorld = !player.getPersistentData().getBoolean(WORLD_CREDITED_KEY);
        if (creditWorld) photographed.addAll(worldBiomes);
        // A Free Play run credits nothing, so the cross-world credit waits for an honest world.
        boolean creditGlobal = !RunIntegrity.isCheated(player)
                && !GlobalPhotoProgress.read(player.getUUID()).rideCredited();
        if (creditGlobal) photographed.addAll(GlobalPhotoProgress.read(player.getUUID()).biomes());
        if (!creditWorld && !creditGlobal) return;

        var registry = player.registryAccess().registryOrThrow(Registries.BIOME);
        Set<ResourceLocation> credit = toCredit(photographed, TerraOmnia.ordered(registry).keySet());
        AchievementEvents.recordLifetimeBiomes(player, credit);
        if (creditWorld) player.getPersistentData().putBoolean(WORLD_CREDITED_KEY, true);
        if (creditGlobal) GlobalPhotoProgress.merge(player.getUUID(), new GlobalPhotoProgress.Tally(Map.of(), Set.of(), true));
        LOGGER.info("[DungeonTrain] Photographed biomes credited to Terra Omnia for {}: {} biomes (world: {}, cross-world: {})",
            player.getName().getString(), credit.size(), creditWorld, creditGlobal);
    }

    /** The photographed {@code biomes} Terra Omnia asks for; unparseable, unknown and excluded ids are dropped. Pure. */
    static Set<ResourceLocation> toCredit(Collection<String> biomes, Collection<ResourceLocation> terraOmnia) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (String biome : biomes) {
            ResourceLocation id = ResourceLocation.tryParse(biome);
            if (id != null && terraOmnia.contains(id)) out.add(id);
        }
        return Set.copyOf(out);
    }
}
