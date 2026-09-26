package com.shortly.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sign-in credentials.
 *
 * @param identifier username or email, whichever the user remembers
 * @param password  plaintext, verified against the stored hash and never logged
 */
public record LoginRequest(

        @NotBlank(message = "Username or email is required.")
        @Size(max = 320, message = "Identifier must be at most 320 characters.")
        String identifier,

        @NotBlank(message = "Password is required.")
        // Bounded to stop an attacker submitting a megabyte of "password" and forcing a huge
        // BCrypt comparison. The real limit is enforced in the service, which knows the policy.
        @Size(max = 128, message = "Password must be at most 128 characters.")
        String password
) {
}
