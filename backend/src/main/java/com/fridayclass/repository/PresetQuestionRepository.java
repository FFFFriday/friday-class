package com.fridayclass.repository;

import com.fridayclass.entity.PresetQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 预置提问数据访问接口。
 */
public interface PresetQuestionRepository extends JpaRepository<PresetQuestion, Long> {

    List<PresetQuestion> findByPageIdOrderBySortOrderAsc(Long pageId);
}
