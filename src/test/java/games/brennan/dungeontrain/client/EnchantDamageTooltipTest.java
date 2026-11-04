package games.brennan.dungeontrain.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Pins the tooltip to the amounts in vanilla's own enchantment data, read from the Minecraft jar. */
class EnchantDamageTooltipTest {

    private static final String DAMAGE_NAME = "attribute.name.generic.attack_damage";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sharpnessIsUnconditional() {
        List<ConditionalEffect<EnchantmentValueEffect>> effects = damageEffects("sharpness");
        assertEquals(1F, EnchantDamageTooltip.damageBonus(effects, 1, false));
        assertEquals(2F, EnchantDamageTooltip.damageBonus(effects, 3, false));
        assertEquals(3F, EnchantDamageTooltip.damageBonus(effects, 5, false));
        assertEquals(0F, EnchantDamageTooltip.damageBonus(effects, 5, true));
    }

    @Test
    void targetedEnchantsAreConditional() {
        for (String id : List.of("smite", "bane_of_arthropods", "impaling")) {
            List<ConditionalEffect<EnchantmentValueEffect>> effects = damageEffects(id);
            assertEquals(7.5F, EnchantDamageTooltip.damageBonus(effects, 3, true), id);
            assertEquals(0F, EnchantDamageTooltip.damageBonus(effects, 3, false), id);
        }
    }

    @Test
    void findsTheBaseDamageLine() {
        Component base = Component.literal(" ").append(
                Component.translatable("attribute.modifier.equals.0", "6", Component.translatable(DAMAGE_NAME)));
        List<Component> lines = List.of(Component.literal("Iron Sword"), Component.empty(), base);
        assertEquals(2, EnchantDamageTooltip.baseDamageLine(lines, DAMAGE_NAME));
    }

    @Test
    void ignoresOtherLines() {
        Component speed = Component.translatable("attribute.modifier.equals.0", "1.6",
                Component.translatable("attribute.name.generic.attack_speed"));
        Component extraDamage = Component.translatable("attribute.modifier.plus.0", "2",
                Component.translatable(DAMAGE_NAME));
        assertEquals(-1, EnchantDamageTooltip.baseDamageLine(List.of(speed, extraDamage), DAMAGE_NAME));
    }

    /** The {@code minecraft:damage} effects of a vanilla enchantment, with only the parts the tooltip reads. */
    private static List<ConditionalEffect<EnchantmentValueEffect>> damageEffects(String id) {
        String path = "/data/minecraft/enchantment/" + id + ".json";
        try (InputStream in = Bootstrap.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return root.getAsJsonObject("effects").getAsJsonArray("minecraft:damage").asList().stream()
                    .map(EnchantDamageTooltipTest::toEffect)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ConditionalEffect<EnchantmentValueEffect> toEffect(JsonElement json) {
        JsonObject entry = json.getAsJsonObject();
        EnchantmentValueEffect effect = EnchantmentValueEffect.CODEC
                .parse(JsonOps.INSTANCE, entry.get("effect"))
                .getOrThrow();
        // Stands in for the real condition: the tooltip only asks whether one is present.
        return new ConditionalEffect<>(effect, entry.has("requirements")
                ? Optional.of(InvertedLootItemCondition.invert(AnyOfCondition.anyOf()).build())
                : Optional.empty());
    }
}
