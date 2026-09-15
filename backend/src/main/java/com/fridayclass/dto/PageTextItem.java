package com.fridayclass.dto;

/**
 * 「某一页的一条文本」——知识点与预置提问共用的**轻量投影**。
 *
 * <h3>为什么需要它，而不是直接返回实体</h3>
 *
 * {@code KnowledgePoint} / {@code PresetQuestion} 上的 {@code page} 是
 * {@code @ManyToOne(LAZY)}：拿到实体后想取页 ID 只能写 {@code kp.getPage().getId()}，
 * 而这会<b>逐个触发懒加载</b>——一份 103 页的课件就是几百次额外 SELECT，
 * 正是 prompt-pack 最需要避免的 N+1。
 *
 * <p>用 JPQL 的 {@code select new} 直接查这个投影就不一样了：
 * {@code k.page.id} 在 JPQL 里会被 Hibernate 优化成<b>直接读外键列</b>，连 JOIN 都不产生，
 * 一条 SQL 取回全部数据。
 *
 * @param pageId  所属页 ID（缓存与分组都用它，绝不能用 pageNo）
 * @param content 知识点 / 提问的正文
 */
public record PageTextItem(Long pageId, String content) {
}
