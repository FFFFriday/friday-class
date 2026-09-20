package com.fridayclass.repository;

import com.fridayclass.entity.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 管理操作审计日志数据访问接口。
 *
 * <p>只写不删、只增不改：审计日志的价值就在于「当时发生了什么」这个事实本身，
 * 所以本接口<b>刻意不提供任何删除或修改方法</b>。
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    Page<AdminAuditLog> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Page<AdminAuditLog> findByActionOrderByCreatedAtDescIdDesc(String action, Pageable pageable);

    Page<AdminAuditLog> findByAdminIdOrderByCreatedAtDescIdDesc(Long adminId, Pageable pageable);

    Page<AdminAuditLog> findByAdminIdAndActionOrderByCreatedAtDescIdDesc(Long adminId,
                                                                        String action,
                                                                        Pageable pageable);
}
