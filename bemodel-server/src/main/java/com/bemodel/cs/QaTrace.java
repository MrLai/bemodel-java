package com.bemodel.cs;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 问数答案证据链（V33）：五段证据落库，trace_id 可在 /trace 页逐段还原（无锚点不输出）。 */
@Data
@TableName("bm_qa_trace")
public class QaTrace {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    /** ANALYTICS / CS */
    private String scene;
    private String question;
    private String dsCode;
    private String usedTables;
    private String sqlText;
    private String semantics;
    /** 命中的 PUBLISHED 概念 code（逗号分隔） */
    private String matchedConcepts;
    private Integer rowCount;
    private String answer;
    /** LLM / TEMPLATE（诚实标注作答来源） */
    private String answerSource;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
