package uno.zhuchen.agent.tools.rag.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库种子数据注入器 — 首次启动幂等注入示例旅游知识.
 *
 * <p>作用: 让知识库开箱即有可检索内容, 便于演示与验收.
 * 幂等: source_ref='seed' 的文档已 indexed 时直接跳过;
 * 自愈: 上次注入 failed 时本次启动自动重试(先清理残留再重注入).
 * 失败兜底: 注入异常仅记录文档状态与日志, 不阻断服务启动.
 *
 * <p>开关: rag.seed.enabled=false 可关闭(默认开启).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeSeeder implements ApplicationRunner {

    /** 种子文档溯源标识, 幂等判断依据 */
    private static final String SEED_REF = "seed";

    private final VectorStore vectorStore;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;

    /**
     * 构造注入向量库与知识表 Mapper.
     *
     * @param vectorStore Milvus 向量库
     * @param docMapper   知识文档表 Mapper
     * @param chunkMapper 知识分块表 Mapper
     */
    public KnowledgeSeeder(VectorStore vectorStore,
                           KnowledgeDocMapper docMapper,
                           KnowledgeChunkMapper chunkMapper) {
        this.vectorStore = vectorStore;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 启动后执行: 按幂等规则注入种子知识.
     *
     * @param args 启动参数(未使用)
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            Long count = docMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDoc>()
                            .eq(KnowledgeDoc::getSourceRef, SEED_REF));
            if (count != null && count > 0) {
                // 幂等检查: 查 failed 记录决定是否自愈重试
                Long failed = docMapper.selectCount(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDoc>()
                                .eq(KnowledgeDoc::getSourceRef, SEED_REF)
                                .eq(KnowledgeDoc::getStatus, "failed"));
                if (failed == 0) {
                    log.info("种子知识已存在且状态正常, 跳过注入 (幂等)");
                    return;
                }
                log.info("检测到上次种子注入失败, 启动自愈重试");
                cleanupFailedSeed();
            }
            inject();
            log.info("种子知识注入完成");
        } catch (Exception e) {
            // 兜底: 种子失败不阻断服务启动
            log.warn("种子知识注入异常(不影响服务): {}", e.getMessage());
        }
    }

    /**
     * 清理上次失败的种子残留(删除 failed 文档及其分块与向量).
     */
    private void cleanupFailedSeed() {
        List<KnowledgeDoc> failedDocs = docMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDoc>()
                        .eq(KnowledgeDoc::getSourceRef, SEED_REF)
                        .eq(KnowledgeDoc::getStatus, "failed"));
        for (KnowledgeDoc doc : failedDocs) {
            List<KnowledgeChunk> chunks = chunkMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeChunk>()
                            .eq(KnowledgeChunk::getDocId, doc.getId()));
            List<String> chunkIds = chunks.stream().map(KnowledgeChunk::getId).toList();
            if (!chunkIds.isEmpty()) {
                try {
                    vectorStore.delete(chunkIds);
                } catch (Exception e) {
                    log.warn("清理向量残留失败(忽略): {}", e.getMessage());
                }
                chunkMapper.deleteByIds(chunkIds);
            }
            docMapper.deleteById(doc.getId());
            log.info("已清理失败的种子文档: id={}, title={}", doc.getId(), doc.getTitle());
        }
    }

    /**
     * 注入种子知识: 建文档 -> 落分块 -> 向量化入 Milvus -> 回写状态.
     */
    private void inject() {
        // 1. 创建文档记录 (processing)
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle("内置旅游知识种子集");
        doc.setSourceType("seed");
        doc.setSourceRef(SEED_REF);
        doc.setStatus("processing");
        doc.setChunkCount(0);
        docMapper.insert(doc);
        try {
            // 2. 构造分块
            List<SeedEntry> entries = seedEntries();
            List<Document> documents = new ArrayList<>();
            List<KnowledgeChunk> chunks = new ArrayList<>();
            int seq = 0;
            for (SeedEntry entry : entries) {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocId(doc.getId());
                chunk.setSeq(seq);
                chunk.setContent(entry.content());
                chunk.setCharCount(entry.content().length());
                chunk.setDestination(entry.destination());
                chunk.setTheme(entry.theme());
                chunk.setEmbeddingStatus("pending");
                chunkMapper.insert(chunk);
                chunks.add(chunk);

                // Milvus 文档: 主键=chunk.id, metadata 供过滤与结果展示
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("docId", doc.getId());
                metadata.put("docTitle", doc.getTitle());
                metadata.put("destination", entry.destination());
                metadata.put("theme", entry.theme());
                metadata.put("source", "seed");
                metadata.put("seq", seq);
                documents.add(new Document(chunk.getId(), entry.content(), metadata));
                seq++;
            }

            // 3. 向量化写入 Milvus
            vectorStore.add(documents);

            // 4. 回写状态
            for (KnowledgeChunk chunk : chunks) {
                chunk.setEmbeddingStatus("indexed");
                chunkMapper.updateById(chunk);
            }
            doc.setChunkCount(chunks.size());
            doc.setStatus("indexed");
            docMapper.updateById(doc);
            log.info("种子知识注入: docId={}, chunks={}", doc.getId(), chunks.size());
        } catch (Exception e) {
            // 5. 失败记录: 下次启动自愈重试
            doc.setStatus("failed");
            doc.setErrorMsg(truncate(e.getMessage()));
            docMapper.updateById(doc);
            throw new IllegalStateException("种子注入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 截断错误信息到列宽上限内.
     *
     * @param msg 原始错误信息
     * @return 截断后的信息(最长 1024 字符)
     */
    private String truncate(String msg) {
        if (msg == null) {
            return "unknown";
        }
        return msg.length() > 1024 ? msg.substring(0, 1024) : msg;
    }

    /** 种子知识条目: 目的地 + 主题 + 内容 */
    private record SeedEntry(String destination, String theme, String content) {
    }

    /**
     * 内置种子知识集 — 覆盖主流目的地的景点/美食/行程经验.
     *
     * @return 种子条目列表
     */
    private List<SeedEntry> seedEntries() {
        return List.of(
                new SeedEntry("北京", "景点", "故宫博物院是中国现存规模最大的古代宫殿建筑群, 建议游览路线为午门-太和殿-中和殿-保和殿-乾清宫-御花园-神武门, 全程约4小时. 旺季需提前在官方渠道实名预约门票, 周一闭馆. 上午入馆人较少, 下午光线适合拍照."),
                new SeedEntry("北京", "景点", "八达岭长城是长城保存最好的一段, 交通可选择市郊铁路S2线或877路公交直达. 建议早晨出发避开人流, 往返城墙约需3小时, 穿舒适的运动鞋并携带足量饮水. 秋季(10月中下旬)红叶季风景最佳."),
                new SeedEntry("北京", "美食", "北京烤鸭以全聚德与便宜坊为代表, 挂炉烤鸭皮脆肉嫩, 佐以荷叶饼葱丝甜面酱食用. 大董烤鸭创新了酥不腻工艺. 一只烤鸭适合2-3人食用, 人均消费150-300元, 热门门店建议提前订位."),
                new SeedEntry("上海", "景点", "外滩是上海地标, 沿黄浦江可欣赏万国建筑博览群, 对岸即陆家嘴东方明珠与上海中心. 夜景灯光19:00后开启, 22:00关闭. 建议傍晚从南京东路步行至外滩, 乘坐轮渡过江体验夜景视角, 轮渡票价2元."),
                new SeedEntry("上海", "行程", "上海迪士尼乐园建议早7:30前到达排队安检, 开园后直奔热门项目创极速光轮与飞越地平线. 官方App实时查看排队时间, 善用早享卡与单人通道可节省大量等待. 城堡烟花秀于闭园前30分钟开始, 提前30分钟占位."),
                new SeedEntry("成都", "景点", "成都大熊猫繁育研究基地建议开园(7:30)即入园, 熊猫上午最活跃, 下午多在睡觉. 月亮产房与太阳产房可看熊猫幼崽. 园区较大, 可乘观光车游览, 全程约3小时. 门票55元, 需提前线上实名预约."),
                new SeedEntry("成都", "美食", "成都火锅以牛油锅底为特色, 推荐门店包括小龙坎与蜀大侠, 鸳鸯锅适合不吃辣的同行者. 串串香是平价替代, 人均50-80元. 玉林路与建设路是本地人常去的美食聚集区, 避开宽窄巷子内高价店铺."),
                new SeedEntry("杭州", "景点", "西湖环湖经典路线: 断桥残雪-白堤-平湖秋月-孤山-苏堤春晓-花港观鱼-雷峰塔, 步行加游船约需大半天. 游船55元可登三潭印月岛. 春季苏堤桃柳最美, 夏季曲院风荷赏荷. 全程免费, 雷峰塔门票40元."),
                new SeedEntry("杭州", "美食", "杭州特色美食包括西湖醋鱼, 东坡肉, 龙井虾仁与片儿川面. 楼外楼与知味观是老字号代表, 但价格偏高; 本地人更推荐新白鹿与绿茶餐厅, 人均60-100元. 龙井村可品茶, 明前龙井为上品."),
                new SeedEntry("西安", "景点", "秦始皇兵马俑博物馆距市区约40公里, 可乘游5(306)路公交直达. 参观顺序建议一号坑-三号坑-二号坑-铜车马馆, 一号坑规模最震撼. 讲解建议请导游或租电子讲解器, 否则难以理解细节. 全程约3小时, 旺季需提前预约."),
                new SeedEntry("西安", "美食", "回民街是西安美食集中地, 推荐羊肉泡馍(老米家大雨), 腊汁肉夹馍与biangbiang面. 钟鼓楼附近的本地老字号比主街摊位更可靠. 羊肉泡馍需自己掰馍, 掰得越碎越入味. 人均消费40-80元."),
                new SeedEntry("重庆", "行程", "重庆经典一日路线: 上午磁器口古镇-中午李子坝轻轨穿楼-下午鹅岭二厂-傍晚洪崖洞夜景. 洪崖洞夜景灯光22:00关闭, 最佳观景点在千厮门大桥与江对岸. 重庆地形立体, 导航常不准确, 善用轨道交通2号线与过江索道.")
        );
    }
}
