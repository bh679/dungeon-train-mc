package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.platform.DtModInfo;
import games.brennan.dungeontrain.platform.DtPlatform;
import java.util.List;
import java.util.Optional;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * NeoForge-backed {@link DtPlatform}, registered for {@link
 * java.util.ServiceLoader} lookup via {@code META-INF/services} in this module's
 * resources. Pure delegation to {@code ModList} / {@code FMLEnvironment} — no
 * behavior change from the pre-seam callsites.
 */
public final class NeoForgePlatform implements DtPlatform {

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Optional<String> getModVersion(String modId) {
        return ModList.get().getModContainerById(modId)
            .map(c -> c.getModInfo().getVersion().toString());
    }

    @Override
    public List<DtModInfo> getLoadedMods() {
        return ModList.get().getMods().stream()
            .map(info -> new DtModInfo(
                info.getModId(),
                info.getVersion().toString(),
                info.getDisplayName()))
            .toList();
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist.isClient();
    }

    @Override
    public boolean isDedicatedServer() {
        return FMLEnvironment.dist.isDedicatedServer();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }
}
