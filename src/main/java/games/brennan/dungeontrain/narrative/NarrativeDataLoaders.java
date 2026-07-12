package games.brennan.dungeontrain.narrative;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtReloadListenerRegistrar;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

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
public final class NarrativeDataLoaders {

    private NarrativeDataLoaders() {}

    public static void registerReloadListeners(DtReloadListenerRegistrar registrar) {
        registrar.register(listener("dungeontrain:narrative/stories", StoryRegistry::load));
        registrar.register(listener("dungeontrain:narrative/random_books", RandomBookRegistry::load));
        registrar.register(listener("dungeontrain:narrative/starting_books", StartingBookRegistry::load));
        registrar.register(listener("dungeontrain:narrative/death_lore", DeathLoreStore::load));
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
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
