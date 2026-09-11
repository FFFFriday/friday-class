package com.fridayclass.repository;

import com.fridayclass.entity.AiParseTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * AI 解析任务数据访问接口。
 */
public interface AiParseTaskRepository extends JpaRepository<AiParseTask, Long> {

    List<AiParseTask> findByCoursewareIdOrderByCreatedAtDesc(Long coursewareId);
}
