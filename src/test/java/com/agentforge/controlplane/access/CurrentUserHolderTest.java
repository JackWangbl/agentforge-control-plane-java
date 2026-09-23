package com.agentforge.controlplane.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CurrentUserHolderTest {

    @Test
    void callCarriesTheRequestUserOntoAnotherThread() throws Exception {
        CurrentUser user = new CurrentUser(1L, "linmo", "林默", 1L, 1L, "默认租户", 1L, "管理员", List.of("*"));
        CurrentUserHolder.set(user);
        CurrentUser captured = CurrentUserHolder.get();
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<CurrentUser> leftover = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            seen.set(CurrentUserHolder.call(captured, () -> {
                CurrentUser current = CurrentUserHolder.get();
                return current == null ? "" : current.getUsername();
            }));
            leftover.set(CurrentUserHolder.get());
        });
        worker.start();
        worker.join();
        assertEquals("linmo", seen.get());
        assertNull(leftover.get());
        assertEquals("linmo", CurrentUserHolder.get().getUsername());
        CurrentUserHolder.clear();
    }
}
