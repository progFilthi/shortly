package com.shortly.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired refresh tokens.
 *
 * <p>Needed because nothing else removes them: a consumed or revoked row is kept deliberately,
 * so reuse can still be detected against it, but once the expiry passes it has no remaining use.
 * Without a sweep the table grows without bound.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenPurgeJob {

    private final RefreshTokenService refreshTokenService;

    /**
     * Hourly. Expiry is a 30-day horizon, so hourly granularity is far finer than any
     * correctness requirement and the query is index-backed on {@code expires_at}.
     */
    @Scheduled(fixedDelayString = "${auth.refresh-purge-interval:PT1H}")
    public void purge() {
        try {
            int removed = refreshTokenService.purgeExpired();
            if (removed > 0) {
                log.info("Purged {} expired refresh token(s)", removed);
            }
        } catch (RuntimeException e) {
            // A scheduled job that throws stops rescheduling in some containers, which would
            // silently end the sweep forever. Swallow and let the next tick retry.
            log.warn("Refresh token purge failed; will retry next interval", e);
        }
    }
}
