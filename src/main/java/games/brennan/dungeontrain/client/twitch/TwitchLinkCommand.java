package games.brennan.dungeontrain.client.twitch;

import com.mojang.brigadier.CommandDispatcher;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code /twitch} — connect this player's Twitch channel from chat, so that while they wear the Live
 * Eye the people watching can find them: their name links to the channel on the Live page, in the
 * Discord post and in the Dungeon Train Twitch chat, and the stream title names it.
 *
 * <ul>
 *   <li>{@code /twitch} — says whether this player is linked, then mints a code and gives the link
 *       page to open (click to open, or copy the code) and watches for the link to land.</li>
 *   <li>{@code /twitch restream on|off} — also push the stream to their own channel while live.</li>
 *   <li>{@code /twitch title on|off} — with restream, set their channel's title/category when the stream starts.</li>
 *   <li>{@code /twitch unlink} — forget the link; the relay tells Twitch to drop the grant.</li>
 * </ul>
 *
 * <p>A CLIENT command ({@link RegisterClientCommandsEvent}, the {@code DiscordLinkCommand} pattern): the
 * code is minted for the signed-in player's own uuid through {@link TwitchLinkClient}, consent-gated
 * like every credits call, so it works on any server and the server never sees it. Kid mode has no
 * Twitch: the command says so and does nothing else.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TwitchLinkCommand {

    static final String COMMAND = "twitch";
    private static final long POLL_SECONDS = 5;
    private static final long POLL_MAX_SECONDS = 15 * 60;

    /** Bumped by every {@code /twitch}, so an older code's watcher stops when a newer one starts. */
    private static volatile int watchGeneration = 0;

    private record Asked(TwitchLinkClient.Status status, TwitchLinkClient.Start start) {}

    private TwitchLinkCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(COMMAND)
                .executes(ctx -> link())
                .then(Commands.literal("restream")
                        .then(Commands.literal("on").executes(ctx -> settings(true, null)))
                        .then(Commands.literal("off").executes(ctx -> settings(false, null))))
                .then(Commands.literal("title")
                        .then(Commands.literal("on").executes(ctx -> settings(null, true)))
                        .then(Commands.literal("off").executes(ctx -> settings(null, false))))
                .then(Commands.literal("unlink").executes(ctx -> unlink())));
    }

    private static boolean kid() {
        if (!ClientDisplayConfig.isKidMode()) return false;
        say(Component.translatable("chat.dungeontrain.twitch.kid_mode").withStyle(ChatFormatting.YELLOW));
        return true;
    }

    private static int link() {
        if (kid()) return 0;
        say(Component.translatable("chat.dungeontrain.twitch.requesting").withStyle(ChatFormatting.GRAY));
        TwitchLinkClient.status().thenCompose(status ->
                TwitchLinkClient.start().thenApply(start -> new Asked(status, start)))
            .whenComplete((asked, err) -> onClient(() -> {
                if (err != null || asked == null) {
                    say(error(TwitchLinkClient.Error.FAILED));
                    return;
                }
                TwitchLinkClient.Status status = asked.status();
                TwitchLinkClient.Start start = asked.start();
                boolean wasLinked = status != null && status.ok() && status.linked();
                if (wasLinked) {
                    say(linkedLines(status));
                }
                if (!start.ok()) {
                    if (!wasLinked) say(error(start.error()));
                    return;
                }
                say(codeLines(start, wasLinked));
                if (!wasLinked) watch(++watchGeneration, 0);
            }));
        return 1;
    }

    private static int settings(Boolean restream, Boolean title) {
        if (kid()) return 0;
        TwitchLinkClient.setSettings(restream, title).whenComplete((s, err) -> onClient(() -> {
            TwitchAccountState.invalidate();
            if (err != null || s == null) { say(error(TwitchLinkClient.Error.FAILED)); return; }
            if (!s.ok()) {
                if (s.error() == TwitchLinkClient.Error.NOT_LINKED) {
                    say(Component.translatable("chat.dungeontrain.twitch.not_linked", command(COMMAND)).withStyle(ChatFormatting.YELLOW));
                } else {
                    say(error(s.error()));
                }
                return;
            }
            say(linkedLines(s));
        }));
        return 1;
    }

    private static int unlink() {
        if (kid()) return 0;
        TwitchLinkClient.unlink().whenComplete((r, err) -> onClient(() -> {
            TwitchAccountState.invalidate();
            if (err != null || r == null) say(error(TwitchLinkClient.Error.FAILED));
            else if (r.ok()) say(Component.translatable("chat.dungeontrain.twitch.unlinked").withStyle(ChatFormatting.GREEN));
            else if (r.error() == TwitchLinkClient.Error.NOT_LINKED) say(Component.translatable(
                    "chat.dungeontrain.twitch.not_linked", command(COMMAND)).withStyle(ChatFormatting.YELLOW));
            else say(error(r.error()));
        }));
        return 1;
    }

    /** Ask the relay every few seconds until the player finishes on the web page, then say so once. */
    private static void watch(int generation, long elapsedSeconds) {
        if (generation != watchGeneration || elapsedSeconds >= POLL_MAX_SECONDS) return;
        CompletableFuture.delayedExecutor(POLL_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (generation != watchGeneration) return;
            TwitchLinkClient.status().whenComplete((s, err) -> {
                if (err == null && s != null && s.ok() && s.linked()) {
                    onClient(() -> {
                        TwitchAccountState.invalidate();
                        if (generation == watchGeneration) {
                            say(Component.translatable("chat.dungeontrain.twitch.linked_now", channelLink(s.login())).withStyle(ChatFormatting.GREEN));
                            say(linkedLines(s));
                        }
                    });
                    return;
                }
                watch(generation, elapsedSeconds + POLL_SECONDS);
            });
        });
    }

    /** "Your Twitch link code: ABC234 [Open link page] [Copy code]" then what linking does. */
    static Component codeLines(TwitchLinkClient.Start start, boolean relink) {
        String code = start.code();
        MutableComponent codeText = Component.literal(code).withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("gui.dungeontrain.credits.link.copy"))));
        MutableComponent open = Component.literal("[").append(Component.translatable("chat.dungeontrain.twitch.open_page"))
                .append("]").withStyle(s -> s.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, start.url()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(start.url()))));
        MutableComponent copy = Component.literal("[").append(Component.translatable("gui.dungeontrain.credits.link.copy"))
                .append("]").withStyle(s -> s.withColor(ChatFormatting.BLUE)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(code))));
        return Component.translatable(relink ? "chat.dungeontrain.twitch.relink_code" : "chat.dungeontrain.twitch.code", codeText)
                .withStyle(ChatFormatting.WHITE)
                .append(" ").append(open).append(" ").append(copy)
                .append("\n")
                .append(Component.translatable("chat.dungeontrain.twitch.instructions").withStyle(ChatFormatting.GRAY));
    }

    /** "Twitch linked: twitch.tv/x. Restream: on/off (/twitch restream …). Title: …" */
    static Component linkedLines(TwitchLinkClient.Status s) {
        MutableComponent out = Component.translatable("chat.dungeontrain.twitch.linked", channelLink(s.login())).withStyle(ChatFormatting.GREEN);
        out.append("\n").append(Component.translatable(s.restream() ? "chat.dungeontrain.twitch.restream_on" : "chat.dungeontrain.twitch.restream_off",
                command(COMMAND + (s.restream() ? " restream off" : " restream on"))).withStyle(ChatFormatting.GRAY));
        out.append("\n").append(Component.translatable(s.setTitle() ? "chat.dungeontrain.twitch.title_on" : "chat.dungeontrain.twitch.title_off",
                command(COMMAND + (s.setTitle() ? " title off" : " title on"))).withStyle(ChatFormatting.GRAY));
        out.append("\n").append(Component.translatable("chat.dungeontrain.twitch.options_hint", command(COMMAND + " unlink")).withStyle(ChatFormatting.GRAY));
        return out;
    }

    /** "twitch.tv/login", clickable. */
    static MutableComponent channelLink(String login) {
        String url = "https://twitch.tv/" + login;
        return Component.literal("twitch.tv/" + login).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
    }

    /** A {@code /command} in chat that fills the chat box when clicked. */
    private static Component command(String text) {
        return Component.literal("/" + text).withStyle(s -> s.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + text)));
    }

    private static Component error(TwitchLinkClient.Error error) {
        String key = switch (error) {
            case NO_CONSENT -> "gui.dungeontrain.credits.link.no_consent";
            case RATE_LIMITED -> "gui.dungeontrain.credits.link.rate_limited";
            case UNSUPPORTED, DISABLED -> "chat.dungeontrain.twitch.unsupported";
            default -> "gui.dungeontrain.credits.link.failed";
        };
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    private static void say(Component line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(line, false);
    }

    private static void onClient(Runnable r) {
        Minecraft.getInstance().execute(r);
    }
}
