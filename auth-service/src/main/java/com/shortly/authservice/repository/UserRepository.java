package com.shortly.authservice.repository;

import com.shortly.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    /**
     * Resolves a sign-in identifier, which the client may present as either a username or an
     * email.
     * <p>
     * Returns two candidates rather than one so the caller can run a single, constant-time
     * password check. Querying one and falling back to the other would leak which identifier
     * exists through response timing.
     */
    Optional<User> findByUsernameIgnoreCase(String identifier);

    Optional<User> findByEmailIgnoreCase(String identifier);
}
