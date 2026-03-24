package com.smartark.template.user;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<UserEntity> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public UserEntity create(CreateUserRequest request) {
        userRepository.findByEmail(request.email()).ifPresent(user -> {
            throw new IllegalArgumentException("Email already exists");
        });

        UserEntity entity = new UserEntity();
        entity.setName(request.name());
        entity.setEmail(request.email());
        return userRepository.save(entity);
    }
}
