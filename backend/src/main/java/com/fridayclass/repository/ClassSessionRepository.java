package com.fridayclass.repository;

import com.fridayclass.entity.ClassSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 课堂/直播会话数据访问接口。
 */
public interface ClassSessionRepository extends JpaRepository<ClassSession, Long> {

    List<ClassSession> findByTeacherIdOrderByCreatedAtDesc(Long teacherId);
}
