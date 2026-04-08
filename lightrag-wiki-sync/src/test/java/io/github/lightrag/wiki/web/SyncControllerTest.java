package io.github.lightrag.wiki.web;

import io.github.lightrag.wiki.scheduler.WikiSyncScheduler;
import io.github.lightrag.wiki.sync.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code POST /sync/trigger} endpoint in isolation.
 *
 * <p>The scheduler and syncer are mocked — these tests only exercise HTTP
 * plumbing: routing, status codes, and JSON serialisation of {@link SyncResult}.
 */
@WebMvcTest(SyncController.class)
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WikiSyncScheduler scheduler;

    @Test
    void triggerSyncReturns200WithSyncResultBody() throws Exception {
        var result = new SyncResult(3, 1, 0, 0, "abc1234", "def5678",
                Duration.ofSeconds(2), List.of());
        when(scheduler.runSync()).thenReturn(result);

        mockMvc.perform(post("/sync/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filesUploaded").value(3))
                .andExpect(jsonPath("$.filesDeleted").value(1))
                .andExpect(jsonPath("$.filesFailed").value(0))
                .andExpect(jsonPath("$.filesSkipped").value(0))
                .andExpect(jsonPath("$.fromCommit").value("abc1234"))
                .andExpect(jsonPath("$.toCommit").value("def5678"))
                .andExpect(jsonPath("$.errors").isEmpty());

        verify(scheduler).runSync();
    }

    @Test
    void triggerSyncReturns200EvenWhenFilesFailedIsNonZero() throws Exception {
        var result = new SyncResult(2, 0, 1, 0, "aaa1111", null,
                Duration.ofMillis(500), List.of("Home.md: Upload failed — HTTP 500"));
        when(scheduler.runSync()).thenReturn(result);

        mockMvc.perform(post("/sync/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filesUploaded").value(2))
                .andExpect(jsonPath("$.filesFailed").value(1))
                .andExpect(jsonPath("$.toCommit").isEmpty())
                .andExpect(jsonPath("$.errors[0]").value("Home.md: Upload failed — HTTP 500"));
    }

    @Test
    void triggerSyncReturns200ForNoChangesResult() throws Exception {
        var result = SyncResult.noChanges("deadbeef", Duration.ofMillis(12));
        when(scheduler.runSync()).thenReturn(result);

        mockMvc.perform(post("/sync/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filesUploaded").value(0))
                .andExpect(jsonPath("$.filesDeleted").value(0))
                .andExpect(jsonPath("$.filesFailed").value(0))
                .andExpect(jsonPath("$.fromCommit").value("deadbeef"))
                .andExpect(jsonPath("$.toCommit").value("deadbeef"));
    }

    @Test
    void triggerSyncDelegatesExactlyOnceToScheduler() throws Exception {
        when(scheduler.runSync()).thenReturn(SyncResult.noChanges("x", Duration.ZERO));

        mockMvc.perform(post("/sync/trigger")).andExpect(status().isOk());
        mockMvc.perform(post("/sync/trigger")).andExpect(status().isOk());

        // Two separate POST calls → two delegations
        verify(scheduler, org.mockito.Mockito.times(2)).runSync();
    }
}
