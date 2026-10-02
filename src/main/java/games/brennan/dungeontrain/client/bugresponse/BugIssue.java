package games.brennan.dungeontrain.client.bugresponse;

import java.util.Optional;

/**
 * A player-reported issue the bug-report response knows how to answer. {@link #ledgerId()} is the
 * string a changelog entry lists in its {@code addresses} field when it fixes the issue (kept in sync
 * with {@code changelog_io.VALID_ISSUES} and the ledger schema). {@link #OTHER} has no id: nothing in
 * the ledger addresses "something else".
 */
public enum BugIssue {
    LAG("lag"),
    TRAIN_VANISHED("train_vanished"),
    OTHER(null);

    private final String ledgerId;

    BugIssue(String ledgerId) {
        this.ledgerId = ledgerId;
    }

    public Optional<String> ledgerId() {
        return Optional.ofNullable(ledgerId);
    }
}
