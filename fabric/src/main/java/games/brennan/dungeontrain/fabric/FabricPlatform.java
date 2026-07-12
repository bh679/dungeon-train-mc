package games.brennan.dungeontrain.fabric;

import games.brennan.dungeontrain.platform.DtModInfo;
import games.brennan.dungeontrain.platform.DtPlatform;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Fabric-backed {@link DtPlatform}, registered for {@link java.util.ServiceLoader}
 * lookup via {@code META-INF/services} in this module's resources. The Fabric
 * mirror of {@code NeoForgePlatform}: pure delegation to {@code FabricLoader} /
 * {@code EnvType}.
 *
 * <p>{@link #getCurrentServer()} is served from a reference captured by
 * {@code FabricServerEventBridges} on the {@code ServerLifecycleEvents.SERVER_STARTING}
 * → {@code SERVER_STOPPED} window (Fabric has no {@code ServerLifecycleHooks} static
 * accessor). {@link #getBurnTime} reads vanilla's furnace fuel map (Fabric's
 * {@code FuelValues}), the loader-neutral equivalent of NeoForge's
 * {@code ItemStack.getBurnTime} extension.</p>
 */
public final class FabricPlatform implements DtPlatform {

    /** Set by the lifecycle bridge across SERVER_STARTING..STOPPED (null otherwise). */
    private static volatile MinecraftServer currentServer;

    /** Called by {@code FabricServerEventBridges} on server start/stop. */
    public static void setCurrentServer(MinecraftServer server) {
        currentServer = server;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Optional<String> getModVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(c -> c.getMetadata().getVersion().getFriendlyString());
    }

    @Override
    public List<DtModInfo> getLoadedMods() {
        return FabricLoader.getInstance().getAllMods().stream()
            .map(c -> new DtModInfo(
                c.getMetadata().getId(),
                c.getMetadata().getVersion().getFriendlyString(),
                c.getMetadata().getName()))
            .toList();
    }

    @Override
    public boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public boolean isDedicatedServer() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public MinecraftServer getCurrentServer() {
        return currentServer;
    }

    @Override
    public int getBurnTime(ItemStack stack, RecipeType<?> recipeType) {
        if (stack.isEmpty()) {
            return 0;
        }
        // Vanilla 1.21.1 fuel map (the furnace fuel table). Mirrors what NeoForge's
        // ItemStack.getBurnTime(recipeType) resolves to for vanilla fuels — DT's sole
        // caller (ContainerContentsRoller) only needs the vanilla fuel check.
        return net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.getFuel()
            .getOrDefault(stack.getItem(), 0);
    }
}
