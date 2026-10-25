package com.carrental.modules.user.repository;

import com.carrental.modules.user.entity.Account;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, String> {
    java.util.Optional<Account> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Account e where e.id = :id")
    java.util.Optional<Account> lock(@Param("id") String id);
}
