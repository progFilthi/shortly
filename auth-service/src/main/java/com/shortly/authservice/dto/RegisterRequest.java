package com.shortly.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "Username is required.")
        String username,

        @Email(message = "Invalid email format.")
        @NotBlank(message = "Email is required.")
        String email,

        @Size(min = 6, message = "Password should be at least 6 characters.")
        @NotBlank(message = "Password is required.")
        String password
) {
}
