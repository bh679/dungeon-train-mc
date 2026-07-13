package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.function.Consumer;

/**
 * NeoForge registration seam for the narrative prose registries. Registers each
 * registry's {@code load(ResourceManager)} as a server-data reload listener on
 * {@code AddReloadListenerEvent}, so bundled prose loads through the vanilla
 * datapack pipeline (honouring {@code /reload} and datapack overrides) instead of
 * a raw classpath scan.
 *
 * <p>The listener <em>bodies</em> — {@link StoryRegistry#load},
 * {@link RandomBookRegistry#load}, {@link StartingBookRegistry#load},
 * {@link DeathLoreStore#load} — are plain {@link ResourceManager} code with no
 * loader-specific imports, so a future Fabric entrypoint can reuse them via
 * {@code ResourceManagerHelper.get(SERVER_DATA)}. Only this thin registration
 * class is NeoForge-specific (mirrors the AdventureItemNames sibling's
 * loader-neutral-listener + per-loader-entrypoint split).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NarrativeDataLoaders {

    private NarrativeDataLoaders() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(listener("dungeontrain:narrative/stories", StoryRegistry::load));
        event.addListener(listener("dungeontrain:narrative/random_books", RandomBookRegistry::load));
        event.addListener(listener("dungeontrain:narrative/starting_books", StartingBookRegistry::load));
        event.addListener(listener("dungeontrain:narrative/death_lore", DeathLoreStore::load));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        StoryRegistry.clear();
        RandomBookRegistry.clear();
        StartingBookRegistry.clear();
        DeathLoreStore.clear();
    }

    /** Wrap a {@code load(ResourceManager)} body in a named reload listener (name shows in the profiler). */
    private static ResourceManagerReloadListener listener(String name, Consumer<ResourceManager> body) {
        return new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                body.accept(resourceManager);
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }
}
