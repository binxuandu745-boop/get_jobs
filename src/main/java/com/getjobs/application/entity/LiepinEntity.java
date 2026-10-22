package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 猎聘岗位快照数据实体
 * 保存从猎聘接口获取的有价值字段
 */
@Data
@TableName("liepin_data")
public class LiepinEntity {
    // ========== 岗位字段 ==========
    @TableId
    private Long jobId;           // job.jobId
    private String jobTitle;      // job.title
    private String jobLink;       // job.link
    private String jobSalaryText; // job.salary
    private String jobArea;       // job.dq
    private String jobEduReq;     // job.requireEduLevel
    private String jobExpReq;     // job.requireWorkYears
    private String jobPublishTime;// job.refreshTime

    // ========== 公司字段 ==========
    private Long compId;          // comp.compId
    private String compName;      // comp.compName
    private String compIndustry;  // comp.compIndustry
    private String compScale;     // comp.compScale

    // ========== HR字段 ==========
    private String hrId;          // recruiter.recruiterId
    private String hrName;        // recruiter.recruiterName
    private String hrTitle;       // recruiter.recruiterTitle
    private String hrImId;        // recruiter.imId

    // ========== 投递状态 ==========
    // 0 未沟通；1 历史已投递或已确认建立沟通（不代表送达）；2 预留/结果待确认，禁止自动重发
    private Integer delivered;

    // ========== 系统字段 ==========
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
