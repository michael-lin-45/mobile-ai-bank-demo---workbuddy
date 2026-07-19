package com.mobileagent.app.observability.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 种子语料（参考检索器的知识源）。
 *
 * <p>提供一组银行领域中文文档，使参考检索器在端到端测试中能稳定产生相关性打分与命中。
 * 注入 {@link MinimalReferenceRetriever}，供测试 / E2E 诊断使用。
 *
 * <p>本期为<b>内存确定性</b>实现（零外部依赖）；接真实向量库时，本类可由数据库 /
 * 文档加载器替换，不影响检索器、装饰器与上层编排。
 */
public class RagSampleCorpus {

    private final List<RagDocument> documents = new ArrayList<>();

    public RagSampleCorpus() {
        init();
    }

    /** 返回不可变视图，避免外部修改污染语料 */
    public List<RagDocument> getDocuments() {
        return List.copyOf(documents);
    }

    private void init() {
        documents.add(build("doc-transfer-1",
                "如何转账给他人？打开手机银行，选择转账功能，输入收款人姓名和卡号，确认金额后完成转账。",
                "转账操作指南", "high"));
        documents.add(build("doc-transfer-2",
                "跨行转账手续费怎么算？一般按金额阶梯收取，部分银行贵宾客户免收，具体以转账页面提示为准。",
                "转账手续费说明", "medium"));
        documents.add(build("doc-bill-1",
                "怎么查账单明细？进入手机银行账单页面，选择月份即可查看当月收支明细与消费分类。",
                "账单查询指引", "high"));
        documents.add(build("doc-bill-2",
                "信用卡账单日和还款日分别是哪天？账单日生成当期账单，还款日之前还清即可免息，建议设置自动还款。",
                "信用卡还款说明", "medium"));
        documents.add(build("doc-wealth-1",
                "稳健型理财产品有哪些？货币型基金和债券型基金风险较低，适合稳健型投资者，收益相对稳定。",
                "理财推荐-稳健", "high"));
        documents.add(build("doc-wealth-2",
                "朝朝盈是什么产品？朝朝盈是现金管理类理财，类似货币基金，随时申赎，收益按日计提。",
                "理财产品解读-朝朝盈", "medium"));
        documents.add(build("doc-wealth-3",
                "基金和股票有什么区别？股票波动大、风险高，基金由专业经理分散投资，风险相对可控。",
                "投资知识-基金股票", "low"));
        documents.add(build("doc-card-1",
                "银行卡丢失怎么办？立即在手机银行或客服热线挂失，冻结账户资金，再到网点补办新卡。",
                "卡片安全指引", "medium"));
    }

    private RagDocument build(String id, String content, String title, String priority) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "help-center");
        metadata.put("title", title);
        metadata.put(RagConstants.METADATA_PRIORITY_KEY, priority);
        return RagDocument.builder()
                .id(id)
                .content(content)
                .metadata(metadata)
                .score(0.0) // 语料初始分占位，检索时由打分逻辑重算
                .build();
    }
}
