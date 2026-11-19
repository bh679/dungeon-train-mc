package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * The one-time move from Terra Omnia's old 150-biome version ({@link TerraOmnia#OLD_ID}) to the every-biome one.
 * Everyone who earned the old one loses it (it is a different advancement now) but keeps their biomes: their
 * permanent list ({@link GlobalBiomeProgress}) is topped up to {@link TerraOmnia#OLD_THRESHOLD} in checklist
 * order, so they start the new one where the old one left them. It only ever adds; removing the old id from
 * their profile is what makes it run once.
 */
public final class TerraOmniaMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TerraOmniaMigration() {}

    /** At login: credit and retire the old version if this profile still holds it. */
    public static void apply(ServerPlayer player) {
        String partition = AdvancementProfiles.partitionFor(player, TerraOmnia.OLD_ID);
        if (!GlobalAchievementStore.read(player.getUUID(), partition).contains(TerraOmnia.OLD_ID)) return;
        var registry = player.registryAccess().registryOrThrow(Registries.BIOME);
        List<ResourceLocation> credit = TerraOmnia.topUp(GlobalBiomeProgress.biomes(player.getUUID()),
            TerraOmnia.ordered(registry).keySet(), TerraOmnia.OLD_THRESHOLD);
        GlobalBiomeProgress.addAll(player.getUUID(), credit);
        GlobalAchievementStore.remove(player.getUUID(), partition, TerraOmnia.OLD_ID);
        LOGGER.info("[DungeonTrain] Terra Omnia moved to every biome for {}: old 150 version retired, {} biomes credited ({} now)",
            player.getName().getString(), credit.size(), GlobalBiomeProgress.biomes(player.getUUID()).size());
    }
}
