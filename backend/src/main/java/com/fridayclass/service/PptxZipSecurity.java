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
            applied = true;
        }
    }
}
