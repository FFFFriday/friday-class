package com.fridayclass.repository;

import com.fridayclass.entity.ClassGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 班级数据访问接口。
 *
 * <p><b>软删过滤一律靠方法名里的 {@code DeletedFalse}，不靠实体注解。</b>
 * {@link ClassGroup} 是**被 join 的一方**（{@code class_group_member} 与
 * {@code session_class_group} 都指向它），把 {@code @SQLRestriction} 挂上去
 * 会在班级被软删时让 join 抛异常而不是给出 null。理由详见实体注释。
 *
 * <p>所有列表查询都 {@code join fetch g.teacher}：列表要显示教师名，
 * 而 {@code open-in-view=false}，事务外读懒加载关联会抛
 * {@code LazyInitializationException}。{@code teacher} 是 to-one，
 * 配合分页不会退化成内存分页。
 */
public interface ClassGroupRepository extends JpaRepository<ClassGroup, Long> {

    /**
     * 教师自己的班级（分页 + 可选关键字）。
     *
     * <p>用 {@code :hasKeyword} 布尔开关而不是 {@code :keyword is null}，
     * 理由同 {@code UserRepository.searchForAdmin}
     * （Hibernate 6 对可空参数做 is-null 判断需要显式类型提示）。
     */
    @Query(value = """
            select g from ClassGroup g
            join fetch g.teacher
            where g.deleted = false
              and g.teacher.id = :teacherId
              and (:hasKeyword = false or lower(g.name) like lower(concat('%', :keyword, '%')))
            order by g.id desc
            """,
            countQuery = """
            select count(g) from ClassGroup g
            where g.deleted = false
              and g.teacher.id = :teacherId
              and (:hasKeyword = false or lower(g.name) like lower(concat('%', :keyword, '%')))
            """)
    Page<ClassGroup> searchMine(@Param("teacherId") Long teacherId,
                                @Param("hasKeyword") boolean hasKeyword,
                                @Param("keyword") String keyword,
                                Pageable pageable);

    /**
     * 管理端：**全部**班级，可按教师过滤。
     *
     * <p>与 {@link #searchMine} 的唯一区别是不限定 {@code teacherId} ——
     * 与既有 {@code AdminUserController} 包一层 Service 的做法一致，
     * 而不是复制一套查询。
     */
    @Query(value = """
            select g from ClassGroup g
            join fetch g.teacher
            where g.deleted = false
              and (:hasTeacher = false or g.teacher.id = :teacherId)
              and (:hasKeyword = false or lower(g.name) like lower(concat('%', :keyword, '%')))
            order by g.id desc
            """,
            countQuery = """
            select count(g) from ClassGroup g
            where g.deleted = false
              and (:hasTeacher = false or g.teacher.id = :teacherId)
              and (:hasKeyword = false or lower(g.name) like lower(concat('%', :keyword, '%')))
            """)
    Page<ClassGroup> searchAll(@Param("hasTeacher") boolean hasTeacher,
                               @Param("teacherId") Long teacherId,
                               @Param("hasKeyword") boolean hasKeyword,
                               @Param("keyword") String keyword,
                               Pageable pageable);

    /** 详情（带教师，供组装 DTO）。软删的不返回 —— 表现为 404。 */
    @Query("select g from ClassGroup g join fetch g.teacher where g.id = :id and g.deleted = false")
    Optional<ClassGroup> findDetailById(@Param("id") Long id);

    /** 我的全部班级（不翻页，供开课弹窗的下拉多选）。 */
    @Query("""
            select g from ClassGroup g
            join fetch g.teacher
            where g.deleted = false and g.teacher.id = :teacherId
            order by g.id desc
            """)
    List<ClassGroup> findMine(@Param("teacherId") Long teacherId);

    /** 某教师名下未删除的班级数（管理端列表的「已开课数」等统计用）。 */
    long countByTeacherIdAndDeletedFalse(Long teacherId);
}
