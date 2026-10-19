package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.stage.StageWoodFamily;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Warns when a wood family names a block that isn't registered.
 *
 * <p>Most families are modded woods (Biomes O' Plenty, the BetterX mods, VanillaBackport's pale oak), named
 * by id only. If a mod update renames one, its stage's placeholders quietly fall back to spruce and log on
 * every cell — this lists every missing id once at server start instead.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StageWoodFamilyCheck {

    private static final Logger LOGGER = LogUtils.getLogger();

    private StageWoodFamilyCheck() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            List<String> missing = new ArrayList<>();
            for (StageWoodFamily family : StageWoodFamily.values()) {
                for (StageWoodFamily.WoodKind kind : StageWoodFamily.WoodKind.values()) {
                    String id = family.block(kind);
                    ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
                    if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                        missing.add(family.id() + "/" + kind + "=" + id);
                    }
                }
            }
            if (!missing.isEmpty()) {
                LOGGER.warn("[DungeonTrain] {} wood-family block(s) aren't registered — those stage placeholders"
                        + " fall back to spruce: {}", missing.size(), missing);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Couldn't check the stage wood families", t);
        }
    }
}
