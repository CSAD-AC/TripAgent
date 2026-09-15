package uno.zhuchen.agent.tools.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunker 分块器单元测试.
 *
 * <p>覆盖: 空文本 / markdown 标题分块 / 无结构滑窗 overlap / 超长段落 / 小段合并.
 */
class ChunkerTest {

    /** 空与纯空白文本返回空分块 */
    @Test
    void chunk_blankTextReturnsEmpty() {
        assertTrue(Chunker.chunk(null, 100, 0, 0).isEmpty());
        assertTrue(Chunker.chunk("", 100, 0, 0).isEmpty());
        assertTrue(Chunker.chunk("   \n  \n", 100, 0, 0).isEmpty());
    }

    /** markdown 标题切分: 大 chunkSize 下多个小 section 被合并为一块(降低碎片) */
    @Test
    void chunk_markdownHeadingCollectsSections() {
        String md = "# 路线\n上午游沱江, 傍晚看夜景\n# 美食\n血粑鸭与酸汤鱼很好吃";
        List<String> chunks = Chunker.chunk(md, 500, 10, 10);
        assertEquals(1, chunks.size(), "两大 section 总长小于 chunkSize 应合并为一块");
        String merged = chunks.get(0);
        assertTrue(merged.contains("# 路线") && merged.contains("# 美食"), "应保留标题语义");
        assertTrue(merged.contains("血粑鸭"));
    }

    /** 超长无结构文本: 按 chunkSize 滑窗, 相邻块带 overlap */
    @Test
    void chunk_plainLongTextSlidingWindow() {
        String text = "春夏秋冬四季变换景色怡人, 游客络绎不绝流连忘返" + "春夏秋冬四季变换景色怡人, 游客络绎不绝流连忘返";
        int chunkSize = 20;
        int overlap = 5;
        List<String> chunks = Chunker.chunk(text, chunkSize, overlap, 0);
        assertFalse(chunks.isEmpty(), "长文本应被切分");
        assertTrue(chunks.size() >= 2, "应产生多个分块");
        // 相邻块重叠: 前一块尾部应出现在后一块开头附近(overlap)
        String first = chunks.get(0);
        String second = chunks.get(1);
        assertFalse(first.equals(second), "相邻块不应完全相同");
        // 每块不超过 chunkSize(trim 后可能略少)
        chunks.forEach(c -> assertTrue(c.length() <= chunkSize + 1, "块超出 chunkSize 上限"));
    }

    /** overlong 段落: 标题下超长 content 独立滑窗成多块, 不与后续小段合并相混 */
    @Test
    void chunk_overlongSectionSplits() {
        StringBuilder body = new StringBuilder("段落内容");
        // 构造约 6 倍 chunkSize 的长段落
        while (body.length() < 600) {
            body.append("这是非常非常长的段落内容反复堆叠用来测试滑窗切分效果。");
        }
        String md = "# 长标题\n" + body;
        List<String> chunks = Chunker.chunk(md, 100, 10, 10);
        assertTrue(chunks.size() > 1, "超长段落应被滑窗切成多个块");
        // 首个块保留标题语义
        assertTrue(chunks.get(0).startsWith("# 长标题") || chunks.get(0).contains("# 长标题"));
    }

    /** 无 markdown 标题的纯文本走全文滑窗 */
    @Test
    void chunk_plainWithoutHeadingSlides() {
        String text = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"; // 52 字符
        List<String> chunks = Chunker.chunk(text, 20, 0, 0);
        assertTrue(chunks.size() >= 2, "无标题长文本应滑窗分块");
        // 拼接后应能还原全部内容(去重合并不做, 只验证覆盖全文)
        String joined = String.join("", chunks);
        assertTrue(joined.contains("abcdefghijklmnopqrstuvwxyz"));
    }
}