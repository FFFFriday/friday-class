package com.fridayclass.repository;

import com.fridayclass.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 课堂讨论区发言数据访问接口。
 *
 * <p>三个查询都 {@code join fetch m.user}：组装响应要读昵称，而
 * {@code ChatMessage.user} 是 {@code @ManyToOne(LAZY)}；在
 * {@code open-in-view=false} 下事务外取它会抛 {@code LazyInitializationException}，
 * 不 fetch 就退化成「每条发言一次 SELECT」的 N+1。
 *
 * <p>{@code page} 用 <b>left</b> join fetch：发言可以不挂在任何页上（列可空），
 * 用内连接会把这些发言整条丢掉。
 *
 * <p>排序一律按 {@code id} 而不是时间：时间只有秒精度，同一秒内的多条顺序不确定，
 * 而自增主键天然严格有序。
 *
 * <p><b>关于分页与 join fetch</b>：这里 fetch 的都是 {@code @ManyToOne}（to-one），
 * Hibernate 能安全地在 SQL 层加 limit；只有当 fetch 的是集合（to-many）时
 * 才会退化成「全查出来再内存分页」并告警。所以传 {@link Pageable} 是有效且高效的。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 按课堂正序取一页（从最早开始）。
     * 它的调用方是「最近 N 条」路径的兜底，以及课堂记录导出。
     */
    @Query("""
            select m from ChatMessage m
            join fetch m.user
            left join fetch m.page
            where m.session.id = :sessionId
            order by m.id asc
            """)
    List<ChatMessage> findWithUserBySession(@Param("sessionId") Long sessionId, Pageable pageable);

    /**
     * 增量拉取：只要 {@code id > afterId} 的发言，正序。
     *
     * <p>学生重连或断线补拉时用——不必把整节课的发言重传一遍。
     * 走 {@code idx_chat_session (session_id, id)} 索引。
     */
    @Query("""
            select m from ChatMessage m
            join fetch m.user
            left join fetch m.page
            where m.session.id = :sessionId and m.id > :afterId
            order by m.id asc
            """)
    List<ChatMessage> findWithUserAfterId(@Param("sessionId") Long sessionId,
                                          @Param("afterId") Long afterId,
                                          Pageable pageable);

    /**
     * 最近 N 条，<b>倒序</b>取出。
     *
     * <p>进入课堂时用这条，而不是「正序取前 50 条」——后者拿到的是
     * 这节课<b>最早</b>的 50 条，学生一进来看到的是开场白，后面聊的全没有。
     * 取出后由调用方反转成正序。
     */
    @Query("""
            select m from ChatMessage m
            join fetch m.user
            left join fetch m.page
            where m.session.id = :sessionId
            order by m.id desc
            """)
    List<ChatMessage> findWithUserLatest(@Param("sessionId") Long sessionId, Pageable pageable);

    /**
     * 比 {@code beforeId} 更早的一页，<b>倒序</b>取出，供「加载更早的发言」用。
     *
     * <p>与 {@link #findWithUserLatest} 同理，取出后由调用方反转成正序。
     * 用 {@code id < beforeId} 而不是「offset 分页」：讨论区会不断有新消息插进来，
     * offset 分页在有人发言时会错位重复——游标分页没有这个问题。
     */
    @Query("""
            select m from ChatMessage m
            join fetch m.user
            left join fetch m.page
            where m.session.id = :sessionId and m.id < :beforeId
            order by m.id desc
            """)
    List<ChatMessage> findWithUserBeforeId(@Param("sessionId") Long sessionId,
                                           @Param("beforeId") Long beforeId,
                                           Pageable pageable);

    /** 某课堂的有效发言条数（不含已撤回）。用于课堂记录概览与总结前置校验。 */
    @Query("""
            select count(m) from ChatMessage m
            where m.session.id = :sessionId
              and m.status = com.fridayclass.enums.ChatMessageStatus.NORMAL
            """)
    long countActiveBySession(@Param("sessionId") Long sessionId);
}
