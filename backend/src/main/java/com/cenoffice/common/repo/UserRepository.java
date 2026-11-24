package com.cenoffice.common.repo;

import com.cenoffice.common.domain.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    boolean existsByUsername(String username);
    long countByRole(com.cenoffice.common.domain.Enums.Role role);
}
