package com.fridayclass.service.agent.export;

import java.util.List;

/**
 * Markdown 文档的一个「块」。
 *
 * <h3>为什么先解析成块，而不是让每个转换器各自逐行判断</h3>
 *
 * 三种格式都要回答同样的问题：「这行是几级标题」「这几行是一个表格」。
 * 各写一遍的必然结果是<b>三者行为逐渐分叉</b> ——
 * 比如 txt 里表格被拆成了空格连接、docx 里却整块漏掉，
 * 而老师只会发现「导出的 Word 少了东西」，完全想不到是两套解析逻辑。
 *
 * <p>解析一次、渲染三次，这类分叉就不可能发生。
 *
 * <p>用 sealed + record 是为了让转换器里的 {@code switch} <b>穷尽</b>：
 * 将来加一种块类型（比如代码块），编译器会直接指出
 * 「三个转换器都还没处理它」，而不是安静地把它渲染成空白。
 */
public sealed interface MdBlock {

    /** 标题。{@code level} 是 {@code #} 的个数，1..6。 */
    record Heading(int level, String text) implements MdBlock {
    }

    /** 普通段落。 */
    record Paragraph(String text) implements MdBlock {
    }

    /** 无序列表项。 */
    record Bullet(String text) implements MdBlock {
    }

    /** 有序列表项。 */
    record Ordered(String text) implements MdBlock {
    }

    /** 引用。 */
    record Quote(String text) implements MdBlock {
    }

    /** 分割线。 */
    record Rule() implements MdBlock {
    }

    /**
     * 表格。
     *
     * @param header 表头；模型没写分隔行（{@code |---|}）时为空列表
     * @param rows   数据行，每个元素是一行的各单元格
     */
    record Table(List<String> header, List<List<String>> rows) implements MdBlock {

        public boolean hasHeader() {
            return header != null && !header.isEmpty();
        }

        /** 列数：取表头与所有数据行的最大值，避免某行列多时被截断。 */
        public int columnCount() {
            int max = hasHeader() ? header.size() : 0;
            if (rows != null) {
                for (List<String> row : rows) {
                    max = Math.max(max, row == null ? 0 : row.size());
                }
            }
            return max;
        }
    }
}
