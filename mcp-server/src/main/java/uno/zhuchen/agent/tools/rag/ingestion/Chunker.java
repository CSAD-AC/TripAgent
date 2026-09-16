package uno.zhuchen.agent.tools.rag.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识文档分块器 — 将长文本切成可向量化的知识块.
 *
 * <p>策略(对应 {@code dev-docs/RAG_PLAN.md} 3.3):
 * <ul>
 *   <li>markdown: 优先按 {@code #/##/###} 标题边界切分, 保留标题语义; 超长段落再用滑窗补齐</li>
 *   <li>无结构文本: 固定窗口滑切, 相邻块带 overlap 防止语义断裂</li>
 * </ul>
 * 分块参数(chunkSize / overlap)由调用方(yml 配置)传入, 便于 reindex 调参.
 *
 * <p>线程安全: 纯静态无状态.
 */
public final class Chunker {

    /** markdown 标题行(1~3 级): "# "、“## ” 或 "### " */
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+.*$");

    private Chunker() {
    }

    /**
     * 按文档类型自动分块(markdown 标题优先, 否则滑窗).
     *
     * @param text       文档原文
     * @param chunkSize  目标块大小(字符数)
     * @param overlap    相邻块重叠字符数
     * @param minChunk   小于该字符数的块会被尝试合并进前一块
     * @return 分块列表(剔除空白块)
     */
    public static List<String> chunk(String text, int chunkSize, int overlap, int minChunk) {
        if (text == null) {
            return List.of();
        }
        String clean = clean(text).trim();
        if (clean.isEmpty()) {
            return List.of();
        }
        List<String> sections = splitByHeading(clean);
        // 含 markdown 标题(含单一标题)时按标题语义分块, 避免滑窗把标题行切碎丢失语义
        boolean hasHeading = sections.stream().anyMatch(s -> s != null && s.startsWith("#"));
        List<String> result;
        if (hasHeading) {
            result = chunkSections(sections, chunkSize, overlap, minChunk);
        } else {
            // 无标题纯文本: 整体滑窗
            result = slidingWindow(clean, chunkSize, overlap);
        }
        // minChunk 生效: 过短块并入前一块, 控制碎片
        return mergeTiny(result, minChunk);
    }

    /**
     * 合并过短分块: 长度 {@code < minChunk} 且非首块的并入其前一块(自后向前).
     *
     * @param chunks   分块列表
     * @param minChunk 最小块阈值(<=1 时视为不合并)
     * @return 合并后分块列表
     */
    private static List<String> mergeTiny(List<String> chunks, int minChunk) {
        if (chunks.isEmpty() || minChunk <= 1) {
            return chunks;
        }
        List<String> out = new ArrayList<>(chunks);
        for (int i = out.size() - 1; i >= 1; i--) {
            if (out.get(i).length() < minChunk) {
                out.set(i - 1, out.get(i - 1) + "\n" + out.get(i));
                out.remove(i);
            }
        }
        return out;
    }

    /**
     * 清洗文本: 折叠连续空行为单空行, 去除行尾空白.
     *
     * @param text 原始文本
     * @return 清洗后文本
     */
    private static String clean(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.replaceAll("\\n{3,}", "\n\n");
    }

    /**
     * 按 markdown 标题切分段落, 每个标题连同其后内容作为一个 section.
     *
     * @param text 清洗后文本
     * @return section 列表(第一个若为正文则作为无标题段)
     */
    private static List<String> splitByHeading(String text) {
        List<String> sections = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (HEADING.matcher(line).matches()) {
                // 遇到新标题, 先收尾上一个 section
                if (current.length() > 0) {
                    sections.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(line);
            } else {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line);
            }
        }
        if (current.length() > 0) {
            sections.add(current.toString().trim());
        }
        return sections;
    }

    /**
     * 各 section 分块: 短块保留(作为独立块), 超长块滑窗切分.
     *
     * @param sections  标题切分后的段落
     * @param chunkSize 目标块大小
     * @param overlap   重叠字符数
     * @param minChunk  极短块阈值
     * @return 分块列表
     */
    private static List<String> chunkSections(List<String> sections, int chunkSize, int overlap, int minChunk) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String section : sections) {
            if (section.length() <= chunkSize) {
                // 合并小段进缓冲, 避免生成大量空信息块
                if (buffer.length() + section.length() + 1 <= chunkSize) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(section);
                } else {
                    if (buffer.length() > 0) {
                        result.add(buffer.toString().trim());
                        buffer.setLength(0);
                    }
                    buffer.append(section);
                }
            } else {
                // 超长 section: 先落缓冲, 再滑窗
                if (buffer.length() > 0) {
                    result.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                result.addAll(slidingWindow(section, chunkSize, overlap));
            }
        }
        if (buffer.length() > 0) {
            result.add(buffer.toString().trim());
        }
        return result.stream().filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 固定窗口滑窗分块, 相邻块保留 overlap 重叠.
     *
     * @param text      文本
     * @param chunkSize 块大小
     * @param overlap   重叠
     * @return 分块列表
     */
    private static List<String> slidingWindow(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        int step = Math.max(1, chunkSize - Math.max(0, overlap));
        List<String> result = new ArrayList<>();
        int len = text.length();
        for (int start = 0; start < len; start += step) {
            int end = Math.min(len, start + chunkSize);
            result.add(text.substring(start, end).trim());
            if (end >= len) {
                break;
            }
        }
        return result.stream().filter(s -> !s.isEmpty()).toList();
    }
}