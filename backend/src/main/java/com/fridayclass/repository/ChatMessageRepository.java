package com.fridayclass.repository;

import com.fridayclass.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 课堂讨论区发言数据访问接口。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 拉某课堂的全部发言（含发言人），按 id 升序。
     *
     * <p>为什么 {@code join fetch m.user}：组装响应要读昵称，而
     * {@code ChatMessage.user} 是 {@code @ManyToOne(LAZY)}；在
     * {@code open-in-view=false} 下事务外取它会抛 {@code LazyInitializationException}，
     * 不 fetch 就退化成「每条发言一次 SELECT」的 N+1。
     *
     * <p>{@code page} 用 <b>left</b> join fetch：发言可以不挂在任何页上（列可空），
     * 用内连接会把这些发言整条丢掉。
     *
     * <p>排序按 {@code id} 而不是时间：时间只有秒精度，同一秒内的多条顺序不确定，
     * 而自增主键天然严格有序。
     */
    @Query("""
            select m from ChatMessage m
            join fetch m.user
            left join fetch m.page
            where m.session.id = :sessionId
            order by m.id asc
            """)
    List<ChatMessage> findBySessionWithUser(@Param("sessionId") Long sessionId);

    /**
     * 增量拉取：只要 {@code id} 大于 {@code afterId} 的发言。
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
    List<ChatMessage> findBySessionAfterId(@Param("sessionId") Long sessionId,
                                           @Param("afterId") Long afterId);

    /** 某课堂的有效发言条数（不含已删除）。用于课堂记录概览。 */
    @Query("""
            select count(m) from ChatMessage m
            where m.session.id = :sessionId and m.status = com.fridayclass.enums.ChatMessageStatus.NORMAL
            """)
    long countActiveBySession(@Param("sessionId") Long sessionId);
}
