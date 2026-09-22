package com.fridayclass.entity;

import com.fridayclass.enums.FileFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 生成的文件。对应 {@code ai_generated_file} 表（V4 迁移）。
 *
 * <h3>为什么 {@code ownerId} 是普通 Long，不写成 {@code @ManyToOne User}</h3>
 *
 * 两个原因，第二个更重要：
 * <ol>
 *   <li>这张表只关心「是谁的」，从不需要顺着它去读用户昵称头像；</li>
 *   <li><b>下载接口的 owner 校验是每次都要走的一步</b>。写成关联对象之后，
 *       校验就变成 {@code file.getOwner().getId().equals(me)} —— 一旦顺手写成
 *       {@code file.getOwner().equals(me)}，比的是实体对象而不是 id，
 *       而在 JPA 里「同一个用户的两个实例」并不相等，校验会<b>永远失败</b>。
 *       用 Long 就没有这个坑可踩。</li>
 * </ol>
 *
 * <p>外键仍然在（{@code fk_aifile_owner}），只是由数据库保证，不映射成对象关系。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_generated_file")
public class AiGeneratedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /**
     * 所属文件夹的<b>名字</b>（不是路径）。
     *
     * <p>真实路径由 {@code WorkspacePathSandbox} 拼，本列只存名字 ——
     * 这样即使有人直接改库，也改不出一个能穿越的路径。
     */
    @Column(nullable = false, length = 100)
    private String folder;

    @Column(nullable = false, length = 200)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FileFormat format;

    /** 落盘后的真实字节数（写完回读，不是按字符串长度估算）。 */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes = 0L;

    /** 生成时的上下文：哪节课。可空 —— 老师可以不选课堂就让它写东西。 */
    @Column(name = "session_id")
    private Long sessionId;

    /** 生成时的上下文：哪份课件。可空，理由同上。 */
    @Column(name = "courseware_id")
    private Long coursewareId;

    /** 老师当时那句指令。留着才能回答「这个文件是怎么来的」。 */
    @Column(length = 1000)
    private String prompt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 最后一次写入时间。
     *
     * <p><b>列表按它排序，不是 {@code createdAt}。</b> 重新生成同一个文件走的是
     * 「改写同一行」（见 {@code WorkspaceFileService#upsert}），{@code createdAt}
     * 一动不动 —— 按它排的话，老师刚重新生成的文件会停在几天前的位置，
     * 看起来像「没更新成功」。用 {@code updatedAt} 才是他期望的顺序。
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
