package com.getjobs.worker.boss;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory record of jobs already claimed by one delivery task.
 *
 * <p>The caller must reset this ledger at the beginning of a new task. A claim
 * records an attempted visit, not a successful delivery. It is intentionally
 * independent of database delivery history and does not retain URLs or tokens.
 */
public final class BossTaskVisitLedger {
    private static final Pattern EXPLICIT_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern JOB_DETAIL_PATH =
            Pattern.compile("/?job_detail/([A-Za-z0-9_-]+)\\.html");

    private final Set<String> visitedIds = new HashSet<>();

    /**
     * Claims a stable job ID or a BOSS job-detail href before opening its details.
     *
     * @return false only when this task already claimed the same identifiable
     * job; true for an unseen job or an unidentifiable reference. Missing IDs
     * must not accidentally collapse different cards into a single job.
     */
    public synchronized boolean claim(String rawJobReference) {
        String identity = identityOf(rawJobReference);
        return identity == null || visitedIds.add(identity);
    }

    /** Starts a fresh task without changing persistent delivery history. */
    public synchronized void reset() {
        visitedIds.clear();
    }

    static String identityOf(String rawJobReference) {
        if (rawJobReference == null) {
            return null;
        }
        String reference = rawJobReference.trim();
        if (reference.isEmpty() || reference.equalsIgnoreCase("null")
                || reference.equalsIgnoreCase("undefined")) {
            return null;
        }
        // Bare IDs are accepted only because the caller explicitly supplies an
        // ID field here. Titles and company names must never be passed as IDs.
        if (EXPLICIT_ID.matcher(reference).matches()) {
            return reference;
        }
        try {
            URI uri = new URI(reference);
            if (uri.isAbsolute() && !"https".equalsIgnoreCase(uri.getScheme())
                    && !"http".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            if (uri.isAbsolute() || uri.getRawAuthority() != null) {
                String host = uri.getHost();
                if (host == null) {
                    return null;
                }
                String normalizedHost = host.toLowerCase(Locale.ROOT);
                if (!normalizedHost.equals("zhipin.com")
                        && !normalizedHost.endsWith(".zhipin.com")) {
                    return null;
                }
            }
            String path = uri.getRawPath();
            if (path == null) {
                return null;
            }
            Matcher match = JOB_DETAIL_PATH.matcher(path);
            if (!match.matches()) {
                return null;
            }
            String identity = match.group(1);
            return identity.equalsIgnoreCase("null") || identity.equalsIgnoreCase("undefined")
                    ? null : identity;
        } catch (URISyntaxException ignored) {
            // Treat malformed or unfamiliar references as unknown, not as a
            // shared identity, and never print the potentially sensitive URL.
            return null;
        }
    }
}
