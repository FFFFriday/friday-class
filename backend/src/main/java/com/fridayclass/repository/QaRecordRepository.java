package com.fridayclass.repository;

import com.fridayclass.entity.QaRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    // ── 课堂记录（M5） ─────────────────────────────────────────

    /**
     * 某课堂的全部问答，<b>带上提问学生</b>与页信息。课堂记录按学生分组用。
     *
     * <p>排序按「学生 ID 升序、记录 ID 升序」：调用方拿到的就是已经按学生聚好的顺序，
     * 直接顺序遍历即可分组，不必再排序。
     */
    @Query("""
            select r from QaRecord r
            join fetch r.student
            left join fetch r.page
            where r.session.id = :sessionId
            order by r.student.id asc, r.id asc
            """)
    List<QaRecord> findBySessionWithStudentAndPage(@Param("sessionId") Long sessionId);

    /**
     * 同上，但<b>限制条数</b>。AI 智能体读课堂记录时用它。
     *
     * <p>不加 {@code Pageable} 的那个版本会把整节课的问答（连带学生与页信息）
     * 一次性拉进内存，再在 Java 里截断 —— 截断是省给模型的，
     * 数据库那趟该拉多少还是多少。一节课几百条问答时，差别就是几十兆的堆占用。
     *
     * <p>{@code join fetch} 配 {@code Pageable} 的注意点：这里两个关联
     * （student / page）都是 to-one，不是集合，所以不会触发 Hibernate 那条
     * 「分页查询里 join fetch 集合会被内存分页」的警告，可以放心用。
     */
    @Query("""
            select r from QaRecord r
            join fetch r.student
            left join fetch r.page
            where r.session.id = :sessionId
            order by r.student.id asc, r.id asc
            """)
    List<QaRecord> findBySessionWithStudentAndPage(@Param("sessionId") Long sessionId,
                                                   Pageable pageable);

    /** 某课堂的问答条数（课堂记录概览 + 总结的空记录拦截都用它）。 */
    long countBySessionId(Long sessionId);

    // ── AI 会话（M4） ──────────────────────────────────────────

    /**
     * 某会话的全部问答，正序。会话详情页用。
     *
     * <p>不带 {@code join fetch r.page}：会话详情不显示页码，
     * 而 {@code page} 对课后提问恒为 null；为了一个用不到的字段去做外连接不划算。
     * 需要页码时用 {@link #findBySessionWithPage}。
     */
    @Query("""
            select r from QaRecord r
            where r.conversationId = :conversationId
            order by r.id asc
            """)
    List<QaRecord> findByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 某会话**最近** N 条，倒序取出。
     *
     * <p>倒序取是有意的：要的是「最近几轮」，正序取前 N 条拿到的是会话最早那几轮，
     * 上下文就对不上了。取完由调用方反转成正序再拼进提示词。
     */
    @Query("""
            select r from QaRecord r
            where r.conversationId = :conversationId
            order by r.id desc
            """)
    List<QaRecord> findRecentByConversationId(@Param("conversationId") Long conversationId,
                                              Pageable pageable);

    /** 某会话的问答条数。一条问答记录 = 一轮对话。 */
    long countByConversationId(Long conversationId);

    /**
     * 批量统计多个会话的条数，供会话列表一次性取回。
     * 不这么做的话列表里每个会话都要单独 count 一次（N+1）。
     */
    @Query("""
            select new com.fridayclass.repository.ConversationCount(r.conversationId, count(r))
            from QaRecord r
            where r.conversationId in :ids
            group by r.conversationId
            """)
    List<ConversationCount> countByConversationIds(@Param("ids") Collection<Long> ids);
}
