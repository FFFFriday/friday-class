package com.fridayclass.repository;

import com.fridayclass.dto.PageTextItem;
import com.fridayclass.entity.PresetQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 预置提问数据访问接口。方法与 {@link KnowledgePointRepository} 一一对应，理由相同。
 */
public interface PresetQuestionRepository extends JpaRepository<PresetQuestion, Long> {

    List<PresetQuestion> findByPageIdOrderBySortOrderAsc(Long pageId);

    /** 返回投影而非实体，理由见 {@link PageTextItem}。 */
    @Query("""
            select new com.fridayclass.dto.PageTextItem(q.page.id, q.content)
            from PresetQuestion q
            where q.page.id in :pageIds
            order by q.page.id asc, q.sortOrder asc, q.id asc
            """)
    List<PageTextItem> findByPageIds(@Param("pageIds") Collection<Long> pageIds);

    /** 重新解析某页前先清空旧数据，避免重复行。 */
    @Modifying
    @Query("delete from PresetQuestion q where q.page.id = :pageId")
    void deleteByPageId(@Param("pageId") Long pageId);
}
