package games.brennan.dungeontrain.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The seven tabs {@link DungeonTrainClientOptionsScreen} files its rows under, and which one the
 * player is currently looking at.
 *
 * <p>Same split-by-subject shape as the editor menu's {@link games.brennan.dungeontrain.client.menu.EditorMenuTab},
 * for the same reason: eleven rows in one list is a wall. The division is by <em>what the row acts
 * on</em>:</p>
 *
 * <ul>
 *   <li>{@link #GENERAL} — the client and the player: content rating, chat lines, the hotkey,
 *       the backpack button, translation.</li>
 *   <li>{@link #ACCOUNT} — the player's linked Discord account: link it, and which posts @-ping them
 *       there (their death report, their photos, their milestones).</li>
 *   <li>{@link #BACKUPS} — the restore points Dungeon Backup keeps of the player's builds and
 *       progress: where, how many, clear them, and whether to confirm build re-uploads.</li>
 *   <li>{@link #TRAIN} — the ride itself: engine volume, whether custom train content loads, and
 *       the ride-photo settings.</li>
 *   <li>{@link #STREAM} — the Live Feed: whether wearing the Live Eye broadcasts, and whether
 *       viewers see the HUD and chat — with a preview of what they would see.</li>
 *   <li>{@link #EDITOR} — the three display-scale channels the in-world editor menus and HUD
 *       render at, plus where each of the four editor menus (X/V/C/Z) draws itself.</li>
 *   <li>{@link #PERFORMANCE} — the low-memory notice, the Live Feed switches that cost frame rate
 *       (watching, broadcasting, broadcast size — the Stream tab's own rows), then the lag tips the
 *       death screen's bug-report card offers, checked against this player's setup, each with a button to where the setting lives.</li>
 * </ul>
 *
 * <p>Kept free of Minecraft types on purpose. Three rows are conditional — {@link Row#POLITICAL_FILTER}
 * on Chinese clients, {@link Row#TRANSLATE} when a translation target resolves, and
 * {@link Row#CATCH_UP_BURST} except on a multiplayer client — and the screen packs
 * rows two-across, so any one appearing re-pairs everything after it in its tab. That pairing is
 * the part most likely to break silently and the part a headless test can actually reach, which it
 * cannot do through live widgets.</p>
 */
public enum ClientOptionsTab {

    GENERAL("general"),
    ACCOUNT("account"),
    BACKUPS("backups"),
    TRAIN("train"),
    STREAM("stream"),
    EDITOR("editor"),
    PERFORMANCE("performance");

    private final String key;

    ClientOptionsTab(String key) {
        this.key = key;
    }

    /** Translation key for this tab's label on the navigation bar. */
    public String titleKey() {
        return "gui.dungeontrain.options.tab." + key;
    }

    /**
     * Opens on General: it holds the rows a player is most often hunting for, and the other two are
     * for people who already know what they came to change.
     *
     * <p>Client-only state, so it lives here as a static — the same shape as
     * {@link games.brennan.dungeontrain.client.menu.EditorMenuTab#active()}. It deliberately persists
     * across closing and reopening the screen: someone adjusting editor scales should not be sent
     * back to General every time they reopen Options.</p>
     */
    private static ClientOptionsTab active = GENERAL;

    public static ClientOptionsTab active() {
        return active;
    }

    public static void select(ClientOptionsTab tab) {
        if (tab != null) active = tab;
    }

    /** One row of the options screen — identity only; the screen decides what widget renders it. */
    public enum Row {
        // --- General ---
        /**
         * The difficulty every new run starts on, remembered locally. In a singleplayer run, changing it
         * asks whether to save it for the next run or abandon and restart now — see
         * {@code DifficultyChangeConfirmScreen}.
         */
        DIFFICULTY("gui.dungeontrain.options.difficulty"),
        CONTENT_MODE("gui.dungeontrain.options.content_mode"),
        /** Chat with the developer, both ways — the consent card's Developer chat switch (Adult only). */
        DEV_CHAT("gui.dungeontrain.options.dev_chat"),
        /** Chinese-language clients only — absent, not merely inert, everywhere else. */
        POLITICAL_FILTER("gui.dungeontrain.political_filter.option"),
        BOOK_AUTHOR_CHAT("gui.dungeontrain.options.book_author_chat"),
        /** Your own pets can't be hurt by you — see {@code OwnPetProtectionEvents}. */
        PROTECT_OWN_PETS("gui.dungeontrain.options.protect_own_pets"),
        /** "Dungeon Train X is out" chat line when a release lands mid-session. */
        UPDATE_NOTICE_CHAT("gui.dungeontrain.options.update_notice_chat"),
        /** Once-a-session "give Minecraft more memory" boot card + chat line — see {@code LowMemoryNotice}. */
        LOW_MEMORY_NOTICE_CHAT("gui.dungeontrain.options.low_memory_notice_chat"),
        /** Minecraft's and other mods' tabs on the advancements screen — hidden by default, see {@code AdvancementTabGate}. */
        OTHER_ADVANCEMENT_TABS("gui.dungeontrain.options.other_advancement_tabs"),
        CINEMATIC_HOTKEY("gui.dungeontrain.options.cinematic_hotkey"),
        /** The top-left developer read-out — dev builds only, see {@code VersionHudOverlay}. */
        DEV_HUD("gui.dungeontrain.options.dev_hud"),
        /** Whether Edible Backpacks draws its open/close button on the inventory screen. */
        BACKPACK_BUTTON("gui.dungeontrain.options.backpack_button"),
        /** When Edible Backpacks collects ground pickups: off, while the panels are open, or always. */
        BACKPACK_AUTO_COLLECT("gui.dungeontrain.options.backpack_auto_collect"),
        /** Scribble's save/load-book-to-file buttons on the book screen — off by default, see {@code ScribbleBookButtonsToggle}. */
        BOOK_FILE_BUTTONS("gui.dungeontrain.options.book_file_buttons"),
        /** Scribble's insert-page button on the book screen — off by default. */
        BOOK_INSERT_PAGE_BUTTON("gui.dungeontrain.options.book_insert_page_button"),
        /** Supporter perk: Copy on the book-reading screen. Locked (greyed) below $10 AUD given — see {@code BookCopyButton}. */
        BOOK_COPY_BUTTON("gui.dungeontrain.options.book_copy_button"),
        /** Opens the AI Policy page. Unconditional — every client can reach it. */
        AI_POLICY("gui.dungeontrain.options.ai_policy"),
        /** Only when {@code TranslationTarget.resolveForClient()} names a language to edit. */
        TRANSLATE("gui.dungeontrain.options.translate"),
        /** Opens the reading-languages page (community books in more than one language). Unconditional. */
        BOOK_LANGUAGES("gui.dungeontrain.options.book_languages"),

        // --- Account ---
        /**
         * Stands for the whole Discord-account block: the screen expands it into the link state plus,
         * once linked, one toggle per kind of ping. What it shows comes from the relay at draw time
         * ({@code DiscordAccountState}), so it cannot be fixed enum rows of its own.
         */
        DISCORD_ACCOUNT(null),
        /**
         * Stands for the whole Twitch-account block the same way: link state, then the restream and
         * title opt-ins once linked ({@code TwitchAccountState}). Hidden in Kid mode.
         */
        TWITCH_ACCOUNT(null),

        // --- Backups (widgets built by Dungeon Backup's BackupOptionsWidgets) ---
        /** Where restore points of builds and progress are written. */
        BACKUPS("gui.dungeontrain.options.backups"),
        /** How many archives to keep per mod version. */
        BACKUPS_PER_VERSION("gui.dungeontrain.options.backups_per_version"),
        /** Deletes every archive, in the instance and outside it. Shows the size on disk. */
        CLEAR_BACKUPS("gui.dungeontrain.options.clear_backups"),
        /** Whether to be asked before builds the build server has lost are sent back up. */
        CONFIRM_BUILD_RESTORE("gui.dungeontrain.options.confirm_build_restore"),

        // --- Train ---
        TRAIN_VOLUME("gui.dungeontrain.options.train_volume"),
        CUSTOM_CONTENT("gui.dungeontrain.options.custom_content"),
        SNAPSHOT_MAX_RES("gui.dungeontrain.options.snapshot_max_res"),
        SNAPSHOT_CHAT_LOG("gui.dungeontrain.options.snapshot_chat_log"),
        /**
         * How fast an end of the train may extend once it has fallen behind. One global value
         * in the COMMON config, so it is present at the title screen as well as in a world, and
         * setting it in either place sets it for every world. Absent only on a multiplayer
         * client, where the value belongs to the server and our write would change nothing the
         * player can see — the row is absent rather than a control that lies about what it did.
         */
        CATCH_UP_BURST("gui.dungeontrain.options.catch_up_burst"),

        // --- Stream (the screen adds the viewer preview under these rows) ---
        /** Broadcast when the live headpiece goes on — the consent card's Livestreaming switch. */
        LIVESTREAM("gui.dungeontrain.options.livestream"),
        /** Whether viewers see the HUD and open menus. Everyone. */
        STREAM_GUI("gui.dungeontrain.options.stream_gui"),
        /** Whether viewers see chat. Locked off in Kid mode. */
        STREAM_CHAT("gui.dungeontrain.options.stream_chat"),
        /** Whether the stream carries the game's sound. Off by default. */
        STREAM_AUDIO("gui.dungeontrain.options.stream_audio"),
        /** Which name viewers see above the hotbar. Locked to none in Kid mode. */
        STREAM_NAME_TAG("gui.dungeontrain.options.stream_name_tag"),
        /** Pop-ups drawn back over the stream's name tag. Also on Performance, as a per-frame cost. */
        STREAM_POPUPS_OVER_TAG("gui.dungeontrain.options.stream_popups_over_tag"),
        /** The stream's Discord chat in your chat, and yours back to it. Locked off in Kid mode. */
        LIVE_CHAT("gui.dungeontrain.options.live_chat"),
        /** While streaming, say advancements earned in Live chat. Locked off in Kid mode. */
        LIVE_MOMENT_ADVANCEMENTS("gui.dungeontrain.options.live_moment_advancements"),
        /** While streaming, share photos taken into Live chat. Locked off in Kid mode. */
        LIVE_MOMENT_PHOTOS("gui.dungeontrain.options.live_moment_photos"),
        /** While streaming, share books signed into Live chat. Locked off in Kid mode. */
        LIVE_MOMENT_BOOKS("gui.dungeontrain.options.live_moment_books"),
        /** The "drop your Twitch in chat for a shout-out" line at stream start, while no Twitch is saved. */
        TWITCH_HINT("gui.dungeontrain.options.twitch_hint"),
        /** Whether this client decodes live feeds onto TVs and the pinned viewer. Also on Performance. */
        LIVE_FEED_VIEWING("gui.dungeontrain.options.live_feed_viewing"),
        /** The broadcast's picture size; the preview shows it. */
        STREAM_SIZE("gui.dungeontrain.options.stream_size"),
        /** Slower, smaller encoding: less upload for frame rate. Off by default; also on Performance. */
        STREAM_SAVE_BANDWIDTH("gui.dungeontrain.options.stream_save_bandwidth"),
        /** The live feed pinned top-right on the HUD; the preview outlines it. */
        HEAD_VIEWER_SIZE("gui.dungeontrain.options.head_viewer_size"),

        // --- Editor ---
        SCALE_ALL("gui.dungeontrain.editor_settings.all_displays"),
        SCALE_WORLDSPACE("gui.dungeontrain.editor_settings.worldspace"),
        SCALE_HUD("gui.dungeontrain.editor_settings.hud"),
        MENU_SPACE_COMMAND("gui.dungeontrain.editor_settings.command_menu"),
        MENU_SPACE_TEMPLATE_BLOCKS("gui.dungeontrain.editor_settings.template_blocks_menu"),
        MENU_SPACE_CONTAINER_CONTENTS("gui.dungeontrain.editor_settings.container_contents_menu"),
        MENU_SPACE_BLOCK_VARIANT("gui.dungeontrain.editor_settings.block_variant_menu"),
        /** Whether the biome mods' own creative tabs are drawn. Off by default. */
        CREATIVE_MOD_BLOCK_TABS("gui.dungeontrain.editor_settings.mod_block_tabs"),
        /** Whether those tabs' contents are listed in the creative search. Off by default. */
        CREATIVE_MOD_BLOCKS_IN_SEARCH("gui.dungeontrain.editor_settings.mod_blocks_in_search"),

        // --- Performance ---
        /**
         * Stands for the whole tip list: the screen expands it into an intro line plus one row per
         * {@link games.brennan.dungeontrain.client.bugresponse.LagTips} tip that applies right now.
         * The tips are decided per setup at build time, so they cannot be enum rows of their own.
         */
        PERFORMANCE_TIPS(null);

        /** The lang key of this row's name, or null for a row the screen expands into several. */
        private final String captionKey;

        Row(String captionKey) {
            this.captionKey = captionKey;
        }

        /**
         * The lang key of the name shown beside this row's icon. {@code <key>.hint} is the one-line grey
         * description under it; the screen decides the tooltip. Null for {@link #DISCORD_ACCOUNT} and
         * {@link #PERFORMANCE_TIPS}, which stand for several rows.
         */
        public String captionKey() {
            return captionKey;
        }
    }

    /**
     * The rows in this tab, in render order, for a client with the given two conditional rows.
     *
     * <p>Every tab is non-empty in all four combinations — no combination of flags can produce a tab
     * that opens onto nothing.</p>
     */
    /**
     * Rows that must begin a fresh line rather than pairing with whatever precedes them.
     *
     * <p>Rows are packed two-across in list order, so without this the first row of a group lands
     * beside the last row of the previous one and the group stops reading as a group. Only the
     * LEADER is named — the rest of the group pairs among themselves as usual.</p>
     */
    private static final java.util.Set<Row> GROUP_LEADERS =
            java.util.EnumSet.of(Row.AI_POLICY);

    /**
     * Rows that are captions rather than settings: no widget to operate, and always a line to
     * themselves.
     */
    private static final java.util.Set<Row> HEADINGS = java.util.EnumSet.noneOf(Row.class);

    /** Whether {@code row} is a caption rather than a setting. */
    public static boolean isHeading(Row row) {
        return HEADINGS.contains(row);
    }

    /** Whether {@code row} begins a visual group. See {@link #GROUP_LEADERS}. */
    public static boolean startsGroup(Row row) {
        return GROUP_LEADERS.contains(row);
    }

    /** As a release build sees them: no dev-only rows. */
    public static List<Row> rowsFor(ClientOptionsTab tab, boolean chineseLocale, boolean hasTranslateTarget,
                                    boolean trainSettingsWritable) {
        return rowsFor(tab, chineseLocale, hasTranslateTarget, trainSettingsWritable, false);
    }

    public static List<Row> rowsFor(ClientOptionsTab tab, boolean chineseLocale, boolean hasTranslateTarget,
                                    boolean trainSettingsWritable, boolean devBuild) {
        List<Row> rows = new ArrayList<>();
        switch (tab) {
            case GENERAL -> {
                // First: the one setting here that changes the game itself, not the client around it.
                rows.add(Row.DIFFICULTY);
                rows.add(Row.CONTENT_MODE);
                if (chineseLocale) {
                    rows.add(Row.POLITICAL_FILTER);
                }
                // The consent card's Developer chat switch, next to the content mode it defaults
                // from. Its Livestreaming switch lives on the Stream tab.
                rows.add(Row.DEV_CHAT);
                rows.add(Row.BOOK_AUTHOR_CHAT);
                rows.add(Row.UPDATE_NOTICE_CHAT);
                rows.add(Row.OTHER_ADVANCEMENT_TABS);
                rows.add(Row.PROTECT_OWN_PETS);
                rows.add(Row.CINEMATIC_HOTKEY);
                // Release builds never draw the dev HUD, so the row is absent there, not inert.
                if (devBuild) {
                    rows.add(Row.DEV_HUD);
                }
                rows.add(Row.BACKPACK_BUTTON);
                rows.add(Row.BACKPACK_AUTO_COLLECT);
                rows.add(Row.BOOK_FILE_BUTTONS);
                rows.add(Row.BOOK_INSERT_PAGE_BUTTON);
                rows.add(Row.BOOK_COPY_BUTTON);
                // The two rows that open a page rather than change a setting, led by the
                // unconditional one so they pair on a line of their own — put TRANSLATE in the
                // lead and the pair breaks apart on the release en_us clients where it is absent.
                rows.add(Row.AI_POLICY);
                if (hasTranslateTarget) {
                    rows.add(Row.TRANSLATE);
                }
                // Last, so it never splits the pair above: without TRANSLATE it pairs with AI_POLICY
                // (both open a page), with it it sits on a line of its own.
                rows.add(Row.BOOK_LANGUAGES);
            }
            case ACCOUNT -> {
                rows.add(Row.DISCORD_ACCOUNT);
                rows.add(Row.TWITCH_ACCOUNT);
            }
            case BACKUPS -> {
                // Its own tab: the block outgrew a heading on General once it had four rows and
                // a sibling mod (Dungeon Backup) owning three of them.
                rows.add(Row.BACKUPS);
                // Adjacent so the width packer pairs the two short backup rows on one line.
                rows.add(Row.BACKUPS_PER_VERSION);
                rows.add(Row.CLEAR_BACKUPS);
                // Beside the backups because that is where its second tier comes from: the card this
                // turns on is the only place a backup-only build can be kept out of a restore.
                rows.add(Row.CONFIRM_BUILD_RESTORE);
            }
            case TRAIN -> {
                // The two short-captioned rows lead so they pair on one line; the two whose captions
                // need a full-width row follow. Packing is sequential — it will not reach past a wide
                // row to find a partner — so adjacency here is what puts them side by side.
                rows.add(Row.TRAIN_VOLUME);
                rows.add(Row.SNAPSHOT_CHAT_LOG);
                rows.add(Row.CUSTOM_CONTENT);
                rows.add(Row.SNAPSHOT_MAX_RES);
                // Conditional (absent on a multiplayer client), so it goes LAST: a row that
                // appears and disappears re-pairs everything after it in the tab (see the class
                // javadoc), and nothing follows it here.
                if (trainSettingsWritable) {
                    rows.add(Row.CATCH_UP_BURST);
                }
            }
            case STREAM -> {
                rows.add(Row.LIVESTREAM);
                rows.add(Row.STREAM_GUI);
                rows.add(Row.STREAM_CHAT);
                rows.add(Row.STREAM_AUDIO);
                rows.add(Row.STREAM_NAME_TAG);
                rows.add(Row.STREAM_POPUPS_OVER_TAG);
                rows.add(Row.LIVE_CHAT);
                rows.add(Row.LIVE_MOMENT_ADVANCEMENTS);
                rows.add(Row.LIVE_MOMENT_PHOTOS);
                rows.add(Row.LIVE_MOMENT_BOOKS);
                rows.add(Row.TWITCH_HINT);
                rows.add(Row.LIVE_FEED_VIEWING);
                rows.add(Row.STREAM_SIZE);
                rows.add(Row.STREAM_SAVE_BANDWIDTH);
                rows.add(Row.HEAD_VIEWER_SIZE);
            }
            case EDITOR -> {
                rows.add(Row.SCALE_ALL);
                rows.add(Row.SCALE_WORLDSPACE);
                rows.add(Row.SCALE_HUD);
                rows.add(Row.MENU_SPACE_COMMAND);
                rows.add(Row.MENU_SPACE_TEMPLATE_BLOCKS);
                rows.add(Row.MENU_SPACE_CONTAINER_CONTENTS);
                rows.add(Row.MENU_SPACE_BLOCK_VARIANT);
                // The creative inventory is the editor's palette, so its two filters sit with the
                // editor's other preferences. Adjacent, and last, so they pair with each other.
                rows.add(Row.CREATIVE_MOD_BLOCK_TABS);
                rows.add(Row.CREATIVE_MOD_BLOCKS_IN_SEARCH);
            }
            case PERFORMANCE -> {
                // The one performance NOTICE sits above the tips: it is about this machine's setup,
                // not about the client or the player, so it reads as part of this tab's subject.
                rows.add(Row.LOW_MEMORY_NOTICE_CHAT);
                // The Live Feed's frame-rate costs, the same rows as on the Stream tab: what a
                // player who came here to stop lag would otherwise have to know to look for there.
                // Watching (decoder) and broadcasting (encoder) pair on one line; the size and the
                // pop-ups-over-name copy follow.
                rows.add(Row.LIVE_FEED_VIEWING);
                rows.add(Row.LIVESTREAM);
                rows.add(Row.STREAM_SIZE);
                rows.add(Row.STREAM_SAVE_BANDWIDTH);
                rows.add(Row.STREAM_POPUPS_OVER_TAG);
                rows.add(Row.PERFORMANCE_TIPS);
            }
        }
        return List.copyOf(rows);
    }
}
