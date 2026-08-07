package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByTelegramUserId(Long telegramUserId);

    @Query("SELECT user.id FROM User user WHERE user.telegramUserId = :telegramUserId")
    Optional<Long> findInternalIdByTelegramUserId(@Param("telegramUserId") Long telegramUserId);

    Optional<User> findByEmployeeCode(String employeeCode);

    List<User> findAllByFullNameIgnoreCase(String fullName);

    Optional<User> findByPhoneNumber(String phoneNumber);

    @Query("SELECT user FROM User user WHERE user.id = :id")
    Optional<User> findByInternalId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT user FROM User user WHERE user.id = :id")
    Optional<User> findLockedById(@Param("id") long id);

    boolean existsByPhoneNumberAndIdNot(String phoneNumber, Long id);

    boolean existsByEmployeeCodeAndIdNot(String employeeCode, Long id);

    @Query("""
            SELECT user
            FROM User user
            WHERE lower(trim(user.departmentName)) = lower(:departmentName)
              AND (coalesce(:unitName, '') = ''
                   OR lower(trim(user.unitName)) = lower(:unitName))
            """)
    List<User> findCurrentTeamMembers(
            @Param("departmentName") String departmentName,
            @Param("unitName") String unitName
    );

}
