package com.getjobs.worker.liepin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only confirmation of an established conversation, not of a sent greeting.
 * These selectors reuse the existing worker's card/control selectors; their current
 * behavior on a real Liepin page has not been verified. A chat header alone is not evidence.
 */
@Component
public class LiepinCommunicationObserver {
    static final String CHAT_ID_SELECTOR = "[data-tlg-elem-id='c_pc_search_job_listcard_chat_btn']";
    static final String BUTTON_SELECTOR = "button, .chat-btn, .talk-btn, .contact-btn";
    static final String JOB_LINK_SELECTOR = "a[href*='/job/']";
    private static final int DEFAULT_TIMEOUT_MILLIS = 15_000;
    private static final int ROUND_MILLIS = 250;
    private static final int MAX_READ_TIMEOUT_MILLIS = 249;
    private static final long MILLIS_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern JOB_LINK_ID = Pattern.compile("/job/(\\d+)(?:\\.shtml|\\.html|[/?#]|$)");

    @Value("${getjobs.liepin.communication-confirm-timeout-ms:15000}")
    private int communicationConfirmTimeoutMs = DEFAULT_TIMEOUT_MILLIS;
    private final LongSupplier nanoClock;

    public LiepinCommunicationObserver() {
        nanoClock = System::nanoTime;
    }

    // Package-private clock injection keeps the tests offline and free of real sleeps.
    LiepinCommunicationObserver(int timeoutMillis, LongSupplier nanoClock) {
        communicationConfirmTimeoutMs = timeoutMillis;
        this.nanoClock = nanoClock;
    }

    public void awaitEstablished(Page page, Long jobId, Runnable checkAllowed) {
        requireAllowed(checkAllowed);
        if (page == null || jobId == null || jobId <= 0) {
            throw LiepinTaskPausedException.communicationUnconfirmed(jobId, "确认沟通",
                    new IllegalArgumentException());
        }
        int timeoutMillis = communicationConfirmTimeoutMs > 0
                ? communicationConfirmTimeoutMs : DEFAULT_TIMEOUT_MILLIS;
        long started = nanoClock.getAsLong();
        long duration = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long maxRounds = ((long) timeoutMillis + ROUND_MILLIS - 1) / ROUND_MILLIS;
        ScanCursor cursor = new ScanCursor();
        RuntimeException lastFailure = null;

        // The round bound also terminates mock waits whose injected clock does not advance.
        for (long round = 0; round < maxRounds; round++) {
            requireAllowed(checkAllowed);
            long roundStarted = nanoClock.getAsLong();
            long remaining = duration - (roundStarted - started);
            if (remaining <= 0) break;
            ReadBudget budget = new ReadBudget(started, duration, roundStarted,
                    Math.min(TimeUnit.MILLISECONDS.toNanos(ROUND_MILLIS), remaining));
            try {
                if (observeRound(page, jobId, checkAllowed, budget, cursor)) return;
            } catch (RoundBudgetExhausted ignored) {
                // Reacquire the complete card list next round; retain no element binding.
            } catch (LiepinTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                requireAllowed(checkAllowed);
                lastFailure = failure;
            }

            requireAllowed(checkAllowed);
            long pauseNanos = budget.remainingNanos();
            if (pauseNanos > 0) {
                try {
                    requireAllowed(checkAllowed);
                    page.waitForTimeout(pauseNanos / (double) MILLIS_NANOS);
                    requireAllowed(checkAllowed);
                } catch (LiepinTaskPausedException | CancellationException stopped) {
                    throw stopped;
                } catch (RuntimeException failure) {
                    requireAllowed(checkAllowed);
                    lastFailure = failure;
                }
            }
        }
        requireAllowed(checkAllowed);
        // The factory retains only the failure type, never a page URL, body or original cause.
        throw LiepinTaskPausedException.communicationUnconfirmed(jobId, "确认沟通", lastFailure);
    }

