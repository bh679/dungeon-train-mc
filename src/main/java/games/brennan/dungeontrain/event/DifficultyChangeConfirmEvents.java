package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ShowDifficultyConfirmPacket;
import games.brennan.dungeontrain.player.DifficultyPartition;
import games.brennan.dungeontrain.player.LoadoutStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a difficulty change until the player has been warned what it costs them.
 *
 * <p>With {@code difficultyIsolatedStash} on, each vanilla difficulty is a separate run
 * ({@link DifficultyPartition}): changing difficulty takes the player's entire carried inventory,
 * their XP and their Ender Chest away and hands them the target difficulty's — which, for a
 * difficulty they have never played, is nothing at all ({@code LoadoutStore.clear}). That is
 * indistinguishable from a bug when it happens with no warning, and until now the only explanation
 * arrived <em>after</em> the fact, as a chat line ({@link DifficultyIsolationEvents}).</p>
 *
 * <p>Two entry points, one gate — both cancel and route through {@link #request}:</p>
 * <ul>
 *   <li>{@code ServerGamePacketListenerImplDifficultyMixin} — the vanilla Options-screen Difficulty
 *       button (and any other client that sends {@code ServerboundChangeDifficultyPacket}).</li>
 *   <li>{@link CommandEvent} — {@code /difficulty <level>}. A console, command block or function
 *       has no one to ask, so those run unheld.</li>
 * </ul>
 *
 * <p>On confirm the change is applied directly with {@code MinecraftServer#setDifficulty}, using the
 * same {@code force} flag the origin would have used (the packet handler passes {@code false}, the
 * command {@code true}). Deliberately <b>not</b> a command re-dispatch — re-running the command
 * would need a re-entrancy flag and would tangle with the Free Play confirmation's own re-dispatch
 * ({@code CheatDetectionEvents}), which already gates {@code /difficulty} for a clean run.</p>
 *
 * <p>The whole feature is inert when {@code difficultyIsolatedStash} is off: nothing is swapped, so
 * there is nothing to warn about and difficulty changes stay instant.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DifficultyChangeConfirmEvents {

    /**
     * A difficulty change held per-player while its confirmation is open. {@code force} is the
     * origin's {@code MinecraftServer#setDifficulty} flag; {@code announce} marks the command path,
     * which owes the player the command's own success feedback once it finally runs.
     */
    private record Pending(Difficulty to, boolean force, boolean announce) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private DifficultyChangeConfirmEvents() {}

    /**
     * Hold {@code to} and prompt {@code player}, if this change is one worth warning about.
     *
     * @param force    the {@code MinecraftServer#setDifficulty} force flag of the origin path
     * @param announce whether to echo the vanilla command success message once it commits
     * @return true when the change was held (the caller must cancel it), false to let it run
     */
    public static boolean request(ServerPlayer player, Difficulty to, boolean force, boolean announce) {
        if (!DungeonTrainConfig.getDifficultyIsolatedStash()) {
            return false; // one shared stash — a difficulty change costs nothing
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        Difficulty from = server.getWorldData().getDifficulty();
        if (from == to) {
            return false;
        }
        boolean targetEmpty = !LoadoutStore.has(player.getUUID(), DifficultyPartition.keyFor(to));
        PENDING.put(player.getUUID(), new Pending(to, force, announce));
        DungeonTrainNet.sendTo(player, new ShowDifficultyConfirmPacket(from, to, targetEmpty));
        return true;
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return; // console / command block / function — no one to ask
        Difficulty to = targetOf(event.getParseResults().getReader().getString());
        if (to == null) return;     // not `/difficulty <level>` (a bare query changes nothing)
        if (request(player, to, true, true)) {
            event.setCanceled(true);
        }
    }

    /**
     * The difficulty {@code raw} sets, or null when it isn't a {@code /difficulty <level>}. Parsed
     * off the raw string rather than the Brigadier tree so aliases and the namespaced
     * {@code minecraft:difficulty} form are handled uniformly — the same reasoning as
     * {@code CommandAllowlist}.
     */
    private static Difficulty targetOf(String raw) {
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 2) return null;
        String root = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
        if (!root.equals("difficulty") && !root.equals("minecraft:difficulty")) return null;
        return Difficulty.byName(parts[1]);
    }

    /** Called from {@code DifficultyConfirmResponsePacket} on the server thread. */
    public static void onConfirmResponse(ServerPlayer player, boolean confirmed) {
        Pending pending = PENDING.remove(player.getUUID());
        if (pending == null) return;
        if (!confirmed) return; // backed out — the difficulty stayed as it was
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.setDifficulty(pending.to(), pending.force());
        if (pending.announce() && server.getWorldData().getDifficulty() == pending.to()) {
            player.createCommandSourceStack().sendSuccess(
                () -> Component.translatable("commands.difficulty.success", pending.to().getDisplayName()), true);
        }
    }

    /** A player who logs out mid-prompt has nothing held for them. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }
}
