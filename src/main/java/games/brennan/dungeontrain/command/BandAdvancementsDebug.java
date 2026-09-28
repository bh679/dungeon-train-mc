package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BandAdvancementChainRewriter;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;

/**
 * {@code /dungeontrain debug band-advancements} — the journey advancement chain in the order the
 * current {@link WorldGenCycle} layout chains it, beside the parent each advancement actually carries
 * in the loaded advancement tree (so a mismatch between the table and the datapack rewrite shows as
 * {@code MISMATCH}). Logged at INFO as well so a headless RCON run can read it from {@code latest.log}.
 */
final class BandAdvancementsDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BandAdvancementsDebug() {}

    static int report(CommandSourceStack source) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        int mismatches = reportChain(source, cycle, "journey", BandAdvancements.chain(cycle.layout()),
                BandAdvancements.ANCHOR, BandAdvancementChainRewriter.PATH_PREFIX);
        mismatches += reportChain(source, cycle, "reverse journey (The Secrete Menu)",
                BandAdvancements.reverseChain(cycle.layout()), BandAdvancements.REVERSE_ANCHOR,
                BandAdvancements.SECRETE_MENU_PREFIX);
        send(source, "[DungeonTrain] band-advancements: " + mismatches + " mismatch(es)",
                mismatches == 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
        return 1;
    }

    /** Lists one chain with each member's expected vs loaded parent; returns the mismatch count. */
    private static int reportChain(CommandSourceStack source, WorldGenCycle cycle, String label, List<String> chain,
                                   String anchor, String pathPrefix) {
        send(source, "[DungeonTrain] band-advancements " + label + ": " + chain.size() + " in chain, "
                + (cycle.hasLayout() ? "layout order" : "classic order (no layout — JSON parents apply)"),
                ChatFormatting.AQUA);
        String expected = anchor;
        int mismatches = 0;
        for (int i = 0; i < chain.size(); i++) {
            String name = chain.get(i);
            String loaded = loadedParent(source, name, pathPrefix);
            boolean ok = !cycle.hasLayout() || loaded == null || loaded.equals(expected);
            if (!ok) mismatches++;
            send(source, String.format("  %2d %-26s parent=%-26s loaded=%s%s", i + 1, name, expected,
                    loaded == null ? "(not loaded)" : loaded, ok ? "" : "  MISMATCH"),
                    ok ? ChatFormatting.WHITE : ChatFormatting.RED);
            expected = name;
        }
        return mismatches;
    }

    /**
     * {@code /dungeontrain debug band-advancements at <x>} — every trigger's column test at world-X
     * {@code x} and at {@code x - depth}: which advancements a player standing there would be granted.
     * Behind spawn it probes the reverse triggers instead, at {@code x + depth}.
     */
    static int probe(CommandSourceStack source, int worldX) {
        var overworld = source.getServer().overworld();
        int grants = 0;
        // Behind spawn only the reverse journey is earned, its depth measured towards +X.
        boolean reverse = WorldGenCycle.fromConfig().isMirroredAt(worldX);
        int sign = reverse ? 1 : -1;
        for (BandAdvancements.Trigger t : reverse ? BandAdvancements.reverseTriggers() : BandAdvancements.triggers()) {
            boolean here = t.test().test(overworld, worldX);
            boolean behind = t.test().test(overworld, worldX + sign * t.depth());
            if (here && behind) grants++;
            if (here || behind) {
                send(source, String.format("  %-26s at X=%d: %s, X%+d: %s%s", t.id(), worldX, here, sign * t.depth(),
                        behind, here && behind ? "  -> GRANT" : ""), here && behind ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            }
        }
        send(source, "[DungeonTrain] band-advancements at X=" + worldX + ": " + grants + " would be granted",
                ChatFormatting.AQUA);
        return 1;
    }

    /** Short name of the parent the loaded tree holds for {@code name}, or {@code null} if it is not loaded. */
    private static String loadedParent(CommandSourceStack source, String name, String pathPrefix) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, pathPrefix + name);
        AdvancementHolder holder = source.getServer().getAdvancements().get(id);
        if (holder == null) return null;
        return holder.value().parent()
                .map(p -> p.getPath().startsWith(pathPrefix) ? p.getPath().substring(pathPrefix.length()) : p.toString())
                .orElse("(root)");
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
