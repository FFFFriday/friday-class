package com.fridayclass.dto.agent;

import com.fridayclass.entity.AiGeneratedFile;

import java.time.LocalDateTime;

/**
 * 一个 AI 生成的文件。
 *
 * <p><b>刻意不返回磁盘路径。</b> 前端要下载就带 id 调
 * {@code GET /api/agent/files/{id}/download}，路径由服务端自己拼。
 * 返回路径等于把「文件在服务器上的哪个位置」告诉了浏览器 ——
 * 这类信息对功能没有任何帮助，却能被用来推断目录结构。
 *
 * @param id           文件 ID（下载用）
 * @param folder       所在文件夹名
 * @param filename     文件名（含扩展名）
 * @param format       MD / TXT / DOCX / XLSX
 * @param sizeBytes    字节数
 * @param sessionId    生成时选的课堂，可空
 * @param coursewareId 生成时选的课件，可空
 * @param prompt       老师当时那句指令
 * @param createdAt    生成时间
 */
public record AgentFileResponse(
        Long id,
        String folder,
        String filename,
        String format,
        long sizeBytes,
        Long sessionId,
        Long coursewareId,
        String prompt,
        LocalDateTime createdAt) {

    public static AgentFileResponse from(AiGeneratedFile file) {
        return new AgentFileResponse(
                file.getId(),
                file.getFolder(),
                file.getFilename(),
                file.getFormat() == null ? null : file.getFormat().name(),
                file.getSizeBytes() == null ? 0L : file.getSizeBytes(),
                file.getSessionId(),
                file.getCoursewareId(),
                file.getPrompt(),
                file.getCreatedAt());
    }
}
