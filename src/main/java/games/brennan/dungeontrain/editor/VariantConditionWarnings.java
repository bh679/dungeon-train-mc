package games.brennan.dungeontrain.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Tells the author, when a template is saved, about variant conditions {@link VariantConditionCheck}
 * thinks are wrong. Advisory only: the save has already gone through.
 */
public final class VariantConditionWarnings {

    private static final String KEY = "chat.dungeontrain.editor_bar.variant_check.";
    /** Lines shown before the rest are folded into "+N more". */
    private static final int MAX_LINES = 8;

    private VariantConditionWarnings() {}

    public static void afterSave(ServerPlayer player, String templateName,
                                 List<CarriageVariantBlocks.Entry> entries, ToIntFunction<BlockPos> lockIdOf) {
        List<VariantConditionCheck.Problem> problems =
            VariantConditionCheck.check(VariantConditionCheck.fromEntries(entries, lockIdOf));
        if (problems.isEmpty()) return;
        player.sendSystemMessage(Component.translatable(KEY + "header", templateName).withStyle(ChatFormatting.YELLOW));
        for (int i = 0; i < Math.min(MAX_LINES, problems.size()); i++) {
            player.sendSystemMessage(line(problems.get(i)).withStyle(ChatFormatting.YELLOW));
        }
        if (problems.size() > MAX_LINES) {
            player.sendSystemMessage(Component.translatable(KEY + "more", problems.size() - MAX_LINES)
                .withStyle(ChatFormatting.YELLOW));
        }
    }

    private static net.minecraft.network.chat.MutableComponent line(VariantConditionCheck.Problem p) {
        String at = cell(p.pos());
        return switch (p.kind()) {
            case MISSING_GROUP -> Component.translatable(KEY + "missing_group", at, p.group());
            case DETACHED_GROUP -> Component.translatable(KEY + "detached", at, p.group(), p.otherGroup());
            case UNGATED -> Component.translatable(KEY + "ungated", at, p.group());
            case LOOP -> {
                List<String> cells = new ArrayList<>();
                for (BlockPos c : p.loop()) cells.add(cell(c));
                yield Component.translatable(KEY + "loop", String.join(" > ", cells));
            }
        };
    }

    private static String cell(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
