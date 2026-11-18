package games.brennan.dungeontrain.echo;

import games.brennan.dungeontrain.player.PlayerMobAppearance;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EchoSummaryCodecTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static PlayerMobAppearance appearance() {
        return new PlayerMobAppearance(3, "", true, "Steve",
                new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD),
                new ItemStack(Items.IRON_HELMET), ItemStack.EMPTY,
                new ItemStack(Items.LEATHER_LEGGINGS), ItemStack.EMPTY);
    }

    @Test
    @DisplayName("an echo survives the wire: look, traits, backpack in order, armour")
    void roundTrip() {
        EchoSummary original = new EchoSummary(appearance(), 7, 2,
                List.of(new ItemStack(Items.BREAD, 5), new ItemStack(Items.TORCH, 12)), 9);
        RegistryFriendlyByteBuf buf = buf();
        original.encode(buf);
        EchoSummary decoded = EchoSummary.decode(buf);

        assertEquals(7, decoded.fightFlight());
        assertEquals(2, decoded.friendliness());
        assertEquals(EchoPersonality.Kind.BRUTE, decoded.personality());
        assertEquals(9, decoded.armour());
        assertEquals(2, decoded.carried().size());
        assertTrue(ItemStack.matches(new ItemStack(Items.BREAD, 5), decoded.carried().get(0)));
        assertTrue(ItemStack.matches(new ItemStack(Items.TORCH, 12), decoded.carried().get(1)));
        assertTrue(ItemStack.matches(new ItemStack(Items.IRON_SWORD), decoded.appearance().mainHand()));
        assertTrue(ItemStack.matches(new ItemStack(Items.SHIELD), decoded.appearance().offHand()));
        assertEquals("Steve", decoded.appearance().name());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    @DisplayName("the backpack is capped at PlayerMob's 8 slots and traits clamp to 0–10")
    void capsAndClamps() {
        List<ItemStack> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(new ItemStack(Items.DIRT, i + 1));
        EchoSummary s = new EchoSummary(appearance(), 15, -4, many, -2);
        assertEquals(EchoSummary.MAX_CARRIED, s.carried().size());
        assertEquals(10, s.fightFlight());
        assertEquals(0, s.friendliness());
        assertEquals(0, s.armour());
    }

    private static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
