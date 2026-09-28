package games.brennan.dungeontrain.tools;

import games.brennan.dungeontrain.builder.relay.BuilderTemplateSource;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Fingerprint every template the mod ships, the way the relay hashes an upload — so the relay can
 * tell an unmodified copy of a stock template from a build.
 *
 * <p>A player who opens a shipped part in the Train Builder and saves it uploads it, and their profile
 * then lists it as a build of theirs. The relay's {@code bundled_fingerprints} table is how it knows
 * better: a builder row whose blocks hash is in there is a copy, and the builder search, another
 * player's view of the profile and the "N builds" count all leave it out. This is the feeder — run by
 * {@code ./gradlew bundledFingerprints} on every release (see {@code release.yml}) and pushed by
 * {@code scripts/relay/push-bundled-fingerprints.py}.</p>
 *
 * <p><b>The hash has to be the relay's hash.</b> The relay stores {@code sha256(blocks)} of the blob a
 * save uploads, and that blob is {@link CarriageBlockSnapshot#encode} of a live capture. The same
 * bytes come out of a stored template through {@link CarriageSnapshotTemplate#fromTemplateTag} — which
 * is what a reconcile upload relies on, and what makes a fingerprint computed here from the {@code .nbt}
 * on disk match a row a player uploaded from a world. Verified against the live pool on 2026-09-15:
 * 310 rows matched the then-current bundle exactly.</p>
 *
 * <p>Walks the same directories {@link BuilderTemplateSource#slugs()} names — each one is where the
 * user-tier copy of a kind lives, and the shipped tree under {@code data/dungeontrain/} has the same
 * shape — so a kind the builder can author is a kind this fingerprints, and a new one appears here
 * without a change. Pure file I/O over a {@link Path}; no Minecraft bootstrap, because the two
 * reshapings it calls take no registries.</p>
 *
 * <pre>
 *   BundledFingerprints --resources src/main/resources/data/dungeontrain --out build/bundled-fingerprints.json
 *                       [--extra &lt;dir with the same layout&gt;]...
 * </pre>
 *
 * <p>{@code --extra} scans additional trees of the same shape — how the one-off backfill from git
 * history (every version of every template that ever shipped) was fed through the same code path.</p>
 */
public final class BundledFingerprints {

    private BundledFingerprints() {}

    /** One shipped template's identity and hash, as the relay files it. */
    public record Fingerprint(String hash, String kind, String subKind, String name, String path) {}

    public static void main(String[] args) throws Exception {
        Path resources = null;
        Path out = null;
        List<Path> extras = new ArrayList<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--resources" -> resources = Path.of(args[i + 1]);
                case "--out" -> out = Path.of(args[i + 1]);
                case "--extra" -> extras.add(Path.of(args[i + 1]));
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (resources == null || out == null) {
            throw new IllegalArgumentException("usage: --resources <data/dungeontrain dir> --out <file> [--extra <dir>]...");
        }
        List<Path> roots = new ArrayList<>();
        roots.add(resources);
        roots.addAll(extras);
        List<Fingerprint> all = new ArrayList<>();
        for (Path root : roots) all.addAll(scan(root));
        List<Fingerprint> unique = dedupe(all);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, toJson(unique), StandardCharsets.UTF_8);
        System.out.println("[BundledFingerprints] " + unique.size() + " fingerprints (" + all.size()
                + " files over " + roots.size() + " tree(s)) -> " + out);
    }

    /**
     * Every template under one tree, by the directories the builder's kinds live in. A directory a
     * kind has no shipped members of (carriage groups) simply contributes nothing.
     */
    public static List<Fingerprint> scan(Path root) throws IOException {
        List<Fingerprint> out = new ArrayList<>();
        for (BuilderTemplateSource.Slug slug : BuilderTemplateSource.slugs()) {
            Path dir = root.resolve(slug.subSlug());
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".nbt")).sorted().toList()) {
                    String name = file.getFileName().toString();
                    name = name.substring(0, name.length() - ".nbt".length());
                    out.add(new Fingerprint(hashOf(file), slug.kind().id(), slug.subKind(), name,
                            root.relativize(file).toString()));
                }
            }
        }
        return List.copyOf(out);
    }

    /** The relay's hash of the blob this template would upload as. */
    public static String hashOf(Path nbt) throws IOException {
        CompoundTag template;
        try (InputStream in = Files.newInputStream(nbt)) {
            template = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        String blocks = CarriageBlockSnapshot.encode(CarriageSnapshotTemplate.fromTemplateTag(template));
        return sha256Hex(blocks);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is mandatory in every JRE
        }
    }

    /** First occurrence per hash wins: the shipped tree is scanned first, so its name is the one kept. */
    static List<Fingerprint> dedupe(List<Fingerprint> all) {
        Map<String, Fingerprint> byHash = new LinkedHashMap<>();
        for (Fingerprint f : all) byHash.putIfAbsent(f.hash(), f);
        return List.copyOf(byHash.values());
    }

    /** The relay's request body: {@code {"fingerprints":[{hash,kind,subKind,name,path}]}}. */
    static String toJson(List<Fingerprint> list) {
        StringBuilder sb = new StringBuilder("{\"fingerprints\":[\n");
        for (int i = 0; i < list.size(); i++) {
            Fingerprint f = list.get(i);
            sb.append("  {\"hash\":\"").append(f.hash())
              .append("\",\"kind\":\"").append(f.kind())
              .append("\",\"subKind\":").append(f.subKind().isEmpty() ? "null" : "\"" + f.subKind() + "\"")
              .append(",\"name\":\"").append(jsonEscape(f.name()))
              .append("\",\"path\":\"").append(jsonEscape(f.path())).append("\"}")
              .append(i + 1 < list.size() ? ",\n" : "\n");
        }
        return sb.append("]}\n").toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
