package com.fridayclass.repository;

import com.fridayclass.entity.SessionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 课堂参与者数据访问接口。
 */
public interface SessionParticipantRepository extends JpaRepository<SessionParticipant, Long> {

    /**
     * 取某人在某课堂的参与记录。
     *
     * <p>靠唯一键 {@code uk_part_session_user} 保证最多一条，
     * 所以可以安全地返回 {@code Optional}。
     */
    Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId);

    /** 某课堂的全体参与记录（含已离开），按首次进入时间升序。课堂记录用。 */
    List<SessionParticipant> findBySessionIdOrderByJoinedAtAscIdAsc(Long sessionId);

    /**
     * 当前仍在场的人（{@code left_at IS NULL}）。
     *
     * <p>注意这只是「没有正常发出离开消息」的集合，不等于「连接还活着」——
     * 浏览器直接崩掉或拔网线时不会发离开消息，那类掉线靠
     * {@code last_seen_at} 与心跳判定。实时在线名单以
     * {@code SessionRegistry} 的内存态为准，这里只用作出席统计。
     */
    List<SessionParticipant> findBySessionIdAndLeftAtIsNull(Long sessionId);

    /** 带用户信息的参与记录（组装 DTO 要读昵称）。 */
    @Query("""
            select p from SessionParticipant p
            join fetch p.user
            where p.session.id = :sessionId
            order by p.joinedAt asc, p.id asc
            """)
    List<SessionParticipant> findBySessionWithUser(@Param("sessionId") Long sessionId);

    /** 某课堂的出席人数（去过重的唯一键保证一人一行，直接 count 即可）。 */
    long countBySessionId(Long sessionId);
}
