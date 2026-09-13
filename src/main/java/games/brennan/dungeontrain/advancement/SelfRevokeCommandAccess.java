package games.brennan.dungeontrain.advancement;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;

/**
 * Opens exactly one vanilla command form — {@code /advancement revoke <targets> everything} — to a
 * player who {@linkplain StartAgainAdvancement#holdsBankedCapstone holds the banked capstone}, by
 * rewriting the Brigadier {@code requirement} predicates on the vanilla nodes after they are built.
 *
 * <p><b>Why the requirement, and not a hook.</b> The client's autocomplete is whatever
 * {@code Commands.sendCommands} includes, and that is decided node by node with
 * {@code CommandNode.canUse(source)} — the requirement predicate. Vanilla puts a single
 * {@code requires(hasPermission(2))} on the {@code advancement} literal and nothing below it, so
 * for a non-op the whole subtree is absent from the tree and the command never even parses. No
 * execution-time hook can make a command <em>autocomplete</em>; only the requirement can.</p>
 *
 * <p><b>Shape after the rewrite.</b> The root literal admits a capstone-holder; {@code grant} and
 * the four narrowing {@code revoke} forms ({@code only}, {@code from}, {@code until},
 * {@code through}) are pinned to permission 2, so the holder's tree is
 * {@code advancement → revoke → <targets> → everything} and nothing else. The
 * {@code <targets>} argument can still spell any selector; that is fenced by
 * {@link SelfSelectorGrant} ({@code @s} only) and by the {@code CommandEvent} guard in
 * {@code AchievementEvents} (exact form only, cancelled before the cheat detector sees it).</p>
 *
 * <p><b>Reflection, deliberately.</b> {@code CommandNode.requirement} is {@code private final}
 * in Brigadier — a library, not a Minecraft class, so it is left to plain reflection rather than a
 * mixin (the jar ships no {@code module-info}, so its packages are open). Fails soft: if the field
 * ever moves, the command simply stays op-only and the log says so.</p>
 */
public final class SelfRevokeCommandAccess {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Predicate<CommandSourceStack> OPERATOR = source -> source.hasPermission(2);

    private SelfRevokeCommandAccess() {}

    /** Rewrite the vanilla {@code advancement} tree. Called from {@code RegisterCommandsEvent}. */
    public static void open(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> advancement = dispatcher.getRoot().getChild("advancement");
        if (advancement == null) {
            LOGGER.warn("[DungeonTrain] No vanilla /advancement command to open for the self-revoke");
            return;
        }
        CommandNode<CommandSourceStack> grant = advancement.getChild("grant");
        CommandNode<CommandSourceStack> revoke = advancement.getChild("revoke");
        CommandNode<CommandSourceStack> targets = revoke == null ? null : revoke.getChild("targets");
        if (grant == null || targets == null) {
            LOGGER.warn("[DungeonTrain] Vanilla /advancement tree has an unexpected shape — leaving it op-only");
            return;
        }

        Field requirement = requirementField();
        if (requirement == null) return;

        Predicate<CommandSourceStack> vanilla = advancement.getRequirement();
        boolean ok = set(requirement, advancement, vanilla.or(SelfRevokeCommandAccess::holderMayRun));
        ok &= set(requirement, grant, OPERATOR);
        for (String narrowing : List.of("only", "from", "until", "through")) {
            CommandNode<CommandSourceStack> node = targets.getChild(narrowing);
            if (node != null) ok &= set(requirement, node, OPERATOR);
        }
        if (ok) {
            LOGGER.info("[DungeonTrain] /advancement revoke <targets> everything opened to capstone-holders");
        }
    }

    private static boolean holderMayRun(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && StartAgainAdvancement.holdsBankedCapstone(player);
    }

    private static Field requirementField() {
        try {
            Field field = CommandNode.class.getDeclaredField("requirement");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Brigadier CommandNode.requirement not reachable ({}) — "
                + "/advancement stays op-only, so It's Not That Simple is unearnable without cheats", e.toString());
            return null;
        }
    }

    private static boolean set(Field field, CommandNode<CommandSourceStack> node, Predicate<CommandSourceStack> requirement) {
        try {
            field.set(node, requirement);
            return true;
        } catch (IllegalAccessException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not rewrite requirement on '{}': {}", node.getName(), e.toString());
            return false;
        }
    }
}
