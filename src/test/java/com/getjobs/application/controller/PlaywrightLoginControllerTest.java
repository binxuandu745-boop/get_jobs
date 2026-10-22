package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaywrightLoginControllerTest {
    @Test
    void reopeningWhileTaskRunsIsRejected() {
        PlaywrightManager manager = mock(PlaywrightManager.class);
        BossJobService jobs = mock(BossJobService.class);
        when(jobs.isRunning()).thenReturn(true);
        var response = new PlaywrightController(manager, jobs).reopen();
        assertEquals(409, response.getStatusCode().value());
        verifyNoInteractions(manager);
    }

    @Test
    void reopeningIdleBrowserDoesNotStartDelivery() {
        PlaywrightManager manager = mock(PlaywrightManager.class);
        BossJobService jobs = mock(BossJobService.class);
        var response = new PlaywrightController(manager, jobs).reopen();
        assertEquals(200, response.getStatusCode().value());
        verify(manager).reopenBrowser();
        verify(jobs).isRunning();
        verifyNoMoreInteractions(jobs);
    }
}
