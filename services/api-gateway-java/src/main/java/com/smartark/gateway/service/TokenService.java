package com.smartark.gateway.service;

import com.smartark.gateway.entity.TokenEntity;
import com.smartark.gateway.repository.TokenRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenService {
    private final TokenRepository tokenRepository;

    public TokenService(TokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    public String createToken(String userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        TokenEntity entity = new TokenEntity();
        entity.setToken(token);
        entity.setUserId(userId);
        entity.setCreatedAt(Instant.now());
        tokenRepository.save(entity);
        return token;
    }

    public Optional<String> parseUserId(String token) {
        return tokenRepository.findById(token).map(TokenEntity::getUserId);
    }
}
