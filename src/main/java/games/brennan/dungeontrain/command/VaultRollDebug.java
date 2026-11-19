package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.ContainerContentsEnchants;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import games.brennan.dungeontrain.editor.VaultLoot;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /dungeontrain debug vault-roll <trial|ominous|green> [times]}: rolls a vault type's default
 * reward {@code times} times, as {@code times} different players unlocking it, and logs every item
 * (enchanted books with their stored enchantments). Chat gets a per-item tally. For checking a vault
 * pool's odds and contents on a real server, where enchantment tags are bound.
 */
final class VaultRollDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_TIMES = 20;
    private static final int MAX_TIMES = 2000;

    private VaultRollDebug() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("vault-roll")
            .then(Commands.literal("enchants").executes(ctx -> listEnchants(ctx.getSource())))
            .then(type("trial", ContainerContentsPool.DEFAULT_VAULT_KEY))
            .then(type("ominous", VaultLoot.OMINOUS_KEY))
            .then(type("green", VaultLoot.GREEN_KEY));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> type(String name, String key) {
        return Commands.literal(name)
            .executes(ctx -> roll(ctx.getSource(), name, key, DEFAULT_TIMES))
            .then(Commands.argument("times", IntegerArgumentType.integer(1, MAX_TIMES))
                .executes(ctx -> roll(ctx.getSource(), name, key, IntegerArgumentType.getInteger(ctx, "times"))));
    }

    /**
     * Every enchantment a book's random roll could pick on this server, with the default on/off
     * (curses and Mending off), as one {@code [DT-vault-roll] enchants} log line per id: the list a
     * bundled pool's {@code "list"} should hold.
     */
    private static int listEnchants(CommandSourceStack source) {
        List<Holder<Enchantment>> all = source.getLevel().registryAccess().lookup(Registries.ENCHANTMENT)
            .map(ContainerContentsEnchants::listCandidates).orElse(List.of());
        for (Holder<Enchantment> holder : all) {
            LOGGER.info("[DT-vault-roll] enchants {} {}", holder.unwrapKey().map(k -> k.location().toString()).orElse("?"),
                ContainerContentsEnchants.onByDefault(holder));
        }
        source.sendSuccess(() -> Component.literal("[DungeonTrain] " + all.size() + " book enchantments logged")
            .withStyle(ChatFormatting.GRAY), false);
        return all.size();
    }

    private static int roll(CommandSourceStack source, String name, String key, int times) {
        ContainerContentsPool pool = VaultLoot.defaultPool(key);
        long worldSeed = source.getLevel().getSeed();
        Map<String, Integer> tally = new TreeMap<>();
        for (int i = 0; i < times; i++) {
            List<ItemStack> reward = ContainerContentsRoller.rollStacks(pool, VaultLoot.VAULT_SLOTS,
                BlockPos.ZERO, worldSeed ^ (0x9E3779B97F4A7C15L * (i + 1)), 0, 0, true,
                source.getLevel().registryAccess());
            StringBuilder line = new StringBuilder();
            for (ItemStack stack : reward) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                tally.merge(id, 1, Integer::sum);
                line.append(stack.getCount()).append('x').append(id);
                ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
                if (stored != null && !stored.isEmpty()) {
                    line.append(" {");
                    stored.entrySet().forEach(e -> line.append(e.getKey().unwrapKey()
                        .map(k -> k.location().getPath()).orElse("?")).append(' ').append(e.getIntValue()).append(' '));
                    line.setLength(line.length() - 1);
                    line.append('}');
                }
                line.append(", ");
            }
            LOGGER.info("[DT-vault-roll] {} #{}: {}", name, i + 1, line);
        }
        source.sendSuccess(() -> Component.literal("[DungeonTrain] " + name + " vault x" + times + ": " + tally)
            .withStyle(ChatFormatting.GRAY), false);
        return times;
    }
}
