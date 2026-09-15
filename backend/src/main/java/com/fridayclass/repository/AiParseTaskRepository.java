package com.fridayclass.repository;

import com.fridayclass.entity.AiParseTask;
import com.fridayclass.enums.AiTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * AI 解析任务数据访问接口。
 */
public interface AiParseTaskRepository extends JpaRepository<AiParseTask, Long> {

    List<AiParseTask> findByCoursewareIdOrderByCreatedAtDesc(Long coursewareId);

    /**
     * 该课件最近的一次解析任务。
     *
     * <p>两个用途：
     * <ol>
     *   <li>{@code parse-progress} 的进度来源；</li>
     *   <li>防重复触发：上一行还没结束（PENDING/RUNNING）就拒绝再次解析。</li>
     * </ol>
     *
     * <p>按 {@code id} 倒序而不是 {@code createdAt}：{@code created_at} 是 {@code DATETIME}
     * （秒精度），同一秒内建的两条任务顺序不确定；{@code id} 是自增主键，严格单调，
     * 而且它正是 prompt-pack 里 {@code parseVersion} 要用的那个版本号。
     */
    Optional<AiParseTask> findTopByCoursewareIdOrderByIdDesc(Long coursewareId);

    /** 启动时清理僵尸任务用（见 {@code StaleParseTaskCleaner}）。 */
    List<AiParseTask> findByStatusIn(Collection<AiTaskStatus> statuses);

    /**
     * 该课件<b>最近一次真正产出了数据</b>的解析任务。
     *
     * <p>它的 {@code id} 就是 prompt-pack 里的 {@code parseVersion}（API 接口文档 §6.1 的 P4）：
     * 库里没有 {@code parse_version} 列，而自增主键天然单调递增、语义正好。
     *
     * <p>只认 {@code SUCCESS} / {@code PARTIAL}——{@code FAILED} 的任务没写出任何数据，
     * 拿它当版本号会让客户端误以为「有新数据了」而去重拉一次空的。
     */
    Optional<AiParseTask> findTopByCoursewareIdAndStatusInOrderByIdDesc(
            Long coursewareId, Collection<AiTaskStatus> statuses);
}
