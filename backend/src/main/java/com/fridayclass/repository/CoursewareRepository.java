package com.fridayclass.repository;

import com.fridayclass.entity.Courseware;
import com.fridayclass.enums.CoursewareStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * <b>我上传的</b>课件，新→旧。AI 智能体的课件下拉框用它。
     *
     * <h3>为什么不复用门户的 {@link #search}</h3>
     *
     * {@code GET /api/courseware} 是<b>门户公开列表</b>——它返回所有人上传的课件
     * （那是刻意的：公开门户要能看到全部）。拿它当下拉框的数据源，会出现
     * 「选项里能看到别人的课件，选中后却被 403 挡回来」这种自相矛盾的体验：
     * 老师只会认为功能坏了，而不会想到「那不是我上传的」。
     *
     * <p>下拉框里出现的东西，必须<b>恰好等于</b>他能操作的东西。
     *
     * <p>软删除过滤不需要写：{@code Courseware} 上的
     * {@code @SQLRestriction("deleted = 0")} 会自动追加。
     */
    @Query("""
            select c from Courseware c
            left join fetch c.uploader
            where c.uploader.id = :uploaderId
            order by c.uploadedAt desc, c.id desc
            """)
    List<Courseware> findMine(@Param("uploaderId") Long uploaderId);

    /**
     * 这份课件是不是他上传的？返回 1 / 0。
     *
     * <h3>为什么用一条 count 查询，而不是 findById 之后再 getUploader()</h3>
     *
     * {@code Courseware.uploader} 是 {@code FetchType.LAZY}。在事务里访问它没问题，
     * 但「校验归属」这一步会被用在<b>没有事务的地方</b>（比如异步任务提交前），
     * 那时懒加载会抛 {@code LazyInitializationException}——
     * 而且报的是「会话已关闭」，跟「这不是你的课件」毫无关系，极难定位。
     *
     * <p>写成 count 查询则完全绕开加载：{@code c.uploader.id} 在 JPQL 里
     * 被优化成直接读外键列，一次 SQL 出结果，<b>不需要事务、不会触发懒加载</b>。
     *
     * <p>不用 {@code select count(c) > 0}：部分方言不支持在 select 子句里做比较，
     * 而「取回 long 再在 Java 里比」在任何方言下都成立。
     */
    @Query("select count(c) from Courseware c where c.id = :id and c.uploader.id = :userId")
    long countOwnedBy(@Param("id") Long id, @Param("userId") Long userId);
}
