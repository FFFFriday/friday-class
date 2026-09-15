package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.PageTextItem;
import com.fridayclass.dto.PromptPackResponse;
import com.fridayclass.entity.AiParseTask;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.CoursewarePage;
import com.fridayclass.enums.AiTaskStatus;
import com.fridayclass.repository.AiParseTaskRepository;
import com.fridayclass.repository.CoursewarePageRepository;
import com.fridayclass.repository.CoursewareRepository;
import com.fridayclass.repository.KnowledgePointRepository;
import com.fridayclass.repository.PresetQuestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装提示词包（F002 的产出 → F004 的输入）。
 *
 * <h3>为什么要单独一个接口，而不是复用「课件页列表」</h3>
 * {@code GET /api/courseware/{id}/pages} 只返回 {@code id/pageNo/textContent/slideUrl} 四个字段，
 * <b>不含任何知识点</b>。设计文档 V1 曾误以为它带知识点，导致整套「预下发」机制没有数据来源。
 * 所以这里另开一个接口，一次性把知识点与预置提问都带上。
 *
 * <h3>为什么必须批量查询</h3>
 * 一份 103 页的课件，逐页查知识点就是 103 条 SQL（外加 103 条查预置提问）。
 * 学生每次进课堂都会调这个接口，N+1 会明显拖慢首屏。这里固定 4 条查询搞定：
 * 查页、查知识点、查预置提问、查最近的解析任务。
 */
@Service
public class PromptPackService {

    private final CoursewareRepository coursewareRepository;
    private final CoursewarePageRepository pageRepository;
    private final KnowledgePointRepository knowledgePointRepository;
    private final PresetQuestionRepository presetQuestionRepository;
    private final AiParseTaskRepository taskRepository;

    public PromptPackService(CoursewareRepository coursewareRepository,
                             CoursewarePageRepository pageRepository,
                             KnowledgePointRepository knowledgePointRepository,
                             PresetQuestionRepository presetQuestionRepository,
                             AiParseTaskRepository taskRepository) {
        this.coursewareRepository = coursewareRepository;
        this.pageRepository = pageRepository;
        this.knowledgePointRepository = knowledgePointRepository;
        this.presetQuestionRepository = presetQuestionRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional(readOnly = true)
    public PromptPackResponse build(Long coursewareId) {
        Courseware courseware = coursewareRepository.findById(coursewareId)
                .orElseThrow(() -> new BusinessException(404, "课件不存在"));

        List<CoursewarePage> pages = pageRepository.findByCoursewareIdOrderByPageNoAsc(coursewareId);

        // 只有 SUCCESS / PARTIAL 才算「真的产出了数据」，版本号据此取
        long parseVersion = taskRepository
                .findTopByCoursewareIdAndStatusInOrderByIdDesc(
                        coursewareId, List.of(AiTaskStatus.SUCCESS, AiTaskStatus.PARTIAL))
                .map(AiParseTask::getId)
                .orElse(0L);

        // 没有页时不必再查两张关联表（空 in 列表在部分数据库上会报语法错，直接短路更稳）
        if (pages.isEmpty()) {
            return new PromptPackResponse(courseware.getId(), parseVersion,
                    courseware.getStatus().name(), List.of());
        }

        List<Long> pageIds = pages.stream().map(CoursewarePage::getId).toList();
        Map<Long, List<String>> knowledgeByPage =
                groupByPage(knowledgePointRepository.findByPageIds(pageIds));
        Map<Long, List<String>> questionsByPage =
                groupByPage(presetQuestionRepository.findByPageIds(pageIds));

        List<PromptPackResponse.PagePack> packs = new ArrayList<>(pages.size());
        for (CoursewarePage page : pages) {
            packs.add(new PromptPackResponse.PagePack(
                    page.getId(),
                    page.getPageNo(),
                    // 空数组而不是 null：前端可以直接遍历，不必判空
                    knowledgeByPage.getOrDefault(page.getId(), List.of()),
                    questionsByPage.getOrDefault(page.getId(), List.of())));
        }

        return new PromptPackResponse(courseware.getId(), parseVersion,
                courseware.getStatus().name(), packs);
    }

    /**
     * 把「某页的一条文本」平铺列表按页分组。
     *
     * <p>依赖 repository 里 {@code order by pageId, sortOrder} 的排序：
     * 顺序遍历即可保证组内顺序正确，不必再排一次。
     */
    private static Map<Long, List<String>> groupByPage(List<PageTextItem> items) {
        Map<Long, List<String>> grouped = new HashMap<>();
        for (PageTextItem item : items) {
            grouped.computeIfAbsent(item.pageId(), key -> new ArrayList<>())
                    .add(item.content());
        }
        return grouped;
    }
}
