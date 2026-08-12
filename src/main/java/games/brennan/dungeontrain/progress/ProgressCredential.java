package games.brennan.dungeontrain.progress;

/**
 * This install's handle on a relay-side progress account: the bearer {@code secret} that authorises
 * reads and writes, plus the account facts the relay reported back.
 *
 * <p>{@code kind} is the namespace the account lives in, and it is the whole reason this record has a
 * {@code principal} separate from the player's uuid:</p>
 *
 * <ul>
 *   <li>{@code mojang} — the player proved a real Mojang account through Minecraft's own join-server
 *       handshake ({@link ProgressAccountFile} drives it). {@code principal} is their verified uuid.</li>
 *   <li>{@code token} — everyone else: offline/cracked launchers, or anyone whose verification simply
 *       failed. {@code principal} is a relay-minted random id.</li>
 * </ul>
 *
 * <p>The split matters because an offline-mode uuid is only {@code MD5("OfflinePlayer:" + name)} — every
 * player called "Steve" on every install shares one. Keying accounts on the bare uuid would let the
 * first offline Steve claim the identity of the <em>premium</em> Steve who has not signed up yet. An
 * unverified player therefore gets a minted principal and can never occupy a Mojang namespace.</p>
 *
 * <p>{@code secret} is sensitive: it is the only thing standing between an attacker and the player's
 * Ender Chest. It is written to the player's own config dir and, in phase 1, handed to the server only
 * when the player's own client owns that server (single-player / LAN host). It is never logged.</p>
 */
public record ProgressCredential(String accountId, String kind, String principal, String secret,
                                 String uuid, String name) {

    public static final String KIND_MOJANG = "mojang";
    public static final String KIND_TOKEN = "token";

    /** True when this account is backed by a verified Mojang identity rather than a minted token. */
    public boolean isVerified() {
        return KIND_MOJANG.equals(kind);
    }

    /** True when the credential carries everything needed to talk to the relay. */
    public boolean isUsable() {
        return secret != null && !secret.isBlank() && accountId != null && !accountId.isBlank();
    }

    /** A copy carrying a different secret — used when a re-verification issues this device a new one. */
    public ProgressCredential withSecret(String newSecret) {
        return new ProgressCredential(accountId, kind, principal, newSecret, uuid, name);
    }

    /**
     * Never expose the secret in a log line or a crash report. {@link #toString} is overridden rather
     * than left to the record default, which would print every component.
     */
    @Override
    public String toString() {
        return "ProgressCredential[" + kind + ":" + accountId + "]";
    }
}
