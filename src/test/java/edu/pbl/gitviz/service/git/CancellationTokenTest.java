package edu.pbl.gitviz.service.git;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTokenTest {

    @Test
    void startsNotCancelled() {
        assertFalse(new CancellationToken().isCancelled());
    }

    @Test
    void runsRegisteredActionsOnceWhenCancelled() {
        CancellationToken token = new CancellationToken();
        List<String> calls = new ArrayList<>();
        token.onCancel(() -> calls.add("a"));
        token.onCancel(() -> calls.add("b"));

        token.cancel();
        token.cancel();

        assertTrue(token.isCancelled());
        assertEquals(List.of("a", "b"), calls);
    }

    @Test
    void doesNotRunActionsWhileNotCancelled() {
        CancellationToken token = new CancellationToken();
        List<String> calls = new ArrayList<>();
        token.onCancel(() -> calls.add("never"));

        assertEquals(List.of(), calls);
    }

    @Test
    void runsActionsRegisteredAfterCancellationImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        List<String> calls = new ArrayList<>();

        token.onCancel(() -> calls.add("late"));

        assertEquals(List.of("late"), calls);
    }
}
