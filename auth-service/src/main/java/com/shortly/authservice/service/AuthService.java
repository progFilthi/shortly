package com.shortly.authservice.service;

import com.shortly.authservice.dto.AuthResponse;
import com.shortly.authservice.dto.RegisterRequest;
import com.shortly.authservice.entity.User;
import com.shortly.authservice.impl.UserMapperImpl;
import com.shortly.authservice.jwt.JwtTokenProvider;
import com.shortly.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapperImpl userMapper;
    private final JwtTokenProvider jwtTokenProvider;


    public AuthResponse register(RegisterRequest request){

        /*
        * We first look if email exists in the db.
        * */
        if( userRepository.existsByEmail(request.email()) ){
            throw new IllegalArgumentException("Email already in use.");
        }

        /*
        * Create the new user using the user Object or entity
        * */
        User user = new User();

        user.setUsername(request.username());
        user.setEmail(request.email());

        //We hash the password using Bcrypt algo.
        user.setPassword(passwordEncoder.encode(request.password()));

        User savedUser = userRepository.save(user);

        /*
        * Generate the token using:
        * 1. UserId
        * 2. User email
        * */


        String token = jwtTokenProvider.generateToken(savedUser.getId(), savedUser.getEmail());

        /*
        * We return the saved user and the token along
        * */

        return userMapper.toAuthResponse(savedUser, token);
    }


}
