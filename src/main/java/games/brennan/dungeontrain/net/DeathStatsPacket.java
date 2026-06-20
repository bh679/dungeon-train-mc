package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DeathStatsCache;
import games.brennan.dungeontrain.player.PlayerMobAppearance;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: snapshot of the player's {@code PlayerRunState} stats at
 * the moment of death plus visual context (most-used weapon, worn armor) so
 * the death screen can render an icon row with hover tooltips.
 *
 * <p>Sent from {@code RunStatsEvents.onPlayerDeath} (LOW priority) before
 * the respawn hook clears the attachment. Cached client-side by
 * {@link DeathStatsCache} until the next death.</p>
 *
 * <p>Armor stacks are sent in head → chest → legs → feet order, each via
 * {@link ItemStack#OPTIONAL_STREAM_CODEC} so empty slots round-trip
 * correctly.</p>
 *
 * <p>The {@code life*} fields are the player's cumulative cross-world totals
 * (from {@code GlobalPlayerStats}), {@link #narrative} carries the server-rolled
 * story lines, and {@link #deathCause} is the second-person death message
 * ("You fell from a high place") shown as the fall-page title — all feed the
 * paginated narrative death screen. {@code deathCause} is empty for the
 * alive-logout snapshot (no death). Any change to this layout must bump
 * {@code DungeonTrainNet.PROTOCOL_VERSION}.</p>
 *
 * <p>{@link #side} + {@link #portrait} carry the DEEDS-page portrait subject:
 * {@code side} 0 = none, 1 = befriended (drawn left), 2 = killed (drawn right);
 * {@code portrait} is the chosen mob's {@link PlayerMobAppearance} (null when
 * {@code side == 0}). Written/read only when {@code side != 0}.</p>
 */
public record DeathStatsPacket(
        int mobKills,
        int cartsTravelled,
        double distanceBlocks,
        long runTicks,
        int containersOpened,
        int booksRead,
        ItemStack mostUsedWeapon,
        ItemStack armorHead,
        ItemStack armorChest,
        ItemStack armorLegs,
        ItemStack armorFeet,
        int playersEncountered,
        int playersKilled,
        int playersBefriended,
        double damageDealt,
        double damageTaken,
        long lifeDeaths,
        long lifeCarriages,
        double lifeDistance,
        long lifeFriends,
        long lifeBooks,
        long lifeTrainTicks,
        DeathNarrative narrative,
        String deathCause,
        byte side,
        PlayerMobAppearance portrait,
        List<ResourceLocation> earnedAdvancements
) implements CustomPacketPayload {

    public static final Type<DeathStatsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "death_stats"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeathStatsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            DeathStatsPacket::decode
        );

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(mobKills);
        buf.writeVarInt(cartsTravelled);
        buf.writeDouble(distanceBlocks);
        buf.writeVarLong(runTicks);
        buf.writeVarInt(containersOpened);
        buf.writeVarInt(booksRead);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, mostUsedWeapon);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, armorHead);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, armorChest);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, armorLegs);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, armorFeet);
        buf.writeVarInt(playersEncountered);
        buf.writeVarInt(playersKilled);
        buf.writeVarInt(playersBefriended);
        buf.writeDouble(damageDealt);
        buf.writeDouble(damageTaken);
        buf.writeVarLong(lifeDeaths);
        buf.writeVarLong(lifeCarriages);
        buf.writeDouble(lifeDistance);
        buf.writeVarLong(lifeFriends);
        buf.writeVarLong(lifeBooks);
        buf.writeVarLong(lifeTrainTicks);
        narrative.encode(buf);
        buf.writeUtf(deathCause);
        // Death-screen portrait subject (0 = none, 1 = befriended/left, 2 = killed/right).
        // Self-consistent on the wire: the appearance is written iff a non-zero byte was.
        boolean hasPortrait = side != 0 && portrait != null;
        buf.writeByte(hasPortrait ? side : 0);
        if (hasPortrait) {
            portrait.encode(buf);
        }
        // Death-screen accolades: Dungeon Train advancements earned this life.
        buf.writeVarInt(earnedAdvancements.size());
        for (ResourceLocation id : earnedAdvancements) {
            buf.writeResourceLocation(id);
        }
    }

    public static DeathStatsPacket decode(RegistryFriendlyByteBuf buf) {
        int mobKills = buf.readVarInt();
        int cartsTravelled = buf.readVarInt();
        double distanceBlocks = buf.readDouble();
        long runTicks = buf.readVarLong();
        int containersOpened = buf.readVarInt();
        int booksRead = buf.readVarInt();
        ItemStack weapon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack head = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack chest = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack legs = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack feet = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        int playersEncountered = buf.readVarInt();
        int playersKilled = buf.readVarInt();
        int playersBefriended = buf.readVarInt();
        double damageDealt = buf.readDouble();
        double damageTaken = buf.readDouble();
        long lifeDeaths = buf.readVarLong();
        long lifeCarriages = buf.readVarLong();
        double lifeDistance = buf.readDouble();
        long lifeFriends = buf.readVarLong();
        long lifeBooks = buf.readVarLong();
        long lifeTrainTicks = buf.readVarLong();
        DeathNarrative narrative = DeathNarrative.decode(buf);
        String deathCause = buf.readUtf();
        byte side = buf.readByte();
        PlayerMobAppearance portrait = null;
        if (side != 0) {
            portrait = PlayerMobAppearance.decode(buf);
        }
        int advCount = buf.readVarInt();
        List<ResourceLocation> earnedAdvancements = new ArrayList<>(advCount);
        for (int i = 0; i < advCount; i++) {
            earnedAdvancements.add(buf.readResourceLocation());
        }
        return new DeathStatsPacket(mobKills, cartsTravelled, distanceBlocks, runTicks,
                containersOpened, booksRead, weapon, head, chest, legs, feet,
                playersEncountered, playersKilled, playersBefriended, damageDealt, damageTaken,
                lifeDeaths, lifeCarriages, lifeDistance, lifeFriends, lifeBooks, lifeTrainTicks,
                narrative, deathCause, side, portrait, earnedAdvancements);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DeathStatsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DeathStatsCache.set(packet));
    }
}
