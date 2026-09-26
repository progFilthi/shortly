package com.shortly.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes expired refresh tokens. Needed because nothing else removes them: a consumed or revoked
 * it has no remaining use. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenPurgeJob {

    private final RefreshTokenService refreshTokenService;

    /** Hourly. */
    @Scheduled(fixedDelayString = "${auth.refresh-purge-interval:PT1H}")
    public void purge() {
        try {
            int removed = refreshTokenService.purgeExpired();
            if (removed > 0) {
                log.info("Purged {} expired refresh token(s)", removed);
            }
        } catch (RuntimeException e) {
            // A scheduled job that throws stops rescheduling in some containers, which would silently end the
            // sweep forever. Swallow and let the next tick retry.
            log.warn("Refresh token purge failed; will retry next interval", e);
        }
    }
}
