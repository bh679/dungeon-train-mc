package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Which Tribute price each player still needs a photo at. The spending advancements on other people's
 * photos ask for one Tribute at a price, and a photo's price comes from the Tributes it has had, so
 * the dearer tiers can only be earned on photos that are rare. The pool request names these prices,
 * and the relay gives each slot a floor chance of a photo that meets one (playerphotos.js queryPool).
 *
 * <p>The pool is shared per server, so on a server another player can find a photo asked for here.</p>
 */
public final class PhotoTierNeed {

    /** A spending advancement, as the JSON under {@code advancement/enchiridion/} asks for it. */
    record Tier(String path, int cost) {}

    /** The chain in order, cheapest first. {@code PhotoTierNeedTest} holds the costs to the JSON. */
    static final List<Tier> TIERS = List.of(
            new Tier("enchiridion/supporting_artists", 3),
            new Tier("enchiridion/appreciating_photos", 5),
            new Tier("enchiridion/voyeur", 7));

    private PhotoTierNeed() {}

    /** The price of the first tier not yet {@code earned}, or empty when the chain is finished. */
    static OptionalInt nextCost(Predicate<String> earned) {
        return TIERS.stream().filter(tier -> !earned.test(tier.path())).mapToInt(Tier::cost).findFirst();
    }

    /** The {@code needs} query value: distinct prices, lowest first; blank when there are none. */
    static String param(Collection<Integer> costs) {
        return new TreeSet<>(costs).stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** {@link #param} for everyone online. Free Play earns no advancements, so it needs nothing. Server thread. */
    static String needsOf(MinecraftServer server) {
        return param(server.getPlayerList().getPlayers().stream()
                .filter(player -> !RunIntegrity.isCheated(player))
                .map(player -> nextCost(path -> earned(server, player, path)))
                .filter(OptionalInt::isPresent)
                .map(OptionalInt::getAsInt)
                .toList());
    }

    /** A tier this build's data doesn't carry counts as earned: nothing can be earned from it. */
    private static boolean earned(MinecraftServer server, ServerPlayer player, String path) {
        Optional<AdvancementHolder> holder = Optional.ofNullable(
                server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path)));
        return holder.map(found -> player.getAdvancements().getOrStartProgress(found).isDone()).orElse(true);
    }
}
