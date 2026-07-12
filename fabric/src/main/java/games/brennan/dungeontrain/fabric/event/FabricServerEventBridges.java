package games.brennan.dungeontrain.fabric.event;

import games.brennan.dungeontrain.fabric.FabricPlatform;
import games.brennan.dungeontrain.fabric.mixin.BlockEntityTypeAccessor;
import games.brennan.dungeontrain.platform.event.DtAttackEntityCallback;
import games.brennan.dungeontrain.platform.event.DtBlockBreakCallback;
import games.brennan.dungeontrain.platform.event.DtChunkLoadCallback;
import games.brennan.dungeontrain.platform.event.DtCommandRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtEntityInteractCallback;
import games.brennan.dungeontrain.platform.event.DtEntityLeaveCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtLevelTickCallback;
import games.brennan.dungeontrain.platform.event.DtLevelUnloadCallback;
import games.brennan.dungeontrain.platform.event.DtLivingDamageCallback;
import games.brennan.dungeontrain.platform.event.DtLivingDeathCallback;
import games.brennan.dungeontrain.platform.event.DtPlayerLoginCallback;
import games.brennan.dungeontrain.platform.event.DtPlayerLogoutCallback;
import games.brennan.dungeontrain.platform.event.DtPlayerRespawnCallback;
import games.brennan.dungeontrain.platform.event.DtPriority;
import games.brennan.dungeontrain.platform.event.DtRightClickBlock;
import games.brennan.dungeontrain.platform.event.DtRightClickBlockCallback;
import games.brennan.dungeontrain.platform.event.DtRightClickItemCallback;
import games.brennan.dungeontrain.platform.event.DtServerChatCallback;
import games.brennan.dungeontrain.platform.event.DtServerTickCallback;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Subscribes the real Fabric server-side events and fires the matching {@code DtEvents}
 * fields — the Fabric analogue of the auto-registered {@code NeoForge*Bridge} classes.
 * Pure adapters: no logic beyond translating Fabric's callback shape into the
 * {@code DtEvents} fire shape, preserving cancellation and (best-effort) DT-internal
 * ordering. Fabric has no per-listener priority, so tiered events fire
 * {@code listeners()} (concatenated HIGHEST→LOWEST) — DT-internal tier order is
 * preserved; cross-mod interleaving is not (documented Fabric-v1 divergence).
 *
 * <p>Events with no Fabric-API equivalent (entity-join cancel, mob-effect-remove cancel,
 * finalize-spawn, player/entity tick, gamemode change, equipment change, advancement earn,
 * command-exec cancel) are fired from thin gap-filler mixins that call {@code DtEvents}
 * directly; those are wired by the mixin config, not here.</p>
 */
public final class FabricServerEventBridges {

    private FabricServerEventBridges() {}

    public static void register() {
        registerLifecycleAndTicks();
        registerConnection();
        registerLiving();
        registerChunkAndLevel();
        registerInteract();
        registerCommandsAndChat();
        registerDeclarative();
    }

    // ---- Server lifecycle + ticks -----------------------------------------

