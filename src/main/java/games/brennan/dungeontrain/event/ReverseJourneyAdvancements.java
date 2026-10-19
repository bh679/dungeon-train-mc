package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.worldgen.ReverseSlide;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grants The Secrete Menu tab — the reverse journey behind spawn — from {@link ReverseSlide}'s scan,
 * right after it has moved the slide, and only to the players it counted. The reversed bands sit as far
 * back as players have earned by riding, so testing anyone the slide ignored (a creative player off the
 * train on a server, someone still in the intro cinematic or in a portal twin's sealed space), or testing
 * before the slide catches up with a long jump back, would hand out bands nobody rode to.
 *
 * <p>Each reverse advancement also needs the one before it on the chain
 * ({@link BandAdvancements#reversePredecessor}) — the first needs The Secrete Menu itself — so the tab
 * can only be earned in the order it is drawn.</p>
 */
public final class ReverseJourneyAdvancements {

    private ReverseJourneyAdvancements() {}

    /** Every counted player behind spawn: The Secrete Menu, then each reversed band in chain order, then tfarcenim. */
    public static void scan(ServerLevel level, WorldGenCycle cycle, List<ServerPlayer> counted) {
        if (counted.isEmpty()) return;
        List<String> chain = BandAdvancements.reverseChain(cycle.layout());
        Map<String, BandAdvancements.Trigger> triggers = new HashMap<>();
        for (BandAdvancements.Trigger t : BandAdvancements.reverseTriggers()) triggers.put(t.id(), t);

        for (ServerPlayer player : counted) {
            int px = player.getBlockX();
            if (!cycle.isMirroredAt(px)) continue;
            checkSecreteMenu(level, cycle, player, px);
            // Walking back, the player enters each band from its spawn (+X) side.
            for (String id : chain) {
                BandAdvancements.Trigger t = triggers.get(id);
                if (t == null) continue;
                if (t.test().test(level, px) && t.test().test(level, px + t.depth())) {
                    grantAfterPredecessor(level, cycle, player, id);
                }
            }
            if (BandAdvancements.isPastReverseJourney(level, px)) {
                grantAfterPredecessor(level, cycle, player, BandAdvancements.TFARCENIM);
            }
        }
    }

    /**
     * "The Secrete Menu" (its tab root and its Dungeon Train-tab twin share the action): ridden
     * {@link BandAdvancements#SECRETE_MENU_BLOCKS} blocks behind spawn while on the train.
     */
    private static void checkSecreteMenu(ServerLevel level, WorldGenCycle cycle, ServerPlayer player, int px) {
        if (px > cycle.startX() - BandAdvancements.SECRETE_MENU_BLOCKS) return;
        if (ReverseSlide.riddenBehindSpawn(level, cycle.startX(), player) < BandAdvancements.SECRETE_MENU_BLOCKS) return;
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, BandAdvancements.SECRETE_MENU);
    }

    private static void grantAfterPredecessor(ServerLevel level, WorldGenCycle cycle, ServerPlayer player, String id) {
        if (!hasEarned(level, player, BandAdvancements.reversePredecessor(cycle.layout(), id))) return;
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, id);
    }

    /**
     * True when {@code player} holds {@code secrete_menu/<id>}. An advancement that isn't loaded (the relay
     * disabled it) can't be earned, so it never blocks the one after it.
     */
    private static boolean hasEarned(ServerLevel level, ServerPlayer player, String id) {
        if (id == null) return true;
        AdvancementHolder holder = level.getServer().getAdvancements().get(ResourceLocation.fromNamespaceAndPath(
                DungeonTrain.MOD_ID, BandAdvancements.SECRETE_MENU_PREFIX + id));
        return holder == null || player.getAdvancements().getOrStartProgress(holder).isDone();
    }
}
