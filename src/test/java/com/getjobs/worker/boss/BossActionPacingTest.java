package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline regression: cancellation while preparing a greeting must never send it. */
class BossActionPacingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopDuringPreSendWaitPreventsSendingGreetingEvenAfterASlowNavigation(boolean navigationTimedOut) throws Throwable {
        Page listPage = usablePage();
        Page detailPage = usablePage();
        BossService service = mock(BossService.class);
        AiService ai = mock(AiService.class);
        Boss boss = new Boss(service, ai);
        boss.setPage(listPage);
        BossConfig config = new BossConfig();
        config.setDebugger(false);
        config.setEnableAI(false);
        config.setSendImgResume(false);
        config.setSayHi("Test greeting");
        boss.setConfig(config);
        AtomicBoolean stopRequested = new AtomicBoolean();
        boss.setShouldStopCallback(stopRequested::get);

        Locator more = mock(Locator.class);
        when(listPage.locator("a.more-job-btn")).thenReturn(more);
        when(more.count()).thenReturn(1);
        when(more.first()).thenReturn(more);
        when(more.getAttribute("href")).thenReturn("/job_detail/test-id.html");
        BrowserContext context = mock(BrowserContext.class);
        when(listPage.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detailPage);
        if (navigationTimedOut) {
            doAnswer(ignored -> {
                when(detailPage.url()).thenReturn("https://www.zhipin.com/job_detail/test-id.html");
                throw new TimeoutError("slow domcontentloaded");
            }).when(detailPage).navigate(anyString(), any(Page.NavigateOptions.class));
        }
        Locator chat = mock(Locator.class);
        when(detailPage.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(chat);
        when(chat.count()).thenReturn(1);
        when(chat.first()).thenReturn(chat);
        when(chat.textContent()).thenReturn("立即沟通");
        Locator input = mock(Locator.class);
        when(detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area"))
                .thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.first()).thenReturn(input);
        when(input.isVisible()).thenReturn(true);
        when(input.evaluate("el => el.tagName.toLowerCase()")).thenReturn("textarea");
        AtomicBoolean greetingPrepared = new AtomicBoolean();
        doAnswer(ignored -> {
            greetingPrepared.set(true);
            return null;
        }).when(input).fill("Test greeting");
        doAnswer(ignored -> {
            if (greetingPrepared.get()) stopRequested.set(true);
            return null;
        }).when(detailPage).waitForTimeout(anyDouble());

        Job job = new Job();
        job.setJobName("Java internship");
        Method submit = Boss.class.getDeclaredMethod("resumeSubmission", String.class, Job.class);
        submit.setAccessible(true);
        try (MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> submit.invoke(boss, "Java", job));
            assertInstanceOf(CancellationException.class, failure.getCause());
        }
        assertTrue(greetingPrepared.get());
        verify(detailPage).close();
        verify(detailPage, never()).locator("div.send-message, button[type='send'].btn-send, button.btn-send");
        verifyNoInteractions(service, ai);
        assertTrue(boss.getResultList().isEmpty());
    }

    private Page usablePage() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        Locator verification = mock(Locator.class);
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        return page;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void optionalImagePathCannotSwallowCancellationOrVerification(boolean verificationRequired) throws Exception {
        Page page = usablePage();
        Boss boss = new Boss(mock(BossService.class), mock(AiService.class));
        boss.setPage(page);
        boss.setShouldStopCallback(() -> !verificationRequired);
        if (verificationRequired) {
            when(page.url()).thenReturn("https://www.zhipin.com/web/passport/zp/verify.html");
        }
        Method sendImage = Boss.class.getDeclaredMethod("sendImageResume", Page.class);
        sendImage.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> sendImage.invoke(boss, page));

        if (verificationRequired) {
            assertInstanceOf(BossTaskPausedException.class, failure.getCause());
            assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION,
                    ((BossTaskPausedException) failure.getCause()).getReason());
        } else {
            assertInstanceOf(CancellationException.class, failure.getCause());
        }
        verify(page, never()).waitForFileChooser(any(Runnable.class));
        verify(page, never()).locator("div.btn-sendimg[aria-label='发送图片'], div[aria-label='发送图片'].btn-sendimg");
    }
}
