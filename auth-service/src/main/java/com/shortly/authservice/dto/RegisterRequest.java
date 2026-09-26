package com.shortly.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration details.
 *
 * <p>The length limits exist for the database as much as for validation: without them a
 * multi-megabyte title is accepted, truncated to the column width, and the user is never told.
 *
 * <p>The password has no composition rules on purpose. Length is what actually resists
 * guessing; rules mostly produce {@code Passw0rd!}.
 */
public record RegisterRequest(

        @NotBlank(message = "Username is required.")
        @Size(min = 3, max = 32, message = "Username must be between 3 and 32 characters.")
        @Pattern(
                regexp = "^[A-Za-z0-9_.-]+$",
                message = "Username may only contain letters, digits, and _ . -")
        String username,

        @NotBlank(message = "Email is required.")
        @Email(message = "Must be a valid email address.")
        @Size(max = 320, message = "Email must be at most 320 characters.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(max = 128, message = "Password must be at most 128 characters.")
        String password
) {
}
