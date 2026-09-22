package com.fridayclass.service.agent;

import com.fridayclass.enums.FileFormat;

/**
 * 一次任务选定的「落在哪、写成什么、算谁的上下文」。
 *
 * <h3>为什么把它单独拎成一条记录，而不是往方法里塞 6 个参数</h3>
 *
 * 这五样东西<b>全部由服务端决定</b>，模型一个都碰不到（见 A1 §4.1「工具不含范围字段」）。
 * 把它们捆在一起传递，能让「模型能决定什么」这条边界在<b>类型层面</b>看得见：
 * {@code write_file} 的参数里只有文件名和正文，其余都从这个对象来。
 *
 * <p>另一个好处是方法签名短。7 个参数的构造调用一旦写错顺序
 * （{@code sessionId} 和 {@code coursewareId} 都是 {@code Long}），
 * 编译器<b>不会</b>报错，而结果是把文件挂到了别的课堂上。
 *
 * @param folder       老师选的文件夹名（只是名字，不是路径）
 * @param format       老师选的输出格式
 * @param sessionId    老师选的课堂，可空
 * @param coursewareId 老师选的课件，可空
 * @param prompt       老师那句原始指令，用于回溯
 */
public record AgentFileContext(String folder,
                               FileFormat format,
                               Long sessionId,
                               Long coursewareId,
                               String prompt) {
}
