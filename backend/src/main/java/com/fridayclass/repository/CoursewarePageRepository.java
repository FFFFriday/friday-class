package com.fridayclass.repository;

import com.fridayclass.entity.CoursewarePage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 课件页数据访问接口。
 */
public interface CoursewarePageRepository extends JpaRepository<CoursewarePage, Long> {

    List<CoursewarePage> findByCoursewareIdOrderByPageNoAsc(Long coursewareId);
}
