package com.fridayclass.enums;

/**
 * 听课名单里某个人的**来源**。对应 {@code session_audience.source} 字段。
 *
 * <p>存在意义有两个：
 * <ol>
 *   <li><b>可追溯</b> —— 课后能回答「这个学生当时是被哪个班带进来的」；</li>
 *   <li><b>可解释</b> —— 老师看到名单里有个人，能立刻分辨是自己单独勾的，
 *       还是班级成员自动带进来的。合班时这一点尤其重要。</li>
 * </ol>
 *
 * <p>注意它<b>不参与授权判定</b>：只要在 {@code session_audience} 里有行就能看，
 * 不区分来源。判定逻辑保持单一，不为了展示需求去复杂化安全检查。
 */
public enum AudienceSource {
    /** 来自班级成员（合班时是各班级成员的并集）。 */
    CLASS,

    /** 老师开课时单独勾选的同学。 */
    MANUAL
}
