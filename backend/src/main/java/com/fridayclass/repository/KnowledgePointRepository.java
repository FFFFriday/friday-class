package com.fridayclass.repository;

import com.fridayclass.entity.KnowledgePoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 知识点数据访问接口。
 */
public interface KnowledgePointRepository extends JpaRepository<KnowledgePoint, Long> {

    List<KnowledgePoint> findByPageIdOrderBySortOrderAsc(Long pageId);
}
