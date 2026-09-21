package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bemodel.cs.mapper.ReconDiffMapper;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.service.LinkService;
import com.bemodel.rca.entity.RcaCase;
import com.bemodel.rca.mapper.RcaCaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 智慧客服验证：打开工单自动完成诊断（无"发起"动作），
 * 一键处置生成退费申请 + 处置单 + 工单办结，且不可重复处置。
 * 用合成工单测试，结束全量清理，不污染演示工单。
 */
@SpringBootTest
class CsServiceTest {

    @Autowired
    private CsService csService;
    @Autowired
    private LinkService linkService;
    @Autowired
    private RcaCaseMapper caseMapper;
    @Autowired
    private DatasourceService datasourceService;
    @Autowired
    private com.bemodel.cs.mapper.CsFeedbackMapper csFeedbackMapper;
    @Autowired
    private ReconDiffMapper reconDiffMapper;

    /** 水位线：核对类测试会向 bm_recon_diff 落批次，结束只清理本次新增行 */
    private long reconWatermark;

    private LinkNode ticket;

    @BeforeEach
    void setUp() {
        reconWatermark = reconWatermark();
        ticket = new LinkNode();
        ticket.setNodeType("TICKET");
        ticket.setRefNo("TEST-CS-001");
        ticket.setTitle("测试工单：住院患者检验项目撤销后仍收费");
        ticket.setConceptCode("FEE_DETAIL");
        ticket.setStatus("未处置");
        ticket.setOccurredAt(LocalDateTime.now());
        ticket.setPayload("{\"inhos_no\":\"ZY20260815001\",\"patient\":\"张建国\"}");
        linkService.save(ticket);
    }

    @AfterEach
    void tearDown() {
        // 清理合成数据：工单/处置单/诊断案例/测试退费申请
        linkService.remove(new LambdaQueryWrapper<LinkNode>().eq(LinkNode::getRefNo, "TEST-CS-001"));
        linkService.remove(new LambdaQueryWrapper<LinkNode>().eq(LinkNode::getRefNo, "DP-TEST-CS-001"));
        caseMapper.delete(new LambdaQueryWrapper<RcaCase>().eq(RcaCase::getTicketRef, "TEST-CS-001"));
        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        charge.update("DELETE FROM refund_apply WHERE reason LIKE '%TEST-CS-001%'");
        reconDiffMapper.delete(new LambdaQueryWrapper<ReconDiff>().gt(ReconDiff::getId, reconWatermark));
    }

    private long reconWatermark() {
        Object v = reconDiffMapper.selectObjs(new QueryWrapper<ReconDiff>()
                .select("COALESCE(MAX(id),0) AS mid")).stream().findFirst().orElse(0);
        return v == null ? 0 : ((Number) v).longValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosisShouldRunAutomatically() {
        Map<String, Object> diag = csService.diagnosis(ticket.getId());

        assertNotNull(diag.get("case"), "应自动产出诊断案例");
        assertFalse(((List<?>) diag.get("steps")).isEmpty(), "应有AI排查路径（探针步骤）");
        assertNotNull(diag.get("report"), "应有诊断报告");
        assertNotNull(diag.get("customerReply"), "应有客户回复话术");
        assertTrue(String.valueOf(diag.get("customerReply")).length() > 20, "话术应有实质内容");
        assertNotNull(diag.get("suggestions"), "应有处置建议");

        // 幂等：再次打开复用同一诊断，不重复执行
        Map<String, Object> again = csService.diagnosis(ticket.getId());
        assertEquals(Boolean.FALSE, again.get("fresh"), "再次打开应复用既有诊断");
    }

    @Test
    @SuppressWarnings("unchecked")
    void askShouldRouteDispensePayQuestion() {
        Map<String, Object> r = csService.ask("没缴费可以发药吗？");

        assertEquals("缴费发药双向核对（语义档）", r.get("intent"), "缴费发药问题应路由到语义档双向核对");
        assertEquals("RULE", r.get("router"));
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) r.get("evidence");
        assertTrue(evidence.stream().anyMatch(e -> String.valueOf(e.get("label")).contains("先药后费")),
                "证据应含先药后费方向");
        assertTrue(evidence.stream().anyMatch(e -> String.valueOf(e.get("label")).contains("真滞留")),
                "证据应含已缴费未发药的真实滞留档（B 方向语义化结论）");
        assertTrue(String.valueOf(r.get("answer")).contains("缴费是发药的前置环节"));
    }

    @Test
    void askShouldRouteDispenseSplitQuestion() {
        Map<String, Object> r = csService.ask("一个医嘱可以分开发药吗？");

        assertEquals("分次发药核对", r.get("intent"), "分开发药问题应路由到 1:N 核对而非缴费核对");
        assertTrue(String.valueOf(r.get("answer")).contains("1:N"));
    }

    @Test
    void askShouldRouteDispenseReturnQuestion() {
        Map<String, Object> r = csService.ask("发药后可以部分退药吗");

        assertEquals("退药核对", r.get("intent"), "退药问题应路由到退药×退费联动核对");
        assertTrue(String.valueOf(r.get("answer")).contains("退药"));
    }

    @Test
    void askShouldFallBackToMenuForUnknownQuestion() {
        Map<String, Object> r = csService.ask("今天天气怎么样");

        assertEquals("能力引导", r.get("intent"), "词表外问题应落到能力菜单而非编造答案");
    }

