package com.fridayclass.service.agent.export;

import com.fridayclass.common.BusinessException;
import com.fridayclass.enums.FileFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 格式 → 转换器 的注册表。Spring 会把所有 {@link MarkdownExporter} 实现注入进来。
 *
 * <h3>为什么在<b>构造器里</b>就要求四种格式齐全</h3>
 *
 * 缺一个转换器的后果是：老师选了那种格式、写了半天、模型也跑完了，
 * 最后在「写文件」这一步才 500 —— 前面烧掉的 token 全白费，
 * 而报错离真正的原因（忘了写那个类）隔了十万八千里。
 *
 * <p>放到启动时校验，这个问题就变成「应用起不来」——
 * 一眼可见，且<b>不可能被带到演示现场</b>。这与 {@code DeepSeekClient}
 * 在缺 API Key 时直接让启动失败是同一个立场：
 * 宁可起不来，也不要静默地半可用。
 */
@Component
public class MarkdownExporterRegistry {

    private static final Logger log = LoggerFactory.getLogger(MarkdownExporterRegistry.class);

    private final Map<FileFormat, MarkdownExporter> byFormat;

    public MarkdownExporterRegistry(List<MarkdownExporter> exporters) {
        Map<FileFormat, MarkdownExporter> map = new EnumMap<>(FileFormat.class);
        for (MarkdownExporter exporter : exporters) {
            MarkdownExporter previous = map.put(exporter.format(), exporter);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "格式 %s 注册了两个转换器：%s 与 %s",
                        exporter.format(), previous.getClass().getName(), exporter.getClass().getName()));
            }
        }
        for (FileFormat format : FileFormat.values()) {
            if (!map.containsKey(format)) {
                throw new IllegalStateException(
                        "缺少格式 " + format + " 的转换器（MarkdownExporter 实现类）");
            }
        }
        this.byFormat = map;
        log.info("agent_exporters_ready formats={}", map.keySet());
    }

    /** 取某种格式的转换器。构造器已保证一定存在。 */
    public MarkdownExporter get(FileFormat format) {
        MarkdownExporter exporter = byFormat.get(format);
        if (exporter == null) {
            // 理论上到不了这里（构造器校验过），留一手以防将来绕过注册表直接 new
            throw new BusinessException(500, "不支持的输出格式：" + format);
        }
        return exporter;
    }
}
