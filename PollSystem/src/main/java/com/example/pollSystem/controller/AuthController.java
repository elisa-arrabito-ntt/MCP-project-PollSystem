package com.example.pollSystem.controller;

import com.example.pollSystem.dto.request.LoginRequestDto;
import com.example.pollSystem.dto.request.RegistrationRequestDto;
import com.example.pollSystem.dto.response.LoginResponseDto;
import com.example.pollSystem.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/rest/api/v0")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/registration")
    public ResponseEntity<Void> register(@Valid @RequestBody RegistrationRequestDto request) {
        log.info("Received registration request: username={}, email={}", request.getUsername(), request.getEmail());
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        LoginResponseDto response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}