package games.brennan.dungeontrain.advancement;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-life / single-life split. Driven through the pure overload with stub triggers, so it needs no
 * registered trigger registry — the rule under test is "does any criterion use a per-life trigger, or is this
 * one of the code-granted single-life ids", and that is decidable without Minecraft running.
 */
class AdvancementScopeTest {

    /** A stub trigger; identity is all the classifier compares. */
    private static CriterionTrigger<?> trigger() {
        return new CriterionTrigger<>() {
            @Override
            public void addPlayerListener(net.minecraft.server.PlayerAdvancements playerAdvancements,
                                         Listener<CriterionTriggerInstance> listener) {}

            @Override
            public void removePlayerListener(net.minecraft.server.PlayerAdvancements playerAdvancements,
                                            Listener<CriterionTriggerInstance> listener) {}

            @Override
            public void removePlayerListeners(net.minecraft.server.PlayerAdvancements playerAdvancements) {}

            @Override
            public com.mojang.serialization.Codec<CriterionTriggerInstance> codec() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final CriterionTrigger<?> PER_LIFE = trigger();
    private static final CriterionTrigger<?> CROSS_LIFE = trigger();
    private static final ResourceLocation CODE_GRANTED_PER_LIFE =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/pacifist_100");

    private static AdvancementHolder holder(String path, CriterionTrigger<?>... triggers) {
        Map<String, Criterion<?>> criteria = new java.util.LinkedHashMap<>();
        int i = 0;
        for (CriterionTrigger<?> t : triggers) {
            criteria.put("c" + i++, new Criterion<>(cast(t), null));
        }
        Advancement advancement = new Advancement(Optional.empty(), Optional.empty(),
            net.minecraft.advancements.AdvancementRewards.EMPTY, criteria,
            new AdvancementRequirements(List.of()), false);
        return new AdvancementHolder(ResourceLocation.fromNamespaceAndPath("dungeontrain", path), advancement);
    }

    @SuppressWarnings("unchecked")
    private static CriterionTrigger<CriterionTriggerInstance> cast(CriterionTrigger<?> t) {
        return (CriterionTrigger<CriterionTriggerInstance>) t;
    }

    private static boolean isPerLife(AdvancementHolder h) {
        return AdvancementScope.isPerLife(h, Set.of(PER_LIFE), Set.of(CODE_GRANTED_PER_LIFE));
    }

    @Test
    void aCriterionOnAPerLifeTriggerMakesItSingleLife() {
        assertTrue(isPerLife(holder("dungeon_train/carts_100", PER_LIFE)));
    }

    @Test
    void cumulativeAndOneOffAdvancementsAreCrossLife() {
        assertFalse(isPerLife(holder("dungeon_train/going_the_distance", CROSS_LIFE)));
        assertFalse(isPerLife(holder("dungeon_train/some_one_off_deed", CROSS_LIFE, CROSS_LIFE)));
    }

    @Test
    void anyPerLifeCriterionIsEnough() {
        // A mixed advancement is single-life: it can only be completed inside one life either way.
        assertTrue(isPerLife(holder("dungeon_train/mixed", CROSS_LIFE, PER_LIFE)));
    }

    @Test
    void codeGrantedSingleLifeIdsAreNamedExplicitly() {
        // minecraft:impossible says nothing about scope, so the pacifist chain has to be listed. Its stub
        // here carries a cross-life trigger to prove the ID is what classifies it.
        assertTrue(isPerLife(holder("dungeon_train/pacifist_100", CROSS_LIFE)));
        // ...and a code-granted advancement NOT on the list stays cross-life.
        assertFalse(isPerLife(holder("dungeon_train/completionist", CROSS_LIFE)));
    }

    @Test
    void theRealCodeGrantedListIsTheOneWeExpect() {
        // Guards the live list against an accidental edit: the pacifist chain and the far-start pair are
        // single-life; the lifetime deeds are not.
        for (String path : List.of("dungeon_train/pacifist_100", "dungeon_train/pacifist_250",
                "dungeon_train/pacifist_1000", "dungeon_train/the_far_start",
                "dungeon_train/the_great_beyond")) {
            assertTrue(AdvancementScope.isPerLifeId(
                    ResourceLocation.fromNamespaceAndPath("dungeontrain", path)), path);
        }
        for (String path : List.of("dungeon_train/completionist", "dungeon_train/nothing_but_books",
                "dungeon_train/leather_over_diamond")) {
            assertFalse(AdvancementScope.isPerLifeId(
                    ResourceLocation.fromNamespaceAndPath("dungeontrain", path)), path);
        }
    }

    @Test
    void aMissingHolderIsNotSingleLife() {
        assertFalse(isPerLife(null));
    }
}
