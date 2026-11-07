package games.brennan.dungeontrain.loot;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.VaultLoot;
import games.brennan.dungeontrain.editor.VaultRarity;
import games.brennan.dungeontrain.net.relay.VaultFindClient;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rare vault finds ({@link VaultRarity}). When a DT vault rolls one for a player, the relay numbers it
 * per item ({@link VaultFindClient}): the first ever is told so in chat, and a find from an ominous or
 * green vault carries its story in its description, "Vault find #3" over "Pulled by Steve, 2026-11-07".
 *
 * <p>The reward is ejected before the relay can answer, so the stack is tagged with its claim id and
 * the "Pulled by" line at once; the number follows when it comes ({@link VaultFindSerials}), stamped
 * at the eject or by {@code VaultFindEvents}' sweep of online players' inventories. No answer (relay
 * down, Free Play, relay reports off) means no first and no number, never a guessed one.</p>
 *
 * <p>Cosmetic, so every entry point is no-throw: a failure leaves the reward exactly as rolled.</p>
 */
public final class VaultFinds {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code CUSTOM_DATA} key: {@code {claim: uuid, serial?: int}}. */
    static final String TAG = "dungeontrain_vault_find";
    static final String TAG_CLAIM = "claim";
    static final String TAG_SERIAL = "serial";

    static final String CHAT_FIRST = "chat.dungeontrain.vault_first";
    static final String LORE_SERIAL = "lore.dungeontrain.vault_find.serial";
    static final String LORE_PULLED_BY = "lore.dungeontrain.vault_find.pulled_by";

    private VaultFinds() {}

    /**
     * Server thread, from {@code VaultServerMixin}: {@code rewards} are what the vault at {@code pos}
     * just rolled for {@code player} and is about to eject. Vanilla-table vaults (no DT pool) take no part.
     */
    public static void onRewards(ServerLevel level, BlockPos pos, BlockState state, Player player,
                                 List<ItemStack> rewards) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            if (!VaultLoot.hasPool(level.getBlockEntity(pos))) return;
            VaultRarity.Kind kind = VaultRarity.kindOf(state);
            for (ItemStack stack : rewards) {
                if (stack.isEmpty()) continue;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (VaultRarity.isRare(id)) find(serverPlayer, kind, id, stack);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] vault find failed: {}", t.toString());
        }
    }

    private static void find(ServerPlayer player, VaultRarity.Kind kind, ResourceLocation id, ItemStack stack) {
        UUID claim = UUID.randomUUID();
        String name = player.getGameProfile().getName();
        if (kind.numbered()) {
            // A plain vault's find only counts: it is left exactly as rolled.
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(TAG, claimTag(claim)));
            String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE);
            writeLore(stack, withLine(lore(stack), -1, styled(Component.translatable(LORE_PULLED_BY, name, date))));
        }
        if (!DungeonTrainConfig.isWorldInfoToRelay()) return;   // the relay is what counts; no relay, no number
        if (RunIntegrity.isCheated(player)) return;            // Free Play finds are not feats
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID who = player.getUUID();
        Item item = stack.getItem();
        VaultFindClient.claim(claim, who, name, id.toString(), kind.wire(),
            answer -> server.execute(() -> onAnswer(server, who, item, kind, claim, answer)));
    }

    /** Back on the server thread with the relay's answer. */
    private static void onAnswer(MinecraftServer server, UUID who, Item item, VaultRarity.Kind kind, UUID claim,
                                 VaultFindClient.Answer answer) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(who);
            if (answer.first() && player != null) {
                player.sendSystemMessage(Component.translatable(CHAT_FIRST, item.getDescription())
                    .withStyle(ChatFormatting.GOLD));
            }
            LOGGER.info("[DungeonTrain] vault find {} #{} for {}{}", BuiltInRegistries.ITEM.getKey(item),
                answer.serial(), who, answer.first() ? " (first ever)" : "");
            if (!kind.numbered()) return;
            VaultFindSerials.get(server).put(claim, answer.serial());
            VaultFindStamper.sweep(server);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] vault find answer failed: {}", t.toString());
        }
    }

    /** The claim waiting for a number on {@code stack}; empty when it has none or is already numbered. */
    static Optional<UUID> pendingClaim(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(TAG)) return Optional.empty();
        CompoundTag find = data.copyTag().getCompound(TAG);
        if (!find.hasUUID(TAG_CLAIM) || find.contains(TAG_SERIAL)) return Optional.empty();
        return Optional.of(find.getUUID(TAG_CLAIM));
    }

    /** Write {@code serial} onto {@code stack}: its number line goes above the "Pulled by" line. */
    static void stamp(ItemStack stack, int serial) {
        List<Component> lines = lore(stack);
        int pulledBy = indexOf(lines, LORE_PULLED_BY);
        writeLore(stack, withLine(lines, pulledBy, styled(Component.translatable(LORE_SERIAL, serial))));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag find = tag.getCompound(TAG).copy();
            find.putInt(TAG_SERIAL, serial);
            tag.put(TAG, find);
        });
    }

    private static CompoundTag claimTag(UUID claim) {
        CompoundTag find = new CompoundTag();
        find.putUUID(TAG_CLAIM, claim);
        return find;
    }

    private static List<Component> lore(ItemStack stack) {
        return stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();
    }

    /** A copy of {@code lines} with {@code line} inserted at {@code index}, or appended when it is -1. */
    static List<Component> withLine(List<Component> lines, int index, Component line) {
        List<Component> out = new ArrayList<>(lines);
        if (index < 0) out.add(line);
        else out.add(index, line);
        return List.copyOf(out);
    }

    /** Index of the first line translated from {@code key}, or -1. */
    static int indexOf(List<Component> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getContents() instanceof TranslatableContents t && key.equals(t.getKey())) return i;
        }
        return -1;
    }

    private static void writeLore(ItemStack stack, List<Component> lines) {
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    /** Same colour AIN gives its "Crafted by …" lines, so provenance reads as one family (see EchoDropCredit). */
    private static Component styled(MutableComponent line) {
        Optional<ChatFormatting> color = NamingConfig.colorFor(ChanceKind.CRAFTED_DESCRIPTION);
        return color.isPresent() ? line.withStyle(color.get()) : line;
    }
}
