package com.fridayclass.repository;

import com.fridayclass.entity.QaRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 问答记录数据访问接口。
 */
public interface QaRecordRepository extends JpaRepository<QaRecord, Long> {

    List<QaRecord> findBySessionIdOrderByAskedAtAsc(Long sessionId);

    /**
     * 某课堂的全部问答记录，<b>带上页信息</b>。
     *
     * <p>为什么必须 {@code left join fetch r.page}：组装响应要读 {@code pageNo}，而
     * {@code QaRecord.page} 是 {@code @ManyToOne(LAZY)}；在 {@code open-in-view=false} 下
     * 事务外取它会抛 {@code LazyInitializationException}。不 fetch 就退化成
     * 「每条记录一次 SELECT」的 N+1。
     *
     * <p>排序带上 {@code id}：{@code asked_at} 只有秒精度，
     * 同一秒内提的两个问题顺序不确定，加主键保证显示顺序稳定。
     */
    @Query("""
            select r from QaRecord r
            left join fetch r.page
            where r.session.id = :sessionId
            order by r.askedAt asc, r.id asc
            """)
    List<QaRecord> findBySessionWithPage(@Param("sessionId") Long sessionId);

    /** 只看某一页的问答（老师翻回上一页时有用）。 */
    @Query("""
            select r from QaRecord r
            left join fetch r.page
            where r.session.id = :sessionId and r.page.id = :pageId
            order by r.askedAt asc, r.id asc
            """)
    List<QaRecord> findBySessionAndPageWithPage(@Param("sessionId") Long sessionId,
                                                @Param("pageId") Long pageId);

    /**
     * 按幂等键查已有记录。学生重复点「发送」时靠它直接返回上次结果，
     * 不重复调模型、不重复入库。
     */
    Optional<QaRecord> findByClientRequestId(String clientRequestId);
}
