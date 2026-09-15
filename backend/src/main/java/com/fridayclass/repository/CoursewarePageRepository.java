package com.fridayclass.repository;

import com.fridayclass.entity.CoursewarePage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 课件页数据访问接口。
 */
public interface CoursewarePageRepository extends JpaRepository<CoursewarePage, Long> {

    List<CoursewarePage> findByCoursewareIdOrderByPageNoAsc(Long coursewareId);

    /**
     * 该课件<b>实际</b>有多少页。
     *
     * <p>⚠️ 不能用 {@code courseware.page_count} 代替。实测库里就存在两者不一致的数据：
     * 一份课件 {@code page_count} 写 20、{@code courseware_page} 实际只有 5 行。
     * 用 {@code page_count} 算进度会让进度条永远走不满——任务明明 {@code SUCCESS} 了，
     * 界面却停在 25%，比不显示进度更让人困惑。
     */
    long countByCoursewareId(Long coursewareId);
}
