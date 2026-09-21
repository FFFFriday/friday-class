package com.fridayclass.repository;

import com.fridayclass.entity.CourseSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 课程总结数据访问接口。
 */
public interface CourseSummaryRepository extends JpaRepository<CourseSummary, Long> {

    Optional<CourseSummary> findBySessionId(Long sessionId);

    /**
     * 这批课堂里，哪些**已经有总结**了。
     *
     * <p>给学生端「我的课堂」列表判断「回顾」按钮该不该提示「尚无总结」。
     * 一次性批量查而不是逐条查：一页 20 节课逐条查就是 20 次 SQL（N+1）。
     *
     * <p>只取 id 不取实体：列表只需要一个布尔，把每篇总结正文都读进内存纯属浪费
     * （总结是 TEXT，一篇可能上万字）。
     */
    @Query("""
            select s.session.id from CourseSummary s
            where s.session.id in :sessionIds
            """)
    List<Long> findSessionIdsWithSummary(@Param("sessionIds") Collection<Long> sessionIds);
}
