package games.brennan.dungeontrain.client.live;

import com.mojang.brigadier.arguments.StringArgumentType;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.List;

/**
 * {@code /offereye} while wearing the Live Eye: offer it to any viewer watching through a TV who could take it,
 * not only the never-streamed viewers the relay prompts for ({@link LiveGiftClient}).
 *
 * <ul>
 *   <li>{@code /offereye} lists them (relay {@code /live/gift/viewers}), each with an [Offer] button;</li>
 *   <li>{@code /offereye <viewer>} offers it straight away (relay {@code /live/gift/offer}).</li>
 * </ul>
 *
 * <p>The viewer then sees the usual [Accept] / [No], and the hand-over is the gift's. Client side, because
 * the stream token that proves this player is on air lives here. Only offered while the Live Eye is worn
 * ({@code requires}); typed otherwise, NeoForge hands it to the server, where {@code CommandAllowlist} keeps
 * it from offering Free Play. Not hidden from tab completion (Brennan's call).</p>
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
            .requires(s -> wearingLiveEye())
            .executes(ctx -> list())
            .then(Commands.argument("viewer", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(lastViewers, b))
                .executes(ctx -> LiveGiftClient.offerTo(StringArgumentType.getString(ctx, "viewer")))));
    }

    /** The Live Eye is on this client's own head. */
    static boolean wearingLiveEye() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.LIVE_HEADPIECE.get());
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
            for (String name : viewers) {
                MutableComponent line = Component.literal(" " + name + " ").withStyle(ChatFormatting.WHITE)
                    .append(LiveGiftClient.offerButton(name));
                LiveGiftClient.say(mc, line);
            }
        }, mc);
        return 1;
    }
}
