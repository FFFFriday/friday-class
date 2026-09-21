package com.fridayclass.repository;

import com.fridayclass.entity.Courseware;
import com.fridayclass.enums.CoursewareStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 课件数据访问接口。
 *
 * <p>软删除过滤由实体上的 {@code @SQLRestriction("deleted = 0")} 自动生效，
 * 本仓库的方法（含 {@code findAll} / {@code findById}）都不需要再写 deleted 条件。
 *
 * <p><b>保留 {@code @SQLRestriction} 是刻意的选择</b>：本仓库被 10 个 Service 注入、
 * {@code findById} 调用点超过 12 处，若改成「去掉实体注解、各查询自己写 deleted = 0」，
 * 漏掉任何一处都会让已删除的课件重新可访问。
 *
 * <p>代价是：任何 {@code @ManyToOne Courseware} 的关联，在课件被软删后读出来是「行不存在」。
 * 为此这些关联必须加 {@code @NotFound(IGNORE)} 把「行不存在」翻译成 null
 * （见 {@code ClassSession.courseware}、{@code CoursewarePage.courseware} 等）。
 * 新增指向 Courseware 的关联时，<b>记得一并加</b>。
 */
public interface CoursewareRepository extends JpaRepository<Courseware, Long> {

    /**
     * 门户课件列表：名称模糊匹配 + 状态筛选，两个条件为空表示不过滤。
     *
     * <p>要点说明：
     * <ul>
     *   <li>join fetch 是为了在 {@code open-in-view=false} 下预取 uploader，
     *       否则组装 DTO 取昵称时抛 LazyInitializationException。
     *       <b>用 left 而不是 inner</b>：上传者被软删除时课件仍应列出来，
     *       只是上传者显示为空——这与 {@code ClassSession} 那边的口径一致；</li>
     *   <li>显式提供 countQuery：含 fetch 的查询 Spring Data 无法自动派生 count；</li>
     *   <li>{@code escape '!'} 配合 Service 侧转义，使用户输入的 % 和 _ 按字面匹配。</li>
     * </ul>
     *
     * @param pattern 已转义并加好 % 的匹配串；null 表示不按名称过滤
     */
    @Query(value = """
            select c from Courseware c
            left join fetch c.uploader
            where (:pattern is null or c.name like :pattern escape '!')
              and (:status is null or c.status = :status)
            order by c.uploadedAt desc, c.id desc
            """,
            countQuery = """
            select count(c) from Courseware c
            where (:pattern is null or c.name like :pattern escape '!')
              and (:status is null or c.status = :status)
            """)
    Page<Courseware> search(@Param("pattern") String pattern,
                            @Param("status") CoursewareStatus status,
                            Pageable pageable);

    /**
     * 课件详情：同时取出上传者，供 DTO 组装 uploaderName。
     * 同样用 left join，理由见 {@link #search}。
     *
     * <p>软删除过滤<b>不需要</b>在这里写：{@code Courseware} 上的
     * {@code @SQLRestriction("deleted = 0")} 会追加到本查询的 where ，
     * 所以已删除的课件仍然查不到，行为与修复前一致。
     */
    @Query("select c from Courseware c left join fetch c.uploader where c.id = :id")
    Optional<Courseware> findDetailById(@Param("id") Long id);
}
