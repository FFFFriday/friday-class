package com.fridayclass.repository;

import com.fridayclass.entity.AiGeneratedFile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * AI 生成文件索引。
 *
 * <p>⚠ <b>本接口里所有「单个文件」的查询都强制带 {@code ownerId}</b>，
 * 没有 {@code findById(Long)} 这种裸方法可用 —— 这是刻意的：
 * 下载接口一旦用了裸 findById，任何人猜一个 id 就能拿到别人的文档。
 * 把 owner 条件写进方法名，**调用方想漏都漏不掉**（见 A1 §4.5「owner 隔离两处」）。
 */
public interface AiGeneratedFileRepository extends JpaRepository<AiGeneratedFile, Long> {

    /**
     * 我的文件，新→旧。
     *
     * <p><b>排序用 {@code updatedAt} 而不是 {@code createdAt}</b>：重新生成同一个文件
     * 是「改写同一行」，创建时间不变。按创建时间排的话，刚重新生成的文件会停在
     * 几天前的位置，老师会以为没更新成功。配套索引 {@code idx_aifile_owner_updated}。
     */
    List<AiGeneratedFile> findByOwnerIdOrderByUpdatedAtDescIdDesc(Long ownerId, Pageable pageable);

    /** 我在某节课下产出的文件。排序理由同上。 */
    List<AiGeneratedFile> findByOwnerIdAndSessionIdOrderByUpdatedAtDescIdDesc(
            Long ownerId, Long sessionId, Pageable pageable);

    /** 按 id 取，且必须是<b>我的</b>。下载与删除都走它。 */
    Optional<AiGeneratedFile> findByIdAndOwnerId(Long id, Long ownerId);

    /**
     * 按「我的 + 文件夹 + 文件名」找，<b>忽略大小写</b>。
     *
     * <p>用于「重新生成同一个文件」时改写成同一行而不是插一行新的 ——
     * Windows 上 {@code 复习资料.docx} 与 {@code 复习资料.DOCX} 是同一个文件，
     * 数据库的唯一键（utf8mb4 默认排序规则不区分大小写）也会把它们当成重复。
     * 不先查一次就直接 insert 的话，第二次生成会撞唯一键报 500。
     *
     * <p><b>这里用显式 {@code @Query} 而不是派生查询名</b>：
     * {@code findByOwnerIdAndFolderIgnoreCaseAndFilenameIgnoreCase} 这种名字
     * 会被 Spring Data 按 {@code And} 切成 {@code ownerIdAndFolder} 之类的候选属性去猜，
     * 猜错只在启动时炸、报的还是「无法解析属性」。显式写出来没有这个不确定性。
     *
     * <p>用 {@code lower(...) = lower(...)} 而不是直接 {@code =}：
     * 后者能不能忽略大小写取决于列的排序规则，属于<b>看不见的依赖</b>。
     */
    @Query("select f from AiGeneratedFile f where f.ownerId = :ownerId "
            + "and lower(f.folder) = lower(:folder) and lower(f.filename) = lower(:filename)")
    Optional<AiGeneratedFile> findSameFile(@Param("ownerId") Long ownerId,
                                          @Param("folder") String folder,
                                          @Param("filename") String filename);

    /**
     * 按文件名跨文件夹找（新→旧），供智能体「读回自己生成的文件」。
     *
     * <p>只返回<b>自己</b>的：工具能读到的范围是 Friday 拍板的「C：AI 自己生成的文件」，
     * 不包括别人的，也不包括课件原文件。
     */
    @Query("select f from AiGeneratedFile f where f.ownerId = :ownerId "
            + "and lower(f.filename) = lower(:filename) order by f.updatedAt desc, f.id desc")
    List<AiGeneratedFile> findMineByName(@Param("ownerId") Long ownerId,
                                         @Param("filename") String filename,
                                         Pageable pageable);
}
