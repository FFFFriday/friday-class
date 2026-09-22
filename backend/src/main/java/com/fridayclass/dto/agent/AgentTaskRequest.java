package com.fridayclass.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起一次 AI 智能体任务。
 *
 * <h3>这里为什么<b>没有</b> ownerId / 路径 / 文件名</h3>
 *
 * 「算谁的」由登录态决定，「落在哪个目录」由 {@code folder} 这个<b>名字</b>
 * 经服务端拼接得到。前端<b>无法</b>指定一个绝对路径 ——
 * 请求体里根本没有这样的字段，不是「传了再拒绝」。
 *
 * <h3>coursewareId / sessionId 可为空，但都要重新验归属</h3>
 *
 * 下拉框里只有他自己的课件与课堂，但那只是<b>体验层</b>：
 * 请求可以手工构造。所以服务端收到 id 后必须再查一次
 * 「这确实是他上传的 / 他上的课」，否则就是越权读别人的课堂记录。
 *
 * @param instruction  老师那句话，或快捷任务展开后的指令
 * @param coursewareId 选定的课件，可空
 * @param sessionId    选定的课堂，可空
 * @param folder       选定的文件夹<b>名字</b>（不是路径）
 * @param format       输出格式，取值见 {@code FileFormat}
 */
public record AgentTaskRequest(

        @NotBlank(message = "请说明要让 AI 做什么")
        @Size(max = 1000, message = "要求最长 1000 个字符")
        String instruction,

        Long coursewareId,

        Long sessionId,

        @NotBlank(message = "请选择要保存到的文件夹")
        @Size(max = 100, message = "文件夹名最长 100 个字符")
        String folder,

        @NotBlank(message = "请选择输出格式")
        String format) {
}
