package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Ordered list of all carriage variants available for spawning and editing.
 * The four {@link CarriageType} built-ins come first in declared enum order;
 * custom variants follow in alphabetical order by id.
 *
 * <p>Custom variants are discovered by scanning
 * {@link CarriageTemplateStore#directory()} for {@code .nbt} files whose
 * basenames are not one of the built-in ids. The registry holds only the
 * identifiers — the template bytes stay in {@link CarriageTemplateStore}.
 * Adding or removing a {@code .nbt} file without going through the editor
 * requires a server restart (or {@link #reload()}) to pick up.
 *
 * <p>Wired to {@link ServerStartingEvent} for the scan and
 * {@link ServerStoppedEvent} to release state between worlds.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CarriageVariantRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<CarriageVariant> BUILTINS;
    static {
        List<CarriageVariant> list = new ArrayList<>();
        for (CarriageType t : CarriageType.values()) list.add(CarriageVariant.of(t));
        BUILTINS = List.copyOf(list);
    }

    /** Sorted custom variant names. Mutations go through register/unregister/reload. */
    private static final TreeSet<String> CUSTOMS = new TreeSet<>();

    private CarriageVariantRegistry() {}

    /**
     * Every variant, built-ins first (enum order), then customs alphabetical.
     * The returned list is a snapshot — safe to iterate without locking even if
     * another thread mutates the registry.
     */
    public static synchronized List<CarriageVariant> allVariants() {
        List<CarriageVariant> all = new ArrayList<>(BUILTINS.size() + CUSTOMS.size());
        all.addAll(BUILTINS);
        for (String name : CUSTOMS) all.add(new CarriageVariant.Custom(name));
        return all;
    }

    public static synchronized List<CarriageVariant> builtins() {
        return BUILTINS;
    }

    /**
     * Look up a variant by id. Returns empty if {@code id} is neither a
     * built-in nor a registered custom.
     */
    public static synchronized Optional<CarriageVariant> find(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        for (CarriageType t : CarriageType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(CarriageVariant.of(t));
            }
        }
        if (CUSTOMS.contains(key)) return Optional.of(new CarriageVariant.Custom(key));
        return Optional.empty();
    }

    /**
     * Register a new custom variant. Returns false if a variant with the same
     * id is already present (built-in or custom). Caller is responsible for
     * writing the backing .nbt file before calling this.
     */
    public static synchronized boolean register(CarriageVariant.Custom variant) {
        if (CarriageVariant.isReservedBuiltinName(variant.name())) return false;
        return CUSTOMS.add(variant.name());
    }

    /**
     * Remove a custom variant from the registry. Built-ins can't be
     * unregistered. Returns false if the id was not a registered custom.
     * Caller is responsible for deleting the backing .nbt file.
     */
    public static synchronized boolean unregister(String id) {
        if (CarriageVariant.isReservedBuiltinName(id)) return false;
        return CUSTOMS.remove(id.toLowerCase(Locale.ROOT));
    }

    /** Rescan the templates directory and rebuild the custom variant list. */
    public static synchronized void reload() {
        CUSTOMS.clear();
        Path dir = CarriageTemplateStore.directory();
        if (!Files.isDirectory(dir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".nbt")) continue;
                String basename = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                if (CarriageVariant.isReservedBuiltinName(basename)) continue;
                if (!CarriageVariant.NAME_PATTERN.matcher(basename).matches()) {
                    LOGGER.warn("[DungeonTrain] Ignoring template '{}' — invalid name", name);
                    continue;
                }
                CUSTOMS.add(basename);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan templates directory {}: {}", dir, e.toString());
        }

        LOGGER.info("[DungeonTrain] Carriage variant registry loaded — {} built-in + {} custom",
            BUILTINS.size(), CUSTOMS.size());
    }

    public static synchronized void clear() {
        CUSTOMS.clear();
    }

    public static synchronized List<String> customIds() {
        return Collections.unmodifiableList(new ArrayList<>(CUSTOMS));
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
