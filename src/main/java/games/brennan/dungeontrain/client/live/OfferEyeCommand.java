package games.brennan.dungeontrain.client.live;

import com.mojang.brigadier.arguments.StringArgumentType;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.List;

/**
 * {@code /offereye} while live with the Live Eye: offer it to any viewer watching through a TV who could take it,
 * not only the never-streamed viewers the relay prompts for ({@link LiveGiftClient}).
 *
 * <ul>
 *   <li>{@code /offereye} lists them (relay {@code /live/gift/viewers}), each with an [Offer] button;</li>
 *   <li>{@code /offereye <viewer>} offers it straight away (relay {@code /live/gift/offer});</li>
 *   <li>{@code /offereye all} (the list's last row, All) offers it to all of them: the first to accept gets it.</li>
 * </ul>
 *
 * <p>The viewer then sees the usual [Accept] / [No], and the hand-over is the gift's. Client side, because
 * the stream token that proves this player is on air lives here. Always in tab completion (Brennan's call):
 * NeoForge judges a client command's {@code requires} once, when it merges the tree at login, so gating it
 * on the worn Live Eye would hide it from anyone who put the eye on later. Off air it says to go live first.
 * An incomplete form NeoForge hands to the server, where {@code CommandAllowlist} keeps it from offering
 * Free Play.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class OfferEyeCommand {

    static final String COMMAND = "offereye";
    private static final String KEY = LiveGiftClient.OFFER_KEY;

    /** The last list the relay gave, for {@code <viewer>} suggestions. Client thread only. */
    private static volatile List<String> lastViewers = List.of();

    private OfferEyeCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND)
            .executes(ctx -> list())
            .then(Commands.literal("all").executes(ctx -> LiveGiftClient.offerAll()))
            .then(Commands.argument("viewer", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(lastViewers, b))
                .executes(ctx -> LiveGiftClient.offerTo(StringArgumentType.getString(ctx, "viewer")))));
    }

    private static int list() {
        Minecraft mc = Minecraft.getInstance();
        String token = LiveStreamController.get().streamToken();
        if (token == null) {
            LiveGiftClient.say(mc, Component.translatable(KEY + "not_live").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        LiveFeedClient.giftViewers(DungeonTrain.relayBaseUrl(), token).thenAcceptAsync(r -> {
            if (!r.ok()) {
                String key = r.status() == 404 ? KEY + "unavailable" : r.status() == 403 ? KEY + "not_live" : KEY + "list_failed";
                LiveGiftClient.say(mc, Component.translatable(key).withStyle(ChatFormatting.GRAY));
                return;
            }
            List<String> viewers = LiveFeedClient.parseGiftViewers(r);
            lastViewers = viewers;
            if (viewers.isEmpty()) {
                LiveGiftClient.say(mc, Component.translatable(KEY + "none").withStyle(ChatFormatting.GRAY));
                return;
            }
            LiveGiftClient.say(mc, Component.translatable(KEY + "header").withStyle(ChatFormatting.LIGHT_PURPLE));
            // Gift Live Eye perk: the per-viewer [Offer] needs it; the All row below is for everyone.
            boolean canTarget = LiveGiftClient.canOfferToOne(mc);
            for (String name : viewers) {
                MutableComponent line = Component.literal(" " + name + " ").withStyle(ChatFormatting.WHITE);
                if (canTarget) line.append(LiveGiftClient.offerButton(name));
                LiveGiftClient.say(mc, line);
            }
            if (!canTarget) {
                LiveGiftClient.say(mc, Component.translatable(KEY + "needs_perk").withStyle(ChatFormatting.GRAY));
            }
            if (viewers.size() > 1 || !canTarget) {
                LiveGiftClient.say(mc, Component.literal(" ").append(Component.translatable(KEY + "all"))
                    .append(" ").withStyle(ChatFormatting.WHITE).append(LiveGiftClient.offerAllButton()));
            }
        }, mc);
        return 1;
    }
}
