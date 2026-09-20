package com.fridayclass.repository;

import com.fridayclass.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 学生 AI 会话数据访问接口。
 *
 * <p>实体上带 {@code @SQLRestriction("deleted = 0")}，所以本接口的查询
 * <b>天然只看得到未删除的会话</b>，各方法不必再自己带 {@code deleted} 条件。
 */
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    /**
     * 某学生的会话列表，最近活跃的排最前。
     *
     * <p>按 {@code lastActiveAt} 而不是 {@code createdAt} 排序：
     * 否则「刚聊过的老会话」会被新开的空会话挤到下面去。
     * 创建时就会给 {@code lastActiveAt} 赋值，所以不会出现 NULL。
     */
    List<AiConversation> findByStudentIdOrderByLastActiveAtDescIdDesc(Long studentId);

    /**
     * 按 ID + 学生 查会话。
     *
     * <p><b>这是会话归属校验的唯一入口。</b>所有按会话 ID 的操作都必须走它，
     * 而不是 {@code findById}——否则学生 A 拿到学生 B 的会话 ID 就能读别人的对话。
     * 归属不匹配时返回空，调用方统一按 404 处理（不返回 403，
     * 免得泄露「这个 ID 确实存在」）。
     */
    Optional<AiConversation> findByIdAndStudentId(Long id, Long studentId);

    /** 带课件关联的详情查询（组装 DTO 要读课件名，避免事务外懒加载炸掉）。 */
    @Query("""
            select c from AiConversation c
            left join fetch c.courseware
            left join fetch c.session
            where c.id = :id and c.student.id = :studentId
            """)
    Optional<AiConversation> findDetailByIdAndStudentId(@Param("id") Long id,
                                                        @Param("studentId") Long studentId);
}