    private boolean observeRound(Page page, Long jobId, Runnable checkAllowed,
                                 ReadBudget budget, ScanCursor cursor) {
        Locator cards = read(checkAllowed, budget, () -> page.locator(Locators.JOB_CARDS));
        int count = read(checkAllowed, budget, cards::count);
        if (count == 0) return false;
        int start = Math.floorMod(cursor.nextIndex, count);
        for (int scanned = 0; scanned < count; scanned++) {
            int index = (start + scanned) % count;
            cursor.nextIndex = (index + 1) % count;
            Locator card = read(checkAllowed, budget, () -> cards.nth(index));
            if (!read(checkAllowed, budget, card::isVisible)) continue;
            if (!jobId.equals(readJobId(card, checkAllowed, budget))) continue;
            Locator buttons = read(checkAllowed, budget, () -> card.locator(BUTTON_SELECTOR));
            int buttonCount = read(checkAllowed, budget, buttons::count);
            for (int buttonIndex = 0; buttonIndex < buttonCount; buttonIndex++) {
                final int current = buttonIndex;
                Locator button = read(checkAllowed, budget, () -> buttons.nth(current));
                if (!read(checkAllowed, budget, button::isVisible)) continue;
                String text = read(checkAllowed, budget, () -> button.textContent(
                        new Locator.TextContentOptions().setTimeout(budget.readTimeoutMillis())));
                if (!"继续聊".equals(normalize(text))) continue;
                // A dynamic locator can resolve to another card after rendering; check again.
                if (read(checkAllowed, budget, card::isVisible)
                        && read(checkAllowed, budget, button::isVisible)
                        && jobId.equals(readJobId(card, checkAllowed, budget))) return true;
            }
        }
        return false;
    }

    private Long readJobId(Locator card, Runnable checkAllowed, ReadBudget budget) {
        Long direct = parseDataId(attribute(card, "data-tlg-ext", checkAllowed, budget));
        if (direct != null) return direct;
        Locator chatData = read(checkAllowed, budget, () -> card.locator(CHAT_ID_SELECTOR));
        if (read(checkAllowed, budget, chatData::count) > 0) {
            Locator first = read(checkAllowed, budget, chatData::first);
            Long nested = parseDataId(attribute(first, "data-tlg-ext", checkAllowed, budget));
            if (nested != null) return nested;
        }
        Locator links = read(checkAllowed, budget, () -> card.locator(JOB_LINK_SELECTOR));
        int count = read(checkAllowed, budget, links::count);
        for (int i = 0; i < count; i++) {
            final int index = i;
            Locator link = read(checkAllowed, budget, () -> links.nth(index));
            String href = attribute(link, "href", checkAllowed, budget);
            Matcher matcher = JOB_LINK_ID.matcher(href == null ? "" : href);
            if (matcher.find()) {
                try {
                    return Long.valueOf(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    // An invalid ID is not confirmation evidence.
                }
            }
        }
        return null;
    }

    private String attribute(Locator locator, String name, Runnable checkAllowed, ReadBudget budget) {
        return read(checkAllowed, budget, () -> locator.getAttribute(name,
                new Locator.GetAttributeOptions().setTimeout(budget.readTimeoutMillis())));
    }

    private static Long parseDataId(String ext) {
        if (ext == null || ext.isBlank()) return null;
        try {
            JsonNode id = JSON.readTree(URLDecoder.decode(ext, StandardCharsets.UTF_8)).path("jobId");
            String value = id.asText("");
            return value.matches("[0-9]+") ? Long.valueOf(value) : null;
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Z}]+", "");
    }

    private <T> T read(Runnable checkAllowed, ReadBudget budget, Supplier<T> operation) {
        requireAllowed(checkAllowed);
        budget.requireRemaining();
        T value = operation.get();
        requireAllowed(checkAllowed);
        budget.requireRemaining();
        return value;
    }

    private static void requireAllowed(Runnable checkAllowed) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("用户取消猎聘任务");
        if (checkAllowed != null) checkAllowed.run();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("用户取消猎聘任务");
    }

    private static final class ScanCursor {
        private int nextIndex;
    }

    private final class ReadBudget {
        private final long started;
        private final long duration;
        private final long roundStarted;
        private final long roundDuration;

        private ReadBudget(long started, long duration, long roundStarted, long roundDuration) {
            this.started = started;
            this.duration = duration;
            this.roundStarted = roundStarted;
            this.roundDuration = roundDuration;
        }

        private long remainingNanos() {
            long now = nanoClock.getAsLong();
            return Math.min(duration - (now - started), roundDuration - (now - roundStarted));
        }

        private void requireRemaining() {
            if (remainingNanos() <= 0) throw new RoundBudgetExhausted();
        }

        private double readTimeoutMillis() {
            long remainingMillis = remainingNanos() / MILLIS_NANOS;
            if (remainingMillis < 1) throw new RoundBudgetExhausted();
            return Math.min(MAX_READ_TIMEOUT_MILLIS, remainingMillis);
        }
    }

    private static final class RoundBudgetExhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RoundBudgetExhausted() {
            super(null, null, false, false);
        }
    }
}
