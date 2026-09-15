package com.fridayclass.service;

import org.apache.poi.openxml4j.util.ZipSecureFile;

/**
 * POI 读 Office 压缩包时的安全参数。<b>集中一处，不要分散去改。</b>
 *
 * <p>调用方有 {@link FileStorageService}（上传校验）和 {@link PptxConverter}（逐页解析），
 * 两处都必须先调用 {@link #apply()}——校验发生在解析之前，如果只在解析器里设置，
 * 校验那一步就是在没有防护的情况下打开压缩包的。
 *
 * <h2>为什么 minInflateRatio 用的是 POI 默认值 0.01，而不是更小的值</h2>
 * 这个参数是「压缩后大小 / 展开后大小」的<b>下限</b>，低于它就判定为 zip bomb。
 * 直觉上会觉得「越小越安全」，但 OOXML 的 XML 部分压缩率极高，调大了会把
 * <b>完全正常的课件</b>当成炸弹拒掉。实测一份正常的 355 条目课件
 * （计算机网络第 8 版课件·运输层，1.8MB）：
 *
 * <pre>
 *   整体压缩比           0.2141
 *   压缩最好的条目       0.0477  (ppt/diagrams/quickStyle1.xml)
 *   正文幻灯片           0.0564  (ppt/slides/slide56.xml，展开 17.7 倍)
 *   阈值设 0.1  -> 41 个正常条目被误判
 *   阈值设 0.01 -> 0 个误判
 * </pre>
 *
 * 所以这里保留 POI 的默认 0.01（真正的 zip bomb 动辄 1000:1，即比值 0.001，
 * 0.01 足以拦下），真正的防护靠下面这条单条目展开上限 + 上传体积上限（50MB）。
 */
public final class PptxZipSecurity {

    private PptxZipSecurity() {
    }

    private static volatile boolean applied;

    /** 幂等，可重复调用；线程安全。 */
    public static void apply() {
        if (applied) {
            return;
        }
        synchronized (PptxZipSecurity.class) {
            if (applied) {
                return;
            }
            // 单条目展开上限：拦住「一个小条目炸成几个 GB」。
            ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
            // 压缩比下限：显式写出来，是为了让后来者看到上面那段实测数据，
            // 而不是「顺手把它调紧一点」。0.01 即 POI 默认值。
            ZipSecureFile.setMinInflateRatio(0.01d);
            // 条目数上限：POI 默认 1000，**对本项目来说太小了**。
            //
            // 实测（2026-09-15，python-pptx 生成的最小 pptx）：
            //   450 页 ->  936 条目 -> 通过
            //   500 页 -> 1036 条目 -> 被拒
            // 每页约 2 个条目（slideN.xml + slideN.xml.rels），另加母版/版式/主题等固定开销。
            // 也就是说默认 1000 大约在 **482 页**左右被撞到，而 PptxConverter.MAX_SLIDES
            // 声明的是 500 —— 那个「课件页数过多」的提示因此**永远走不到**：
            // 页数一多，先在这里被拦下，用户拿到的是
            // 「文件内容不是有效的 .pptx（可能是改了扩展名的其他文件）」——
            // 一份完全合法的课件被告知是坏文件。
            //
            // 4000 的依据：500 页约 1000 条目，真实 PowerPoint 课件还会带版式、备注页、
            // 小图标等，留约 4 倍余量。它离真正的「海量小条目」攻击（百万级）仍然极远，
            // 而单条目大小（100MB）与压缩比（0.01）两道更强的防线没有放松。
            ZipSecureFile.setMaxFileCount(4000L);
            applied = true;
        }
    }
}
