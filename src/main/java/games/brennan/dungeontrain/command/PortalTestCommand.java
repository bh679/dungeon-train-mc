package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.event.PortalTestPlacementService;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.portal.PortalForcedGroups;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * {@code /dungeontrain portal test} — put a dimensional carriage right here and walk into it.
 *
 * <p>Reaching a portal room otherwise means turning the rate on and riding until the lottery
 * yields one: in survival the draw is seeded, spaced by {@link PortalCarriageSelection#MIN_GROUP_GAP}
 * groups, and shut out entirely on the opening stretch of track
 * ({@link PortalCarriageSelection#firstEligibleGroup()}). This is the same thing on demand.</p>
 *
 * <p><b>It does not touch the world's portal rate.</b> {@code /dungeontrain portal carriage} is
 * persisted world state and calls {@code PortalTuningIntegrity.markTuned} — the
 * {@code free_play.cause.portal_rate} trip. A test button that put the world into Free Play would
 * be worse than no button. The group is forced through {@link PortalForcedGroups} instead, which
 * writes nothing down and lapses when the server stops.</p>
 *
 * <p><b>Why the seed group is the one forced.</b> {@link TrainAssembler#spawnTrain} derives its
 * initial pIdx from the spawner's X relative to the origin, and both come from the player here — so
 * a freshly-seeded train always puts <b>carriage 0</b> at the player's feet. Group ordinal 0 is
 * therefore exactly the group they are about to be standing beside, and the one worth forcing.</p>
 *
 * <p>Registered as a subcommand node from {@link PortalCommand#build}, and surfaced as the
 * "Spawn Dimensional Carriage" button in the Debug menu.</p>
 */
public final class PortalTestCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Vertical clearance above {@code trainY} for the holding spot. Comfortably above
     * {@link CarriageDims#MAX_HEIGHT} so the player can never be inside the assembly footprint —
     * {@link TrainAssembler} turns every world block in that volume into ship blocks and drags along
     * whatever is caught inside it (issue #22). Same figure {@code /dtp} holds at.
     */
    private static final int HOLD_Y_MARGIN = 48;

    /** Safety margin below the train level's build-height ceiling for the holding spot. */
    private static final int CEILING_MARGIN = 5;

    /** The group a test spawn forces: the one the fresh train seeds the player into. */
    private static final long TEST_GROUP_ORDINAL = 0L;

    private PortalTestCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("test").executes(ctx -> run(ctx.getSource()));
    }

    private static int run(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        if (!data.startsWithTrain()) {
            source.sendFailure(Component.literal(
                "This world doesn't use the auto-train system (startsWithTrain is off), so there is no "
                    + "train to put a dimensional carriage in."));
            return 0;
        }

        // A portal is entry corridor, cart, exit corridor — three slots. A shorter group would get
        // half a portal, and an entry whose exit landed in the next group strands whoever walked in.
        int groupSize = DungeonTrainConfig.getGroupSize();
        if (groupSize < PortalCarriageSelection.PORTAL_GROUP_SPAN) {
            source.sendFailure(Component.literal(
                "groupSize is " + groupSize + " — a dimensional carriage needs "
                    + PortalCarriageSelection.PORTAL_GROUP_SPAN
                    + " slots (entry corridor, room, exit corridor). Raise groupSize in the config first.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Operate in the player's CURRENT dimension: the appender runs independently per loaded
        // level, so each dimension keeps its own train once one exists there. Same reasoning as /dtp.
        ServerLevel trainLevel = player.serverLevel();
        CarriageDims dims = data.dims();
        int trainY = data.getTrainY();
        TrackGeometry geometry = TrackGeometry.from(dims, trainY);

        // Leave the editor first if they are in it — the menu's "Test the Carriage" row is reachable
        // from inside a portal-room plot, and a player teleported to the train with a session still
        // open behind them keeps the editor's game mode and leaves their plots standing in the sky.
        boolean leftEditor = exitEditorSession(player, server);

        // Last press wins rather than accumulating: pressing the button twice should replace the test
        // carriage, not litter the track with forced groups.
        PortalForcedGroups.clear();
        PortalForcedGroups.force(TEST_GROUP_ORDINAL);

        double spawnX = player.getX();
        int holdY = Math.min(trainLevel.getMaxBuildHeight() - CEILING_MARGIN, trainY + HOLD_Y_MARGIN);
        double holdZ = geometry.trackCenterZ() + 0.5;
        player.setInvulnerable(true);
        player.teleportTo(trainLevel, spawnX, holdY, holdZ, player.getYRot(), player.getXRot());

        // The train ALWAYS goes on the track: only X comes from the player. origin.z = 0 is the
        // corridor's trackZMin, not its centreline — cf. /dtp and /dungeontrain spawn.
        BlockPos origin = new BlockPos((int) Math.floor(spawnX), trainY, 0);
        Vector3d spawnerWorldPos = new Vector3d(spawnX, trainY, 0); // only .x is read, to pick the seed pIdx
        Vector3d velocity = new Vector3d(DungeonTrainConfig.getSpeed(), 0.0, 0.0);

        // Seed-only spawn; the per-tick appender extends from here. Config 0 is the "auto" sentinel,
        // not a literal count — same guard as TrainBootstrapEvents.ensureTrainSpawned and /dtp.
        int configCount = DungeonTrainConfig.getNumCarriages();
        int count = configCount > 0 ? configCount : DungeonTrainConfig.DEFAULT_CARRIAGES_AUTO_SEED;

        LOGGER.info("[DungeonTrain] /dungeontrain portal test by {} at X={} — forcing group {} and re-seeding "
                + "the train at origin {} (groupSize={}, configCount={})",
            player.getName().getString(), spawnX, TEST_GROUP_ORDINAL, origin, groupSize, configCount);

        try {
            TrainAssembler.spawnTrain(trainLevel, origin, velocity, count, spawnerWorldPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] portal test spawnTrain failed", t);
            player.setInvulnerable(false);
            PortalForcedGroups.clear();
            source.sendFailure(Component.literal(
                "spawnTrain failed: " + t.getClass().getSimpleName() + ": " + t.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        PortalTestPlacementService.enqueue(player, trainLevel, spawnX);

        if (leftEditor) {
            source.sendSuccess(() -> Component.literal(
                "Left the editor first — plots cleared, and you're back off creative if that is where "
                    + "you started.").withStyle(ChatFormatting.GRAY), false);
        }

        source.sendSuccess(() -> Component.literal(
            "Spawning a dimensional carriage here — you'll land in front of its entry corridor once "
                + "the pair goes live. Walk straight in. The world's portal rate is unchanged."), true);

        // The forced group is per-server, not per-player: on a shared world the last press wins and
        // everyone else's train has just been replaced under them. Say so rather than letting it be
        // a mystery.
        if (server.getPlayerList().getPlayers().size() > 1) {
            source.sendSuccess(() -> Component.literal(
                "  → this re-seeded the shared train, so everyone riding it has been moved.")
                .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /**
     * Unwind whichever editor session this player has open, and clear the plots behind them.
     *
     * <p>The same sequence {@code EditorCommand.runExit} runs, with {@link PortalRoomEditor} tried
     * first because a portal room is where this command is most likely to be pressed from.</p>
     *
     * @return true if a session was actually unwound
     */
    private static boolean exitEditorSession(ServerPlayer player, MinecraftServer server) {
        boolean exited = PortalRoomEditor.exit(player)
            || TunnelEditor.exit(player)
            || CarriageEditor.exit(player);
        if (!exited) return false;

        ServerLevel overworld = server.overworld();
        EditorCategory.clearAllPlots(overworld, DungeonTrainWorldData.get(overworld).dims());
        LOGGER.info("[DungeonTrain] portal test: unwound {}'s editor session before re-seeding",
            player.getName().getString());
        return true;
    }
}
