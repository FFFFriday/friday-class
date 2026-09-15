package com.fridayclass.repository;

import com.fridayclass.dto.PageTextItem;
import com.fridayclass.entity.KnowledgePoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 知识点数据访问接口。
 */
public interface KnowledgePointRepository extends JpaRepository<KnowledgePoint, Long> {

    List<KnowledgePoint> findByPageIdOrderBySortOrderAsc(Long pageId);

    /**
     * 一次取多页的知识点，供 prompt-pack 用。
     *
     * <p><b>必须批量，不能逐页查</b>：prompt-pack 要返回整份课件所有页的知识点，
     * 一份 103 页的课件逐页查就是 103 条 SQL（典型的 N+1）。学生每次进课堂都会调这个接口，
     * 103 条查询会明显拖慢首屏。
     *
     * <p>排序里带上 {@code pageId}：调用方把结果按 pageId 分组，
     * 有排序的话分组时天然保持页内顺序；组内再按 sortOrder 升序。
     *
     * <p><b>返回投影而不是实体</b>：取实体的话想拿页 ID 只能 {@code getPage().getId()}，
     * 那会逐条触发懒加载；而 JPQL 的 {@code k.page.id} 会被优化成直接读外键列，
     * 连 JOIN 都不产生。理由详见 {@link PageTextItem}。
     */
    @Query("""
            select new com.fridayclass.dto.PageTextItem(k.page.id, k.content)
            from KnowledgePoint k
            where k.page.id in :pageIds
            order by k.page.id asc, k.sortOrder asc, k.id asc
            """)
    List<PageTextItem> findByPageIds(@Param("pageIds") Collection<Long> pageIds);

    /**
     * 删除某一页的全部知识点。<b>重新解析某页前必须调用</b>，
     * 否则重跑一次就会多出一份重复行（设计文档 §2.5 的幂等要求）。
     *
     * <p>用 JPQL 批量删除而不是派生查询 {@code deleteByPageId}：后者会先把实体逐条查出来、
     * 再逐条删除（每条一次 DELETE），页内几十条知识点就是几十次往返。
     */
    @Modifying
    @Query("delete from KnowledgePoint k where k.page.id = :pageId")
    void deleteByPageId(@Param("pageId") Long pageId);
}
