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
 * 这里不需要再写 deleted 条件。
 */
public interface CoursewareRepository extends JpaRepository<Courseware, Long> {

    /**
     * 门户课件列表：名称模糊匹配 + 状态筛选，两个条件为空表示不过滤。
     *
     * <p>要点说明：
     * <ul>
     *   <li><b>必须用 left join fetch</b>：{@code User} 上也有
     *       {@code @SQLRestriction("deleted = 0")}，inner join 会把「上传者已被软删除」
     *       的课件整行滤掉，而下面的 countQuery 不 join、仍把它计入 total，
     *       导致 total 与实际条数对不上（出现「有下一页却查不到数据」）；</li>
     *   <li>join fetch 是为了在 {@code open-in-view=false} 下预取 uploader，
     *       否则组装 DTO 取昵称时抛 LazyInitializationException；</li>
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
     * 同样用 left join，避免上传者被软删除时课件误报 404。
     */
    @Query("select c from Courseware c left join fetch c.uploader where c.id = :id")
    Optional<Courseware> findDetailById(@Param("id") Long id);
}
