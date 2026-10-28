package com.getjobs.worker.boss;

import java.util.Set;

/** Applies the configured recent-recruiter activity requirement consistently. */
final class BossRecruiterActivityFilter {
    private static final Set<String> ACCEPTED = Set.of("在线", "当前在线", "刚刚活跃", "今日活跃", "今天活跃");

    private BossRecruiterActivityFilter() {}

    static boolean rejects(Boolean enabled, String activity) {
        return Boolean.TRUE.equals(enabled) && (activity == null || !ACCEPTED.contains(activity.trim()));
    }
}
