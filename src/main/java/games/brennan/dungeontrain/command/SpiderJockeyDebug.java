package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.VariantRotation;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /dungeontrain debug spider-jockey}: spawns plain spiders through the real carriage
 * variant-mob path ({@link CarriageContentsPlacer#spawnVariantMobEntity}) at the cell the player
 * stands on, until vanilla's 1-in-100 skeleton jockey roll hits, and reports whether the rider made
 * it into the level with the carriage contents tag. Riderless spiders are discarded. Regression probe
 * for the ghost-passenger bug: a plain {@code addFreshEntity} left the skeleton out of the level.
 */
final class SpiderJockeyDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_TRIES = 1000;
    private static final ResourceLocation SPIDER = ResourceLocation.withDefaultNamespace("spider");

    private SpiderJockeyDebug() {}

    static int run(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            send(source, "[DungeonTrain] spider-jockey: run as a player standing in a carriage", ChatFormatting.RED);
            return 0;
        }
        ServerLevel level = player.serverLevel();
        List<Trains.Carriage> carriages = new ArrayList<>();
        Trains.byTrainId(level).values().forEach(carriages::addAll);
        Trains.Carriage carriage = CarriageDeck.carriageUnder(carriages, player);
        if (carriage == null) {
            send(source, "[DungeonTrain] spider-jockey: not standing on a carriage", ChatFormatting.RED);
            return 0;
        }
        int pIdx = carriage.provider().getPIdx();
        BlockPos cell = CarriageDeck.shipLocal(carriage.ship(), player.blockPosition());
        VariantState spider = new VariantState(Blocks.AIR.defaultBlockState(), null, 1,
            VariantRotation.NONE, null, SPIDER);

        for (int i = 1; i <= MAX_TRIES; i++) {
            Entity spawned = CarriageContentsPlacer.spawnVariantMobEntity(
                level, cell, spider, pIdx, level.getSeed(), /*asAuthored*/ true);
            if (spawned == null) {
                send(source, "[DungeonTrain] spider-jockey: spawn rejected on try " + i, ChatFormatting.RED);
                return 0;
            }
            if (spawned.getPassengers().isEmpty()) {
                spawned.discard();
                continue;
            }
            for (Entity rider : spawned.getPassengers()) {
                boolean ok = rider.isAddedToLevel() && level.getEntity(rider.getUUID()) == rider
                    && rider.getTags().contains(CarriageContentsPlacer.contentsTagFor(pIdx));
                send(source, "[DungeonTrain] spider-jockey: try " + i + " pIdx=" + pIdx
                        + " rider=" + rider.getType().getDescriptionId() + " uuid=" + rider.getUUID()
                        + " inLevel=" + rider.isAddedToLevel() + " tags=" + rider.getTags()
                        + (ok ? " OK" : " GHOST"),
                    ok ? ChatFormatting.GREEN : ChatFormatting.RED);
            }
            return 1;
        }
        send(source, "[DungeonTrain] spider-jockey: no jockey in " + MAX_TRIES + " tries", ChatFormatting.YELLOW);
        return 0;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
