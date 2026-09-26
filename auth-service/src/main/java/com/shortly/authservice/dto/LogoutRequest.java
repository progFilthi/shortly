package com.shortly.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/** A sign-out request. {@code allDevices} is a separate field rather than a second endpoint so the
 * two intents cannot be confused by a client that forgets to call the right URL. */
public record LogoutRequest(

        @NotBlank(message = "refreshToken is required.")
        String refreshToken,

        boolean allDevices
) {
}
