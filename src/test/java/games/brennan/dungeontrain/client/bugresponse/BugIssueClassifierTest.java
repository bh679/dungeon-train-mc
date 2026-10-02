package games.brennan.dungeontrain.client.bugresponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BugIssueClassifierTest {

    @Test
    @DisplayName("the picked option decides, whatever the comment says")
    void option() {
        assertEquals(BugIssue.LAG, BugIssueClassifier.classify("Lag", "the train derailed"));
        assertEquals(BugIssue.TRAIN_VANISHED, BugIssueClassifier.classify("Train Vanished", ""));
        assertEquals(BugIssue.TRAIN_VANISHED, BugIssueClassifier.classify(" train vanished ", null));
    }

    @Test
    @DisplayName("an Other comment about the train going wrong counts as train vanished")
    void keywords() {
        assertEquals(BugIssue.TRAIN_VANISHED, BugIssueClassifier.classify("Other", "my train DERAILED"));
        assertEquals(BugIssue.TRAIN_VANISHED, BugIssueClassifier.classify("Other", "it got duplciated"));
        assertEquals(BugIssue.TRAIN_VANISHED, BugIssueClassifier.classify("Other", "there were two\n trains"));
        assertEquals(BugIssue.TRAIN_VANISHED, BugIssueClassifier.classify("Other", "lost my train after a portal"));
        assertEquals(BugIssue.OTHER, BugIssueClassifier.classify("Other", "a villager fell through the floor"));
        assertEquals(BugIssue.OTHER, BugIssueClassifier.classify("Other", ""));
    }

    @Test
    @DisplayName("the bundled keyword list loads and is lower case")
    void bundled() {
        List<String> words = BugIssueClassifier.trainKeywords();
        assertTrue(words.contains("derail"));
        assertTrue(words.stream().allMatch(w -> w.equals(w.toLowerCase())));
    }

    @Test
    @DisplayName("a malformed keyword file yields no words rather than failing")
    void malformed() {
        assertEquals(List.of(), BugIssueClassifier.parseKeywords("[]"));
        assertEquals(List.of("abc"), BugIssueClassifier.parseKeywords("{\"train_vanished\": [\" ABC \", \"\", 3]}")
                .subList(0, 1));
    }
}
