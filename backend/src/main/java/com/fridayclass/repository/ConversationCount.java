package com.fridayclass.repository;

/**
 * 「某会话有几条消息」的分组统计投影。
 *
 * <p>单独建这个类型是为了让会话列表**一次查完**所有会话的条数。
 * 不建它的话，列表里每个会话都要单独 count 一次——学生有 5 个会话就是 5 次查询，
 * 而这类 N+1 一旦写成习惯，等到会话多了才会被发现。
 *
 * <p>必须是 public 顶层类型：JPQL 的构造表达式要写全限定名，
 * 嵌套类型会变成 {@code Outer$Inner} 这种写法，难读也容易写错。
 */
public record ConversationCount(Long conversationId, Long messageCount) {
}
