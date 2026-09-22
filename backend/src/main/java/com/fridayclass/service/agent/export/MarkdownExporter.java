package com.fridayclass.service.agent.export;

import com.fridayclass.enums.FileFormat;

/**
 * 把模型产出的 Markdown 转成某种可下载的格式。
 *
 * <h3>为什么让模型只写 Markdown，格式由后端转</h3>
 *
 * <ol>
 *   <li><b>模型生成二进制/复杂格式极易出错，而且没法测。</b>
 *       让模型直接吐 docx 是不现实的；让它吐 Markdown 则是它最擅长的；</li>
 *   <li><b>转换器是确定性代码</b>，输入一样输出就一样，可以单独写单测；</li>
 *   <li><b>以后加格式不用改提示词</b>，只需再加一个实现类 ——
 *       提示词是模型行为的描述，加格式却要动它，是典型的耦合错位。</li>
 * </ol>
 *
 * <h3>为什么用接口 + 注册表，而不是一个 switch</h3>
 *
 * 加一种格式时，switch 要改一处、枚举要改一处、前端下拉要改一处；
 * 而这里的注册表是<b>按实现类自动收集</b>的（见 {@link MarkdownExporterRegistry}），
 * 新增格式只写一个新类，其余地方一行不动。
 */
public interface MarkdownExporter {

    /** 本实现负责哪一种格式。 */
    FileFormat format();

    /**
     * 产出最终字节。
     *
     * @param markdown 模型给出的 Markdown 原文
     * @param title    兜底标题：Markdown 里没有一级标题时用它（取文件基本名）
     */
    byte[] export(String markdown, String title);
}
