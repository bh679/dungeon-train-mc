package games.brennan.dungeontrain.client;

import com.mojang.brigadier.CommandDispatcher;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /discord link} and {@code /discord status} — the chat-command way into the existing Discord
 * link (the other is Credits → "Link your Discord", {@code credits/DiscordLinkScreen}). Both reuse
 * {@link CommunityLinkClient}: the relay mints a code, the player runs {@code /dtlink <code>} in the
 * Dungeon Train Discord, and from then on their passenger and echo logs tag them there.
 *
 * <p>Client-side: the link calls carry this client's own profile uuid and are consent-gated there,
 * so the command never touches the server (works in singleplayer and on any multiplayer server).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DiscordCommand {

    private static final String BOT_COMMAND = "/dtlink";

    private DiscordCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("discord")
            .executes(ctx -> link())
            .then(Commands.literal("link").executes(ctx -> link()))
            .then(Commands.literal("status").executes(ctx -> status())));
    }

    private static int link() {
        say(Component.translatable("gui.dungeontrain.credits.link.requesting").withStyle(ChatFormatting.GRAY));
        CommunityLinkClient.start().thenAccept(r -> Minecraft.getInstance().execute(() -> {
            if (!r.ok()) {
                say(error(r.error()));
                return;
            }
            String command = BOT_COMMAND + " " + r.code();
            int minutes = Math.max(1, Math.round(r.expiresInSec() / 60f));
            say(Component.translatable("command.dungeontrain.discord.intro")
                .append(" ").append(clickToCopy(command))
                .append(" ").append(openDiscord()));
            say(Component.translatable("command.dungeontrain.discord.expires", minutes)
                .withStyle(ChatFormatting.GRAY));
        }));
        return 1;
    }

    private static int status() {
        CommunityLinkClient.status().thenAccept(r -> Minecraft.getInstance().execute(() -> {
            if (!r.ok()) {
                say(error(r.error()));
            } else if (r.linked()) {
                say(Component.translatable("command.dungeontrain.discord.linked"));
            } else {
                say(Component.translatable("command.dungeontrain.discord.not_linked"));
            }
        }));
        return 1;
    }

    /** Gold + underlined = clickable in this repo's chat (see {@code BookBurnAuthorMessage}). */
    private static Component clickToCopy(String text) {
        return Component.literal(text).withStyle(style -> style
            .withColor(ChatFormatting.GOLD)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.translatable("command.dungeontrain.discord.copy_hint"))));
    }

    private static Component openDiscord() {
        String url = OfficialLinks.discord();
        MutableComponent label = Component.literal("[")
            .append(Component.translatable("gui.dungeontrain.credits.link.open_discord"))
            .append("]");
        return label.withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
    }

    private static Component error(CommunityLinkClient.Error error) {
        String key = switch (error) {
            case NO_CONSENT -> "gui.dungeontrain.credits.link.no_consent";
            case RATE_LIMITED -> "gui.dungeontrain.credits.link.rate_limited";
            case UNSUPPORTED, DISABLED -> "gui.dungeontrain.credits.link.unsupported";
            default -> "gui.dungeontrain.credits.link.failed";
        };
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(message, false);
    }
}
