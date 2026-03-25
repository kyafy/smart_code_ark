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
        // Keep business uniqueness rules in the service layer so generated
        // services have a clear place to enforce domain constraints.
        userRepository.findByEmail(request.email()).ifPresent(user -> {
            throw new IllegalArgumentException("Email already exists");
        });

        // Map the validated request into an entity in one compact block.
        // This keeps field assignment readable when future fields are added.
        UserEntity entity = new UserEntity();
        entity.setName(request.name());
        entity.setEmail(request.email());
        return userRepository.save(entity);
    }
}
