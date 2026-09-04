package com.example.pollSystem.service;

import com.example.pollSystem.dto.request.RegistrationRequestDto;
import com.example.pollSystem.dto.response.LoginResponseDto;
import com.example.pollSystem.entity.User;
import com.example.pollSystem.exception.EmailAlreadyExistsException;
import com.example.pollSystem.exception.UsernameAlreadyExistsException;
import com.example.pollSystem.mapper.UserMapper;
import com.example.pollSystem.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.pollSystem.dto.request.LoginRequestDto;
import com.example.pollSystem.exception.InvalidCredentialsException;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponseDto login(LoginRequestDto request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> {
                    log.warn("Login failed: user {} not found", request.getUsername());
                    return new InvalidCredentialsException("Invalid username or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Login failed: wrong password for user {}", request.getUsername());
            throw new InvalidCredentialsException("Invalid username or password");
        }

        String token = jwtService.generateToken(user);
        LocalDateTime expiresAt = jwtService.getExpirationDateTime();

        log.info("Login successful for user {}", request.getUsername());

        return LoginResponseDto.builder()
                .token(token)
                .expiresAt(expiresAt)
                .build();
    }

    @Transactional
    public void register(RegistrationRequestDto request) {
        log.info("AuthService.register started for {}", request.getUsername());
        if (userRepository.existsByUsername(request.getUsername())) {
            log.warn("Registration failed: username {} already exists", request.getUsername());
            throw new UsernameAlreadyExistsException("Username already exists");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed: email {} already exists", request.getEmail());
            throw new EmailAlreadyExistsException("Email already exists");
        }

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        userRepository.save(user);

        log.info("User {} saved successfully", request.getUsername());
    }
}