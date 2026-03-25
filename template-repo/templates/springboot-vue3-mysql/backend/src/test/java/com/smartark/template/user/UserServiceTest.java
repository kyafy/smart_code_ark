package com.smartark.template.user;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void listUsers_returnsAll() {
        UserEntity user = new UserEntity();
        user.setName("Alice");
        user.setEmail("alice@example.com");
        when(userRepository.findAll()).thenReturn(List.of(user));

        List<UserEntity> result = userService.listUsers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Alice");
    }

    @Test
    void create_savesNewUser() {
        CreateUserRequest request = new CreateUserRequest("Bob", "bob@example.com");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserEntity result = userService.create(request);

        assertThat(result.getName()).isEqualTo("Bob");
        assertThat(result.getEmail()).isEqualTo("bob@example.com");
        verify(userRepository).save(any(UserEntity.class));
    }

    @Test
    void create_throwsWhenEmailExists() {
        CreateUserRequest request = new CreateUserRequest("Bob", "bob@example.com");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(new UserEntity()));

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email already exists");

        verify(userRepository, never()).save(any());
    }
}
