package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelStorageFile;
import games.brennan.dungeontrain.ship.sable.SableStorageSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;

/**
 * Stops Sable's sub-level save from waiting on the disk for every single write (#1508).
 *
 * <p>Sable saves trains on the <b>server thread</b> ({@code SubLevelHoldingChunkMap.saveAll},
 * called from {@code ServerLevel.save}). {@code SubLevelStorageFile} opens its channel with
 * {@code CREATE, READ, WRITE, DSYNC}, so each header, sector and padding write blocks until the
 * disk confirms it, and {@code flush()} then {@code force()}s every open file whether it changed
 * or not. With Distant Horizons writing its LOD database to the same disk, a player's autosave
 * froze the game for ~500 s, and 5–15 s freezes followed nearly every autosave and pause.</p>
 *
 * <p>This mixin (a) drops {@code DSYNC} from the constructor's open options, so writes land in
 * the OS cache, (b) skips the {@code force()} in {@code flush()} for files with no writes since
 * the last flush, and (c) on a non-flush save (the periodic autosave, the singleplayer pause
 * save, plain {@code /save-all}) leaves changed files in the OS cache too, marked dirty, so the
 * next flush save syncs them. That is vanilla's own contract: {@code ServerLevel.save} only
 * syncs region files when its {@code flush} argument is true (shutdown, Save &amp; Quit,
 * {@code /save-all flush}); {@link ServerLevelSaveFlushFlagMixin} relays that argument. A JVM
 * crash can't lose OS-cached writes, only a power loss between an autosave and the next flush
 * save could (same tradeoff as vanilla chunks). {@code close()}'s forces are left alone, so
 * leaving a world always syncs. {@code SubLevelRegionFile} (holding chunks) extends this class,
 * so both file kinds are covered. Files on disk are byte-identical to before.
 * {@code -Ddungeontrain.sable.forceOnAutosave=true} restores the sync on every save.</p>
 *
 * <p>Also times {@code write(int, CompoundTag)} (gzip + file writes) and the {@code force()}
 * itself for the {@code [DT-SableSave]} breakdown.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.5+mc1.21.1}: the 3-arg constructor has the only
 * {@code FileChannel.open} with {@code DSYNC} ({@code writeToExternalFile} opens its own channel
 * without it); {@code writeHeader}, {@code write(int, ByteBuffer)} and
 * {@code padOrTruncateToFullSector} are the only methods that write to or truncate the channel
 * ({@code clear} goes through {@code writeHeader}); {@code flush} is a single
 * {@code file.force(true)}; {@code write(ILnet/minecraft/nbt/CompoundTag;)V} is the one entry
 * every sub-level and holding-chunk save goes through ({@code attemptSaveSubLevel},
 * {@code trySave}, {@code tryRemove}). <b>Re-verify on any {@code sable_version} bump.</b> Left
 * {@code required} so a Sable change fails loudly instead of silently restoring the stall.</p>
 *
 * <p>The clean flag is cleared before the sync and restored on failure, so a write racing the
 * flush re-marks the file dirty rather than being lost from the next flush.</p>
 */
@Mixin(value = SubLevelStorageFile.class, remap = false)
public abstract class SubLevelStorageFileNoDsyncMixin {

    /** No writes since the last successful flush. Starts false, so a fresh file always syncs. */
    @Unique
    private volatile boolean dungeontrain$clean;

    /** Start of the {@code write(int, CompoundTag)} in progress (server thread only). */
    @Unique
    private long dungeontrain$writeStartNanos;

    @ModifyArg(
        method = "<init>(Ljava/nio/file/Path;Ljava/nio/file/Path;I)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/nio/channels/FileChannel;open(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/FileChannel;"
        ),
        index = 1
    )
    private OpenOption[] dungeontrain$dropDsync(OpenOption[] options) {
        return SableStorageSync.withoutDsync(options);
    }

    @Inject(method = "writeHeader", at = @At("HEAD"))
    private void dungeontrain$dirtyOnHeader(CallbackInfo ci) {
        dungeontrain$clean = false;
    }

    @Inject(method = "write(ILjava/nio/ByteBuffer;)V", at = @At("HEAD"))
    private void dungeontrain$dirtyOnWrite(CallbackInfo ci) {
        dungeontrain$clean = false;
    }

    @Inject(method = "padOrTruncateToFullSector", at = @At("HEAD"))
    private void dungeontrain$dirtyOnPad(CallbackInfo ci) {
        dungeontrain$clean = false;
    }

    @Inject(method = "write(ILnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"))
    private void dungeontrain$startWriteTimer(CallbackInfo ci) {
        dungeontrain$writeStartNanos = System.nanoTime();
    }

    @Inject(method = "write(ILnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"))
    private void dungeontrain$endWriteTimer(CallbackInfo ci) {
        SableStorageSync.addWriteNanos(System.nanoTime() - dungeontrain$writeStartNanos);
    }

    @WrapOperation(
        method = "flush",
        at = @At(value = "INVOKE", target = "Ljava/nio/channels/FileChannel;force(Z)V")
    )
    private void dungeontrain$forceOnlyIfDirty(FileChannel channel, boolean metaData, Operation<Void> original) {
        if (dungeontrain$clean) {
            SableStorageSync.recordSkipped();
            return;
        }
        if (!SableStorageSync.shouldForce()) {
            // Autosave: leave the bytes in the OS cache and stay dirty for the next flush save.
            SableStorageSync.recordDeferred();
            return;
        }
        dungeontrain$clean = true;
        boolean synced = false;
        long start = System.nanoTime();
        try {
            // force() throws IOException through Operation.call undeclared, hence finally.
            original.call(channel, metaData);
            synced = true;
        } finally {
            SableStorageSync.addSyncNanos(System.nanoTime() - start);
            if (!synced) dungeontrain$clean = false;
        }
        SableStorageSync.recordForced();
    }
}
