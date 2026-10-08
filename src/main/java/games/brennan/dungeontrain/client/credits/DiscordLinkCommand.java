package games.brennan.dungeontrain.client.credits;

import com.mojang.brigadier.CommandDispatcher;
import games.brennan.dungeontrain.DungeonTrain;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code /discord} — link this player's Discord account from chat, the same link the Credits page's
 * "Link your Discord" button makes ({@link DiscordLinkScreen}). Once linked, the relay @-pings that
 * Discord account whenever one of the player's photos is posted, or their death is reported.
 *
 * <ul>
 *   <li>{@code /discord} — says whether this player is linked, then mints a code (click to copy the
 *       code, plus an Open Discord link) and watches for the link to land. Only the code is copied:
 *       Discord runs a slash command only when it is picked from its list, so a pasted
 *       {@code /dtlink CODE} would post as a plain message.</li>
 *   <li>{@code /discord pings on|off} — keep the link but stop (or restart) the pings.</li>
 * </ul>
 *
 * <p>A CLIENT command ({@link RegisterClientCommandsEvent}, the {@code FramerateThrottleCommand}
 * pattern): the code is minted for the signed-in player's own uuid through {@link CommunityLinkClient},
 * consent-gated like every credits call, so it works on any server — DT on the server or not — and the
 * server never sees it, so it can never taint a run. No Discord id ever reaches the mod: the relay keeps
 * them and resolves the pings itself.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DiscordLinkCommand {

    static final String COMMAND = "discord";
    /** How often the "did they run it yet?" check asks the relay, and how long it keeps asking at most. */
    private static final long POLL_SECONDS = 5;
    private static final long POLL_MAX_SECONDS = 15 * 60;

    /** Bumped by every {@code /discord}, so an older code's watcher stops when a newer one starts. */
    private static volatile int watchGeneration = 0;

    /** The two answers {@code /discord} waits for: is this player linked, and a fresh code. */
    private record Asked(CommunityLinkClient.Status status, CommunityLinkClient.Start start) {}

    private DiscordLinkCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(COMMAND)
                .executes(ctx -> link())
                .then(Commands.literal("pings")
                        .then(Commands.literal("on").executes(ctx -> pings(true)))
                        .then(Commands.literal("off").executes(ctx -> pings(false)))));
    }

    private static int link() {
        say(Component.translatable("gui.dungeontrain.credits.link.requesting").withStyle(ChatFormatting.GRAY));
        CommunityLinkClient.status().thenCompose(status ->
                CommunityLinkClient.start().thenApply(start -> new Asked(status, start)))
            .whenComplete((asked, err) -> onClient(() -> {
                if (err != null || asked == null) {
                    say(error(CommunityLinkClient.Error.FAILED));
                    return;
                }
                CommunityLinkClient.Status status = asked.status();
                CommunityLinkClient.Start start = asked.start();
                boolean wasLinked = status != null && status.ok() && status.linked();
                if (wasLinked) say(linkedLine(status.pings()));
                if (!start.ok()) {
                    // Already linked and no new code is fine — the status line said what matters.
                    if (!wasLinked) say(error(start.error()));
                    return;
                }
                say(codeLines(start.code(), wasLinked));
                if (!wasLinked) watch(++watchGeneration, 0);
            }));
        return 1;
    }

    private static int pings(boolean on) {
        CommunityLinkClient.setPings(on).whenComplete((r, err) -> onClient(() -> {
            if (err != null || r == null) say(error(CommunityLinkClient.Error.FAILED));
            else if (r.ok()) say(Component.translatable(r.on() ? "chat.dungeontrain.discord.pings_on"
                    : "chat.dungeontrain.discord.pings_off").withStyle(ChatFormatting.GREEN));
            else if (r.error() == CommunityLinkClient.Error.NOT_LINKED) say(Component.translatable(
                    "chat.dungeontrain.discord.not_linked", command(COMMAND)).withStyle(ChatFormatting.YELLOW));
            else say(error(r.error()));
        }));
        return 1;
    }

    /** Ask the relay every few seconds until the player runs the code in Discord, then say so once. */
    private static void watch(int generation, long elapsedSeconds) {
        if (generation != watchGeneration || elapsedSeconds >= POLL_MAX_SECONDS) return;
        CompletableFuture.delayedExecutor(POLL_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (generation != watchGeneration) return;
            CommunityLinkClient.status().whenComplete((s, err) -> {
                if (err == null && s != null && s.ok() && s.linked()) {
                    onClient(() -> {
                        if (generation == watchGeneration) say(Component.translatable("chat.dungeontrain.discord.linked_now")
                                .withStyle(ChatFormatting.GREEN));
                    });
                    return;
                }
                watch(generation, elapsedSeconds + POLL_SECONDS);
            });
        });
    }

    /** "Your code: ABC234 [Copy code] [Open Discord]" then how to use it. */
    private static Component codeLines(String code, boolean relink) {
        MutableComponent codeText = Component.literal(code).withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("gui.dungeontrain.credits.link.copy"))));
        MutableComponent copy = Component.literal("[").append(Component.translatable("gui.dungeontrain.credits.link.copy"))
                .append("]").withStyle(s -> s.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(code))));
        String url = OfficialLinks.discord();
        MutableComponent open = Component.literal("[").append(Component.translatable("gui.dungeontrain.credits.link.open_discord"))
                .append("]").withStyle(s -> s.withColor(ChatFormatting.BLUE)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
        return Component.translatable(relink ? "chat.dungeontrain.discord.relink_code" : "chat.dungeontrain.discord.code",
                        codeText)
                .withStyle(ChatFormatting.WHITE)
                .append(" ").append(copy).append(" ").append(open)
                .append("\n")
                .append(Component.translatable("chat.dungeontrain.discord.instructions",
                                Component.literal("/dtlink").withStyle(ChatFormatting.AQUA))
                        .withStyle(ChatFormatting.GRAY));
    }

    private static Component linkedLine(boolean pings) {
        return pings
                ? Component.translatable("chat.dungeontrain.discord.linked_pings_on", command(COMMAND + " pings off"))
                        .withStyle(ChatFormatting.GREEN)
                : Component.translatable("chat.dungeontrain.discord.linked_pings_off", command(COMMAND + " pings on"))
                        .withStyle(ChatFormatting.YELLOW);
    }

    /** A {@code /command} in chat that fills the chat box when clicked. */
    private static Component command(String text) {
        return Component.literal("/" + text).withStyle(s -> s.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + text)));
    }

    private static Component error(CommunityLinkClient.Error error) {
        String key = switch (error) {
            case NO_CONSENT -> "no_consent";
            case RATE_LIMITED -> "rate_limited";
            case UNSUPPORTED, DISABLED -> "unsupported";
            default -> "failed";
        };
        return Component.translatable("gui.dungeontrain.credits.link." + key).withStyle(ChatFormatting.RED);
    }

    private static void say(Component line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(line, false);
    }

    private static void onClient(Runnable r) {
        Minecraft.getInstance().execute(r);
    }
}
