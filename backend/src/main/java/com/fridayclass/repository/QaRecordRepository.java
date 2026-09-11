package com.fridayclass.repository;

import com.fridayclass.entity.QaRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 问答记录数据访问接口。
 */
public interface QaRecordRepository extends JpaRepository<QaRecord, Long> {

    List<QaRecord> findBySessionIdOrderByAskedAtAsc(Long sessionId);
}
