package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.CoursewarePageResponse;
import com.fridayclass.dto.CoursewareResponse;
import com.fridayclass.dto.PageResult;
import com.fridayclass.entity.Courseware;
import com.fridayclass.enums.CoursewareStatus;
import com.fridayclass.repository.CoursewarePageRepository;
import com.fridayclass.repository.CoursewareRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 门户课件业务：列表查询、详情、页列表。
 */
@Service
public class CoursewareService {

    /** 单页最大条数，防止客户端传超大 size 拖垮查询。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 关键字长度上限：模糊匹配走不了索引，超长关键字会放大扫描成本。 */
    private static final int MAX_KEYWORD_LENGTH = 100;

    /** LIKE 转义符，需与 CoursewareRepository 中 `escape '!'` 保持一致。 */
    private static final String LIKE_ESCAPE = "!";

    private final CoursewareRepository coursewareRepository;
    private final CoursewarePageRepository coursewarePageRepository;

    public CoursewareService(CoursewareRepository coursewareRepository,
                             CoursewarePageRepository coursewarePageRepository) {
        this.coursewareRepository = coursewareRepository;
        this.coursewarePageRepository = coursewarePageRepository;
    }

    /**
     * 分页查询课件。
     *
     * @param page    页码，从 1 开始（契约口径）；小于 1 时兜底为 1
     * @param size    每页条数；越界时收敛到 [1, {@value #MAX_PAGE_SIZE}]
     * @param keyword 名称关键字，空串按「不过滤」处理
     * @param status  课件状态，空串按「不过滤」处理；非法值报错而不是静默忽略
     */
    @Transactional(readOnly = true)
    public PageResult<CoursewareResponse> list(int page, int size, String keyword, String status) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Page<Courseware> result = coursewareRepository.search(
                buildNamePattern(keyword),
                parseStatus(status),
                PageRequest.of(safePage - 1, safeSize));

        return PageResult.from(result, CoursewareResponse::from);
    }

    /** 课件详情，不存在（含已软删除）返回 404。 */
    @Transactional(readOnly = true)
    public CoursewareResponse detail(Long id) {
        Courseware courseware = coursewareRepository.findDetailById(id)
                .orElseThrow(() -> new BusinessException(404, "课件不存在"));
        return CoursewareResponse.from(courseware);
    }

    /** 课件页列表，按页码升序。 */
    @Transactional(readOnly = true)
    public List<CoursewarePageResponse> pages(Long coursewareId) {
        if (!coursewareRepository.existsById(coursewareId)) {
            throw new BusinessException(404, "课件不存在");
        }
        return coursewarePageRepository.findByCoursewareIdOrderByPageNoAsc(coursewareId)
                .stream()
                .map(CoursewarePageResponse::from)
                .toList();
    }

    private String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * 把搜索关键字转成 LIKE 匹配串。
     *
     * <p>必须转义 LIKE 元字符：否则用户传 {@code %} 会变成「匹配全部」，
     * 传 {@code _} 会变成「任意单字符」，与字面搜索的语义不符。
     * 参数化绑定本身已防住 SQL 注入，这里处理的是语义问题。
     *
     * @return 形如 {@code %关键字%}；关键字为空返回 null（表示不按名称过滤）
     */
    private String buildNamePattern(String keyword) {
        String value = normalize(keyword);
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_KEYWORD_LENGTH) {
            throw new BusinessException("搜索关键字过长（最多 " + MAX_KEYWORD_LENGTH + " 个字符）");
        }
        String escaped = value
                .replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped + "%";
    }

    private CoursewareStatus parseStatus(String status) {
        String value = normalize(status);
        if (value == null) {
            return null;
        }
        try {
            return CoursewareStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("课件状态不合法：" + value);
        }
    }
}
