package com.shortly.authservice.impl;

import com.shortly.authservice.dto.AuthResponse;
import com.shortly.authservice.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapperImpl {

    public AuthResponse toAuthResponse(User user, String token){

        if(user == null){
            return null;
        }

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }
}
