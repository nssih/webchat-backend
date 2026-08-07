package com.chat.project.chat.repository;

import com.chat.project.chat.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupRepository extends JpaRepository<Group, Long> {

    @Query("SELECT g FROM Group g JOIN FETCH g.owner JOIN g.members m WHERE m.user.id = :userId")
    List<Group> findByMemberUserId(@Param("userId") Long userId);

    Optional<Group> findByName(String name);
}