    @Test
    @SuppressWarnings("unchecked")
    void askShouldForkRoutingByScene() {
        // 同一句话两个场景走不同分支：客服场景命中专属核对处理器，问数场景进语义层
        Map<String, Object> cs = csService.ask("没缴费可以发药吗？", CsService.SCENE_CS);
        assertEquals("缴费发药双向核对（语义档）", cs.get("intent"), "客服场景应走语义档专属核对处理器");

        Map<String, Object> qa = csService.ask("没缴费可以发药吗？", CsService.SCENE_ANALYTICS);
        assertNotEquals("缴费发药双向核对（语义档）", qa.get("intent"), "问数场景不应落入客服专属处理器");
        // 无 Key 时语义层不可用：应降级为问数兜底菜单（场景引导），而非客服菜单
        assertEquals("场景引导", qa.get("intent"), "问数场景应落问数兜底菜单");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackMenusShouldCrossReferEachScene() {
        Map<String, Object> csMenu = csService.ask("今天天气怎么样");
        assertEquals("REFERRAL", csMenu.get("router"), "未命中应显式标记 REFERRAL 而非靠字符串嗅探");
        assertTrue(String.valueOf(csMenu.get("links")).contains("/ask"), "客服兜底菜单应转介智能问数");

        Map<String, Object> qaMenu = csService.ask("今天天气怎么样", CsService.SCENE_ANALYTICS);
        assertEquals("REFERRAL", qaMenu.get("router"));
        assertTrue(String.valueOf(qaMenu.get("links")).contains("/cs"), "问数兜底菜单应转介 AI 客服");
    }

    @Test
    @SuppressWarnings("unchecked")
    void glossaryMetricHitShouldCarryMetricCard() {
        // 口径卡：指标命中时附带结构化字段（定义/公式/探针SQL 均取自 bm_metric 真实数据，卡上 SQL 与巡检同源）
        Map<String, Object> r = csService.ask("出院人数怎么算？", CsService.SCENE_ANALYTICS);

        assertEquals("口径查询", r.get("intent"));
        assertEquals("METRIC", r.get("card"), "指标命中应显式标记口径卡，前端不做字符串嗅探");
        Map<String, Object> card = (Map<String, Object>) r.get("metric");
        assertEquals("DISCHARGE_COUNT", card.get("metricCode"), "口径问题应优先命中指标（出院人数）");
        assertNotNull(card.get("definition"), "口径定义应来自指标库");
        assertTrue(card.get("probeSql") != null && String.valueOf(card.get("probeSql")).contains("COUNT"),
                "探针SQL应来自指标库真实配置");
        assertEquals(Boolean.TRUE, card.get("hasProbe"), "数据源+探针齐备的指标应显式带 hasProbe 标记（文案按真实能力分支）");
        List<Map<String, Object>> links = (List<Map<String, Object>>) r.get("links");
        assertTrue(String.valueOf(links.get(0).get("route")).startsWith("/glossary?metric="),
                "口径卡应带统一口径页指标深链");
    }

    @Test
    @SuppressWarnings("unchecked")
    void glossaryCardShouldStayAnalyticsOnly() {
        // CS 场景保持历史文本形态：同一问题不出口径卡、链接为通用 /glossary（卡片仅问数场景前置路径产出）
        Map<String, Object> cs = csService.ask("出院人数怎么算？", CsService.SCENE_CS);
        assertEquals("口径查询", cs.get("intent"));
        assertNull(cs.get("card"), "客服场景不应附带口径卡字段");
        List<Map<String, Object>> links = (List<Map<String, Object>>) cs.get("links");
        assertEquals("/glossary", links.get(0).get("route"), "客服场景应保持通用术语页链接");

        // 问数场景下，仅术语文本命中（名称不直命中）也应走文本回答：什么是检验申请 不该卡到检验撤销率
        Map<String, Object> qa = csService.ask("什么是检验申请？", CsService.SCENE_ANALYTICS);
        assertNull(qa.get("card"), "仅定义顺带命中时不应出口径卡");
    }

    @Test
    void routePromptShouldCarryMistakeFeedback() {
        // 反馈回路：correct=0 的错例应出现在路由提示词尾部（LLM 看到前车之鉴）
        CsFeedback f = csService.saveFeedback("测试错例问题XYZ", "MATERIAL", "LLM", 0, "正确应为 OTHER");
        try {
            String prompt = csService.routePrompt("任意问题");
            assertTrue(prompt.contains("测试错例问题XYZ"), "错例原文应进提示词");
            assertTrue(prompt.contains("前车之鉴"), "应有错例段落引导语");
            assertTrue(prompt.contains("正确应为 OTHER"), "备注（正确意图）应进提示词");
        } finally {
            csFeedbackMapper.deleteById(f.getId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void refundActionShouldCloseTheLoop() {
        csService.diagnosis(ticket.getId()); // 先诊断（客服真实操作顺序）
        Map<String, Object> result = csService.refundAction(ticket.getId(), "客服 测试");

        assertTrue(((Number) result.get("refundCount")).intValue() >= 5, "应批量生成退费申请（≥5笔）");
        assertNotNull(result.get("disposalRef"), "应登记处置单");

        // 工单已办结，处置单进入链路视图
        assertEquals("已处置", linkService.getById(ticket.getId()).getStatus());
        assertNotNull(linkService.getByRefNo(String.valueOf(result.get("disposalRef"))));

        // 不可重复处置
        assertThrows(Exception.class, () -> csService.refundAction(ticket.getId(), "客服 测试"));
    }
}
