package com.shortly.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A sign-out request.
 * <p>
 * {@code allDevices} is a separate field rather than a second endpoint so the two intents cannot
 * be confused by a client that forgets to call the right URL.
 *
 * @param refreshToken the session to end; required even for {@code allDevices}, as proof the
 *                     caller holds a live session for this account
 * @param allDevices   when true, every session for the user is revoked, not just this one
 */
public record LogoutRequest(

        @NotBlank(message = "refreshToken is required.")
        String refreshToken,

        boolean allDevices
) {
}
