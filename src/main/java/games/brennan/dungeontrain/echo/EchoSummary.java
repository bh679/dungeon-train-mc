package games.brennan.dungeontrain.echo;

import games.brennan.dungeontrain.player.PlayerMobAppearance;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The echo this death left behind, condensed for the death screen's echo page: its look (skin,
 * name and the six worn/held stacks), its disposition traits (fight/flight, friendliness, and
 * reaction speed overall plus per {@link EchoPersonality.Skill}), the backpack it carries, and the
 * armour points its worn gear gives, and its two hobby scores on the skill graph ({@link
 * EchoPersonality.Hobby}: reading and trading, 0–10). Read server-side from PlayerMob's death record by
 * {@link EchoSummaryReader} and carried in {@code DeathStatsPacket}.
 *
 * <p>The carried list holds at most {@link #MAX_CARRIED} non-empty stacks — PlayerMob's backpack
 * size — and is defensively copied so the record stays immutable.</p>
 */
public record EchoSummary(
        PlayerMobAppearance appearance,
        int fightFlight,
        int friendliness,
        int reactionSpeed,
        List<Integer> skills,
        List<ItemStack> carried,
        int armour,
        int reading,
        int trading) {

    /** PlayerMob's backpack size ({@code PlayerMobEntity.INVENTORY_SIZE}). */
    public static final int MAX_CARRIED = 8;

    public EchoSummary {
        fightFlight = EchoPersonality.clamp(fightFlight);
        friendliness = EchoPersonality.clamp(friendliness);
        reactionSpeed = EchoPersonality.clamp(reactionSpeed);
        skills = clampSkills(skills, reactionSpeed);
        carried = List.copyOf(carried.size() > MAX_CARRIED ? carried.subList(0, MAX_CARRIED) : carried);
        armour = Math.max(0, armour);
        reading = EchoPersonality.clamp(reading);
        trading = EchoPersonality.clamp(trading);
    }

    /**
     * One reaction speed per {@link EchoPersonality.Skill}, clamped; a missing entry takes the
     * overall speed (as PlayerMob does for a skill with no value of its own).
     */
    private static List<Integer> clampSkills(List<Integer> skills, int overall) {
        int n = EchoPersonality.Skill.values().length;
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Integer v = i < skills.size() ? skills.get(i) : null;
            out.add(v == null ? overall : EchoPersonality.clamp(v));
        }
        return List.copyOf(out);
    }

    /** This echo's reaction speed for {@code skill}. */
    public int skill(EchoPersonality.Skill skill) {
        return skills.get(skill.ordinal());
    }

    /** This echo's score for {@code hobby}. */
    public int hobby(EchoPersonality.Hobby hobby) {
        return switch (hobby) {
            case READING -> reading;
            case TRADING -> trading;
        };
    }

    /** This echo's personality class. */
    public EchoPersonality.Kind personality() {
        return EchoPersonality.classify(fightFlight, friendliness);
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        appearance.encode(buf);
        buf.writeVarInt(fightFlight);
        buf.writeVarInt(friendliness);
        buf.writeVarInt(reactionSpeed);
        for (int v : skills) {
            buf.writeVarInt(v);
        }
        buf.writeVarInt(carried.size());
        for (ItemStack stack : carried) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }
        buf.writeVarInt(armour);
        buf.writeVarInt(reading);
        buf.writeVarInt(trading);
    }

    public static EchoSummary decode(RegistryFriendlyByteBuf buf) {
        PlayerMobAppearance appearance = PlayerMobAppearance.decode(buf);
        int fightFlight = buf.readVarInt();
        int friendliness = buf.readVarInt();
        int reactionSpeed = buf.readVarInt();
        List<Integer> skills = new ArrayList<>();
        for (int i = 0; i < EchoPersonality.Skill.values().length; i++) {
            skills.add(buf.readVarInt());
        }
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_CARRIED) {
            throw new IllegalArgumentException("EchoSummary: bad carried count " + count);
        }
        List<ItemStack> carried = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            carried.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        int armour = buf.readVarInt();
        int reading = buf.readVarInt();
        int trading = buf.readVarInt();
        return new EchoSummary(appearance, fightFlight, friendliness, reactionSpeed, skills, carried, armour,
                reading, trading);
    }
}
