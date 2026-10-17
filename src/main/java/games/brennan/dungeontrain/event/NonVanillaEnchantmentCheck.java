package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisabledModContent;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Warns when a non-vanilla enchantment is still offered by the enchanting table or villagers.
 *
 * <p>DT keeps mod enchantments out of those two tags with {@code remove} lists in
 * {@code data/minecraft/tags/enchantment/}, which name each enchantment — a tag can't be filtered by
 * namespace. A mod update that adds a new one would slip past them; this makes it visible at server
 * start so the lists can be extended.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NonVanillaEnchantmentCheck {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<TagKey<Enchantment>> CHECKED_TAGS =
        List.of(EnchantmentTags.IN_ENCHANTING_TABLE, EnchantmentTags.TRADEABLE);

    private NonVanillaEnchantmentCheck() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            Registry<Enchantment> registry = event.getServer().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            for (TagKey<Enchantment> tag : CHECKED_TAGS) {
                List<ResourceLocation> leaked = registry.getTag(tag).stream()
                    .flatMap(set -> set.stream())
                    .flatMap(holder -> holder.unwrapKey().stream())
                    .map(key -> key.location())
                    .filter(DisabledModContent::isDisabledEnchantment)
                    .toList();
                if (!leaked.isEmpty()) {
                    LOGGER.warn("[DungeonTrain] #{} still offers non-vanilla enchantments {} — add them to the remove"
                        + " list in data/minecraft/tags/enchantment/{}.json", tag.location(), leaked, tag.location().getPath());
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Couldn't check enchantment tags for non-vanilla enchantments", t);
        }
    }
}