    private static void registerLifecycleAndTicks() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            FabricPlatform.setCurrentServer(server);
            DtEvents.SERVER_STARTING.listeners().forEach(cb -> cb.onServerStarting(server));
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
            DtEvents.SERVER_STARTED.listeners().forEach(cb -> cb.onServerStarted(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
            DtEvents.SERVER_STOPPING.listeners().forEach(cb -> cb.onServerStopping(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            DtEvents.SERVER_STOPPED.listeners().forEach(cb -> cb.onServerStopped(server));
            FabricPlatform.setCurrentServer(null);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (DtEvents.SERVER_TICK.isEmpty()) {
                return;
            }
            for (DtServerTickCallback cb : DtEvents.SERVER_TICK.listeners()) {
                cb.onServerTick(server);
            }
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (DtEvents.LEVEL_TICK.isEmpty()) {
                return;
            }
            for (DtLevelTickCallback cb : DtEvents.LEVEL_TICK.listeners()) {
                cb.onLevelTick(world);
            }
        });
    }

    // ---- Player connection -------------------------------------------------

    private static void registerConnection() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            for (DtPlayerLoginCallback cb : DtEvents.PLAYER_LOGIN.listeners()) {
                cb.onPlayerLoggedIn(handler.player);
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            for (DtPlayerLogoutCallback cb : DtEvents.PLAYER_LOGOUT.listeners()) {
                cb.onPlayerLoggedOut(handler.player);
            }
        });
        // Fabric's `alive` == "returned from the End" == NeoForge's isEndConquered.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            for (DtPlayerRespawnCallback cb : DtEvents.PLAYER_RESPAWN.listeners()) {
                cb.onPlayerRespawn(newPlayer, alive);
            }
        });
    }

    // ---- Living entity -----------------------------------------------------

    private static void registerLiving() {
        // NeoForge LivingDeathEvent fires just before death; Fabric AFTER_DEATH just after —
        // DT death handlers observe (no cancel), so the isCanceled flag is always false.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            for (DtLivingDeathCallback cb : DtEvents.LIVING_DEATH.listeners()) {
                cb.onDeath(entity, source, false);
            }
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, takenDamage, blocked) -> {
            for (DtLivingDamageCallback cb : DtEvents.LIVING_DAMAGE.listeners()) {
                cb.onLivingDamage(entity, source, takenDamage);
            }
        });
    }

    // ---- Chunk / level -----------------------------------------------------

    private static void registerChunkAndLevel() {
        // Fabric has no isNewChunk flag; approximate with isUnsaved() (freshly generated
        // chunks load dirty, disk chunks load clean). Fabric-v1 approximation — band
        // handlers gated on newChunk (nether/disintegration/bedrock/upside-down) may
        // occasionally re-run on a dirty reload; the upside-down drain is marker-idempotent.
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (DtEvents.CHUNK_LOAD.isEmpty()) {
                return;
            }
            boolean newChunk = chunk.isUnsaved();
            for (DtChunkLoadCallback cb : DtEvents.CHUNK_LOAD.listeners()) {
                cb.onChunkLoad(world, chunk, newChunk);
            }
        });
        ServerWorldEvents.UNLOAD.register((server, world) -> {
            for (DtLevelUnloadCallback cb : DtEvents.LEVEL_UNLOAD.listeners()) {
                cb.onLevelUnload(world);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            for (DtEntityLeaveCallback cb : DtEvents.ENTITY_LEAVE.listeners()) {
                cb.onEntityLeave(entity, world);
            }
        });
    }

    // ---- Interaction -------------------------------------------------------

    private static void registerInteract() {
        // RightClickItem — observers only (never cancel). Fire then PASS.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            for (DtRightClickItemCallback cb : DtEvents.RIGHT_CLICK_ITEM.listeners()) {
                cb.onRightClickItem(player, world, stack);
            }
            return InteractionResultHolder.pass(stack);
        });
        // EntityInteract — observers only. Fire then PASS.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            for (DtEntityInteractCallback cb : DtEvents.ENTITY_INTERACT.listeners()) {
                cb.onEntityInteract(player, world, player.getItemInHand(hand), entity);
            }
            return InteractionResult.PASS;
        });
        // AttackEntity — observers only. Fire then PASS.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            for (DtAttackEntityCallback cb : DtEvents.ATTACK_ENTITY.listeners()) {
                cb.onAttackEntity(player, entity, false);
            }
            return InteractionResult.PASS;
        });
        // BlockBreak — observers only (no DT cancel). Fabric AFTER (post-break).
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            for (DtBlockBreakCallback cb : DtEvents.BLOCK_BREAK.listeners()) {
                cb.onBlockBreak(world, player, pos, state, false);
            }
        });
        // RightClickBlock — cancellable with a result across three tiers (HIGHEST/HIGH/NORMAL).
        UseBlockCallback.EVENT.register(FabricServerEventBridges::onUseBlock);
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {
        RightClickBlockCarrier carrier = new RightClickBlockCarrier(player, world, hand, hitResult);
        dispatchRightClickTier(carrier, DtPriority.HIGHEST);
        dispatchRightClickTier(carrier, DtPriority.HIGH);
        dispatchRightClickTier(carrier, DtPriority.NORMAL);
        if (carrier.canceled) {
            return carrier.result != null ? carrier.result : InteractionResult.CONSUME;
        }
        if (carrier.denied) {
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    private static void dispatchRightClickTier(RightClickBlockCarrier carrier, DtPriority tier) {
        for (DtRightClickBlockCallback cb : DtEvents.RIGHT_CLICK_BLOCK.listeners(tier)) {
            if (carrier.canceled) {
                return;
            }
            cb.onRightClickBlock(carrier);
        }
    }

    /** Mutable {@link DtRightClickBlock} carrier backed by local fields (Fabric has no live event object). */
    private static final class RightClickBlockCarrier implements DtRightClickBlock {
        private final Player player;
        private final Level level;
        private final InteractionHand hand;
        private final BlockHitResult hit;
        private boolean canceled;
        private boolean denied;
        private InteractionResult result;

        RightClickBlockCarrier(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
            this.player = player;
            this.level = level;
            this.hand = hand;
            this.hit = hit;
        }

        @Override public Player player() { return player; }
        @Override public Level level() { return level; }
        @Override public net.minecraft.core.BlockPos pos() { return hit.getBlockPos(); }
        @Override public InteractionHand hand() { return hand; }
        @Override public ItemStack itemStack() { return player.getItemInHand(hand); }
        @Override public Direction face() { return hit.getDirection(); }
        @Override public BlockHitResult hitResult() { return hit; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public void setCanceled(boolean c) { this.canceled = c; }
        @Override public void setCancellationResult(InteractionResult r) { this.result = r; }
        @Override public void denyUseBlock() { this.denied = true; }
        @Override public void denyUseItem() { this.denied = true; }
    }

    // ---- Commands + chat ---------------------------------------------------

    private static void registerCommandsAndChat() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (DtCommandRegistrationCallback cb : DtEvents.COMMAND_REGISTRATION.listeners()) {
                cb.register(dispatcher, registryAccess, environment);
            }
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (DtEvents.SERVER_CHAT.isEmpty()) {
                return;
            }
            String raw = message.signedContent();
            Component decorated = message.decoratedContent();
            for (DtServerChatCallback cb : DtEvents.SERVER_CHAT.listeners()) {
                cb.onChat(sender, raw, decorated);
            }
        });
    }

    // ---- Declarative (reload listeners + block-entity valid blocks) --------

    private static void registerDeclarative() {
        // Server datapack reload listeners — wrap each PreparableReloadListener in an
        // identifiable listener with a synthetic id (Fabric requires identifiable listeners).
        int[] counter = {0};
        DtEvents.SERVER_RELOAD_LISTENER_REGISTRATION.listeners().forEach(cb ->
            cb.registerReloadListeners(listener -> {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    "dungeontrain", "reload_" + (counter[0]++));
                ResourceManagerHelper.get(PackType.SERVER_DATA)
                    .registerReloadListener(wrapReload(id, listener));
            }));

        // Block-entity valid-block extensions (narrative lectern → vanilla LECTERN BE).
        DtEvents.BLOCK_ENTITY_TYPE_ADD_BLOCKS.listeners().forEach(cb ->
            cb.addBlocks(FabricServerEventBridges::addValidBlocks));
    }

    @SuppressWarnings("unchecked")
    private static void addValidBlocks(BlockEntityType<?> type, Block... blocks) {
        BlockEntityTypeAccessor accessor = (BlockEntityTypeAccessor) type;
        Set<Block> current = new HashSet<>(accessor.dungeonTrain$getValidBlocks());
        for (Block block : blocks) {
            current.add(block);
        }
        accessor.dungeonTrain$setValidBlocks(current);
    }

    /** Wrap a PreparableReloadListener into a Fabric identifiable reload listener. */
    static IdentifiableResourceReloadListener wrapReload(ResourceLocation id, PreparableReloadListener delegate) {
        return new IdentifiableResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return id; }
            @Override public java.util.concurrent.CompletableFuture<Void> reload(
                    PreparableReloadListener.PreparationBarrier barrier,
                    net.minecraft.server.packs.resources.ResourceManager manager,
                    net.minecraft.util.profiling.ProfilerFiller prepareProfiler,
                    net.minecraft.util.profiling.ProfilerFiller applyProfiler,
                    java.util.concurrent.Executor prepareExecutor,
                    java.util.concurrent.Executor applyExecutor) {
                return delegate.reload(barrier, manager, prepareProfiler, applyProfiler, prepareExecutor, applyExecutor);
            }
        };
    }
}
