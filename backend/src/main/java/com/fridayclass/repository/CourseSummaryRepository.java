package com.fridayclass.repository;

import com.fridayclass.entity.CourseSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 课程总结数据访问接口。
 */
public interface CourseSummaryRepository extends JpaRepository<CourseSummary, Long> {

    Optional<CourseSummary> findBySessionId(Long sessionId);
}
