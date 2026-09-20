package com.shortly.authservice.repository;

import com.shortly.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String > {
    boolean existsByEmail( String email);
}
