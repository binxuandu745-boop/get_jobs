package com.getjobs.worker.liepin;

import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.application.service.LiepinService;
import com.getjobs.application.entity.LiepinEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

// 移除保存页面源码相关的导入
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.getjobs.worker.liepin.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Slf4j
@Component
@Scope("prototype")
public class Liepin {
    static {
        // 在类加载时就设置日志文件名，确保Logger初始化时能获取到正确的属性
        System.setProperty("log.name", "liepin");
    }

    private int maxPage = 50;
    private final List<String> resultList = new ArrayList<>();
    private final List<LiepinEntity> lastApiEntities = new ArrayList<>();
    private boolean monitoringRegistered = false;
    private Consumer<Response> searchResponseListener;
    @Setter
    private LiepinConfig config;
    @Getter
    private Date startDate;
    @Setter
    private Page page;
    @Autowired
    private LiepinService liepinService;
    @Autowired
    private LiepinActionPacer actionPacer = new LiepinActionPacer();
    @Autowired
    private LiepinCommunicationObserver communicationObserver = new LiepinCommunicationObserver();
    @Value("${getjobs.liepin.required-role-terms:Java,后端,服务端}")
    private List<String> requiredRoleTerms = List.of("Java", "后端", "服务端");
    @Value("${getjobs.liepin.require-internship:false}")
    private boolean requireInternship;
    @Value("${getjobs.liepin.minimum-monthly-salary:4500}")
    private int minimumMonthlySalary = 4500;
    @Value("${getjobs.liepin.minimum-daily-salary:125}")
    private int minimumDailySalary = 125;

    public interface ProgressCallback {
        void onProgress(String message, Integer current, Integer total);
    }

    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    public void prepare() {
        this.startDate = new Date();
        this.resultList.clear();

        // 监控猎聘接口请求与返回，输出被拦截的URL（精确匹配PC搜索相关接口）
        if (page != null && !monitoringRegistered) {
            searchResponseListener = response -> {
                try {
                    String url = response.url();
                    if (url != null && response.status() == 200 &&
                            url.contains("com.liepin.searchfront4c.pc-search-job") &&
                            !url.contains("com.liepin.searchfront4c.pc-search-job-cond-init")) {
                        log.info("猎聘搜索岗位响应已到达");
                        String contentType = null;
                        try { contentType = response.headers().get("content-type"); } catch (Exception ignored) {}
                        if (contentType == null || contentType.contains("application/json")) {
                            String text = response.text();
                            if (text != null && !text.isEmpty()) {
                                // 尝试解析，若结构不符合则在方法内直接返回
                                parseAndPersistLiepinData(text);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("监控猎聘接口响应时发生错误: {}", e.getMessage());
                }
            };
            page.onResponse(searchResponseListener);
            monitoringRegistered = true;
        }
    }

    public int execute() {
        if (page == null) {
            throw new IllegalStateException("Liepin.page 未设置");
        }
        if (config == null) {
            throw new IllegalStateException("Liepin.config 未设置");
        }

        List<String> keywords = config.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            log.warn("未配置关键词，执行结束");
            return 0;
        }

        try {
            prepare();
            for (String keyword : keywords) {
                checkStopRequested();
                submit(keyword);
            }
            return resultList.size();
        } finally {
            if (searchResponseListener != null) {
                try {
                    page.offResponse(searchResponseListener);
                } catch (RuntimeException failure) {
                    log.warn("猎聘搜索监听器清理失败；异常类型：{}", failure.getClass().getSimpleName());
                }
                monitoringRegistered = false;
                searchResponseListener = null;
            }
        }
    }

    // ========== 解析接口JSON并保存到数据库 ==========
    private void parseAndPersistLiepinData(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            // 兼容两种结构：data.data.jobCardList 或 data.jobCardList
            JsonNode cardList = root.path("data").path("data").path("jobCardList");
            if (!cardList.isArray()) {
                cardList = root.path("data").path("jobCardList");
            }
            if (!cardList.isArray()) {
                return;
            }
            lastApiEntities.clear();
            for (JsonNode item : cardList) {
                JsonNode job = item.path("job");
                JsonNode comp = item.path("comp");
                JsonNode recruiter = item.path("recruiter");

                Long jobId = readLong(job.path("jobId"));
                if (jobId == null) {
                    continue;
                }

                LiepinEntity entity = new LiepinEntity();
                entity.setJobId(jobId);
                entity.setJobTitle(readText(job.path("title")));
                entity.setJobLink(readText(job.path("link")));
                entity.setJobSalaryText(readText(job.path("salary")));
                entity.setJobArea(readText(job.path("dq")));
                entity.setJobEduReq(readText(job.path("requireEduLevel")));
                entity.setJobExpReq(readText(job.path("requireWorkYears")));
                entity.setJobPublishTime(readText(job.path("refreshTime")));

                entity.setCompId(readLong(comp.path("compId")));
                entity.setCompName(readText(comp.path("compName")));
                entity.setCompIndustry(readText(comp.path("compIndustry")));
                entity.setCompScale(readText(comp.path("compScale")));

                entity.setHrId(readText(recruiter.path("recruiterId")));
                entity.setHrName(readText(recruiter.path("recruiterName")));
                entity.setHrTitle(readText(recruiter.path("recruiterTitle")));
                entity.setHrImId(readText(recruiter.path("imId")));

                // 缓存到内存供页面投递显示使用（避免从页面读取文本）
                lastApiEntities.add(entity);
            }
            // 批量持久化：仅不存在时插入，默认 delivered=0
            try {
                liepinService.insertSnapshotsIfNotExistsBatch(lastApiEntities);
            } catch (Exception e) {
                log.warn("批量保存猎聘岗位数据失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("解析猎聘JSON失败: {}", e.getMessage());
        }
    }

    private String readText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String v = node.asText();
        return (v == null || v.isEmpty()) ? null : v;
    }

    private Long readLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            if (node.isNumber()) {
                long v = node.asLong();
                return v == 0 ? null : v;
            }
            if (node.isTextual()) {
                String t = node.asText();
                if (t == null || t.isEmpty()) return null;
                long v = Long.parseLong(t.trim());
                return v == 0 ? null : v;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String safeText(String s) {
        if (s == null) return null;
        return s.replaceAll("\n", " ").replaceAll("【 ", "[").replaceAll(" 】", "]");
    }

    private boolean shouldStop() {
        return Thread.currentThread().isInterrupted()
                || (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get()));
    }

    private void checkStopRequested() {
        if (shouldStop()) throw new CancellationException("用户取消猎聘任务");
    }

    private void requireActionAllowed(Page targetPage) {
        LiepinPageSafety.requireUsablePage(targetPage, this::checkStopRequested);
    }

    private void pauseBeforeAction(Page targetPage, String step) {
        actionPacer.pauseBeforeAction(targetPage, step, this::checkStopRequested);
    }

    private void info(String msg) {
        if (progressCallback != null) {
            progressCallback.onProgress(msg, null, null);
        } else {
            log.info(msg);
        }
    }

    private void submit(String keyword) {
        // 清洗关键词：去掉前后引号与多余空白
        String cleanKeyword = keyword == null ? "" : keyword.replace("\"", "").trim();
        lastApiEntities.clear();
        pauseBeforeAction(page, "搜索岗位");
        page.navigate(getSearchUrl() + "&key=" + java.net.URLEncoder.encode(cleanKeyword,
                        java.nio.charset.StandardCharsets.UTF_8),
                new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(45_000));
        requireActionAllowed(page);
        
        // 等待分页元素加载
        page.waitForSelector(PAGINATION_BOX, new Page.WaitForSelectorOptions().setTimeout(10000));
        Locator paginationBox = page.locator(PAGINATION_BOX);
        Locator lis = paginationBox.locator("li");
        setMaxPage(lis);
        
        for (int i = 0; i < maxPage; i++) {
            requireActionAllowed(page);
            try {
                // 尝试关闭订阅弹窗
                Locator closeBtn = page.locator(SUBSCRIBE_CLOSE_BTN);
                if (closeBtn.count() > 0) {
                    closeBtn.click();
                }
            } catch (LiepinTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (Exception ignored) {
            }
            
        // 等待岗位卡片挂载（不要求可见，避免因遮挡造成超时）
        page.waitForSelector(
            JOB_CARDS,
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.ATTACHED)
                .setTimeout(15000)
        );
            // 接口响应在卡片渲染前就已到达并被 onResponse 处理，这里不能再等 30 秒默认超时，
            // 否则每页白白卡住半分钟。lastApiEntities 为空时才短暂等一下。
            if (lastApiEntities.isEmpty()) {
                try {
                    page.waitForResponse(r -> {
                        try {
                            String u = r.url();
                            return u != null && u.contains("com.liepin.searchfront4c.pc-search-job") && r.status() == 200;
                        } catch (Exception ignored) { return false; }
                    }, new Page.WaitForResponseOptions().setTimeout(3000), () -> {});
                } catch (LiepinTaskPausedException | CancellationException stopped) {
                    throw stopped;
                } catch (Exception ignored) {}
            }
            requireActionAllowed(page);
            info(String.format("正在筛选并处理【%s】第【%d】页...", cleanKeyword, i + 1));
            submitJob();
            checkStopRequested();
            info(String.format("第【%d】页处理完成（包含跳过岗位），累计确认建立【%d】次沟通（不代表消息送达）", i + 1, resultList.size()));
            
            // 查找下一页按钮（AntD v5 结构）
            paginationBox = page.locator(PAGINATION_BOX);
            Locator nextLi = paginationBox.locator(NEXT_PAGE);
            if (nextLi.count() > 0) {
                String cls = nextLi.first().getAttribute("class");
                boolean disabled = cls != null && cls.contains("ant-pagination-disabled");
                if (!disabled) {
                    pauseBeforeAction(page, "打开下一页岗位");
                    lastApiEntities.clear(); // 下一页接口数据到达前不要沿用上一页的
                    Locator btn = nextLi.first().locator("button.ant-pagination-item-link");
                    if (btn.count() > 0) {
                        btn.first().click();
                    } else {
                        nextLi.first().click();
                    }
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        info(String.format("【%s】关键词处理完成！", cleanKeyword));
    }

    private String getSearchUrl() {
        String baseUrl = "https://www.liepin.com/zhaopin/?";
        StringBuilder sb = new StringBuilder(baseUrl);
        // 直接拼接参数，参数为空则忽略
        if (config.getCityCode() != null && !config.getCityCode().isEmpty()) {
            sb.append("city=").append(config.getCityCode()).append("&");
            sb.append("dq=").append(config.getCityCode()).append("&");
        }
        if (config.getSalary() != null && !config.getSalary().isEmpty()) {
            sb.append("salary=").append(config.getSalary()).append("&");
        }
        sb.append("currentPage=0");
        return sb.toString();
    }

    private void setMaxPage(Locator lis) {
        try {
            int count = lis.count();
            if (count >= 2) {
                String pageText = lis.nth(count - 2).textContent();
                int page = Integer.parseInt(pageText);
                if (page > 1) {
                    maxPage = page;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void submitJob() {
        // 获取hr数量
        Locator jobCards = page.locator(JOB_CARDS);
        int count = jobCards.count();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            checkStopRequested();
            // 获取当前岗位卡片（用于后续操作与缺省展示）
            Locator currentJobCard = page.locator(JOB_CARDS).nth(i);
            requireActionAllowed(page);
            // Resolve by job ID, not the list index: API snapshots and visible cards can differ in order.
            Long cardJobId = extractJobIdFromCard(currentJobCard);
            LiepinEntity apiEntity = cardJobId == null ? null : lastApiEntities.stream()
                    .filter(entity -> cardJobId.equals(entity.getJobId())).findFirst().orElse(null);
            if (apiEntity == null) {
                log.info("猎聘岗位待确认：无法将卡片与接口数据对应；不进入沟通流程");
                continue;
            }
            if (apiEntity.getDelivered() != null && apiEntity.getDelivered() != 0) {
                log.info("跳过已有沟通或待确认的猎聘岗位，不自动重发 | 岗位ID：{} | 状态：{}",
                        cardJobId, apiEntity.getDelivered());
                continue;
            }
            LiepinTargetFilter.Evaluation evaluation = LiepinTargetFilter.evaluate(
                    apiEntity.getJobTitle(), null, apiEntity.getJobSalaryText(), requiredRoleTerms,
                    requireInternship, minimumMonthlySalary, minimumDailySalary);
            if (evaluation.decision() != LiepinTargetFilter.Decision.ACCEPT) {
                log.info("猎聘岗位{}：{} | 岗位：{} | 薪资：{}",
                        evaluation.decision() == LiepinTargetFilter.Decision.UNKNOWN ? "待确认" : "被过滤",
                        evaluation.reason(), apiEntity.getJobTitle(), apiEntity.getJobSalaryText());
                continue;
            }
            String jobName = safeText(apiEntity.getJobTitle());
            String companyName = safeText(apiEntity.getCompName());
            String salary = safeText(apiEntity.getJobSalaryText());
            String recruiterName = safeText(apiEntity.getHrName());
            if (recruiterName == null) recruiterName = "HR";
            if (companyName == null) companyName = "公司";
            pauseBeforeAction(page, "处理符合条件岗位");
            if (!cardJobId.equals(extractJobIdFromCard(currentJobCard))) {
                log.info("猎聘列表卡片在等待期间发生变化，跳过本次处理，不发起沟通");
                continue;
            }
            
            try {
                // 使用JavaScript滚动到卡片位置，更稳定
                try {
                    // 先滚动到卡片位置
                    page.evaluate("(element) => element.scrollIntoView({behavior: 'instant', block: 'center'})", currentJobCard.elementHandle());
                    // PlaywrightUtil.sleep(1); // 等待滚动完成
                    
                    // 再次确保元素在视窗中
                    page.evaluate("(element) => { const rect = element.getBoundingClientRect(); if (rect.top < 0 || rect.bottom > window.innerHeight) { element.scrollIntoView({behavior: 'instant', block: 'center'}); } }", currentJobCard.elementHandle());
                    // PlaywrightUtil.sleep(1);
                } catch (LiepinTaskPausedException | CancellationException stopped) {
                    throw stopped;
                } catch (Exception scrollError) {
                    requireActionAllowed(page);
                    log.warn("JavaScript滚动失败，尝试页面滚动: {}", scrollError.getMessage());
                    // 备用方案：滚动页面到大概位置
                    page.evaluate("window.scrollBy(0, " + (i * 200) + ")");
                    // PlaywrightUtil.sleep(1);
                }
                
                // 查找HR区域 - 尝试多种可能的HR标签选择器
                Locator hrArea = null;
                String[] hrSelectors = {
                    ".recruiter-info-box",  // 根据页面源码，这是主要的HR区域类名
                    ".recruiter-info, .hr-info, .contact-info",
                    "[class*='recruiter'], [class*='hr-'], [class*='contact']",
                    ".job-card-footer, .card-footer",
                    ".job-bottom, .bottom-info"
                };
                
                for (String selector : hrSelectors) {
                    Locator tempHrArea = currentJobCard.locator(selector);
                    if (tempHrArea.count() > 0) {
                        hrArea = tempHrArea.first();
                        log.debug("找到HR区域，使用选择器: {}", selector);
                        break;
                    }
                }
                
                // 如果找不到特定的HR区域，使用整个卡片
                if (hrArea == null) {
                    log.debug("未找到特定HR区域，使用整个岗位卡片");
                    hrArea = currentJobCard;
                }
                
                // 鼠标悬停到HR区域，触发按钮显示 - 简化悬停逻辑
                boolean hoverSuccess = false;
                int hoverRetries = 3;
                for (int retry = 0; retry < hoverRetries; retry++) {
                    try {
                        // 检查HR区域是否可见，如果不可见则跳过悬停
                        if (!hrArea.isVisible()) {
                            log.debug("HR区域不可见，跳过悬停操作");
                            hoverSuccess = true; // 设为成功，继续后续流程
                            break;
                        }
                        
                        // 直接悬停，不再进行复杂的微调
                        requireActionAllowed(page);
                        hrArea.hover(new Locator.HoverOptions().setTimeout(5000));
                        hoverSuccess = true;
                        break;
                    } catch (LiepinTaskPausedException | CancellationException stopped) {
                        throw stopped;
                    } catch (Exception hoverError) {
                        requireActionAllowed(page);
                        log.warn("第{}次悬停失败: {}", retry + 1, hoverError.getMessage());
                        if (retry < hoverRetries - 1) {
                            // 重试前重新滚动确保元素可见
                            try {
                                page.evaluate("(element) => element.scrollIntoView({behavior: 'instant', block: 'center'})", currentJobCard.elementHandle());
                                page.waitForTimeout(500); // 只读等待，不阻塞Playwright事件派发
                                requireActionAllowed(page);
                            } catch (LiepinTaskPausedException | CancellationException stopped) {
                                throw stopped;
                            } catch (Exception e) {
                                log.warn("重试前滚动失败: {}", e.getMessage());
                            }
                        }
                    }
                }
                
                if (!hoverSuccess) {
                    log.warn("悬停操作失败，但继续查找按钮");
                    // 不再跳过，而是继续查找按钮，因为有些按钮可能不需要悬停就能显示
                }
                
                // PlaywrightUtil.sleep(1); // 等待按钮显示
                
                // 获取hr名字
                // 已从接口获取 HR 名字，无需再从页面读
                
            } catch (LiepinTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (Exception e) {
                log.error("处理岗位卡片失败: {}", e.getMessage());
                continue;
            }
            
            // 查找聊一聊按钮
            Locator button = null;
            String buttonText = "";
            try {
                // 在当前岗位卡片中查找按钮，尝试多种选择器
                
                String[] buttonSelectors = {
                    "button.ant-btn.ant-btn-primary.ant-btn-round",
                    "button.ant-btn.ant-btn-round.ant-btn-primary", 
                    "button[class*='ant-btn'][class*='primary']",
                    "button[class*='ant-btn'][class*='round']",
                    "button[class*='chat'], button[class*='talk']",
                    ".chat-btn, .talk-btn, .contact-btn",
                    "button:has-text('聊一聊')",
                    "button" // 最后尝试所有按钮
                };
                
                for (String selector : buttonSelectors) {
                    try {
                        Locator tempButtons = currentJobCard.locator(selector);
                        int buttonCount = tempButtons.count();
                        log.debug("选择器 '{}' 找到 {} 个按钮", selector, buttonCount);
                        
                        for (int j = 0; j < buttonCount; j++) {
                            Locator tempButton = tempButtons.nth(j);
                            try {
                                if (tempButton.isVisible()) {
                                    String text = tempButton.textContent();
                                    log.debug("按钮文本: '{}'", text);
                                    if (text != null && !text.trim().isEmpty()) {
                                        button = tempButton;
                                        buttonText = text.trim();
                                        // 只关注"聊一聊"按钮
                                        if (text.contains("聊一聊")) {
                                            log.debug("找到目标按钮: '{}'", text);
                                            break;
                                        }
                                    }
                                }
                            } catch (LiepinTaskPausedException | CancellationException stopped) {
                                throw stopped;
                            } catch (Exception ignore) {
                                log.debug("获取按钮文本失败: {}", ignore.getMessage());
                            }
                        }
                        
                        if (button != null && buttonText.contains("聊一聊")) {
                            break;
                        }
                    } catch (LiepinTaskPausedException | CancellationException stopped) {
                        throw stopped;
                    } catch (Exception e) {
                        log.debug("选择器 '{}' 查找失败: {}", selector, e.getMessage());
                    }
                }
                
            } catch (LiepinTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (Exception e) {
                log.error("查找按钮失败: {}", e.getMessage());
                // 不再保存页面源码
                continue;
            }
            
            // 提取 jobId（用于更新投递状态）
            Long jobIdForUpdate = cardJobId;

            // 检查按钮文本并点击
            if (button != null && buttonText.contains("聊一聊")) {
                boolean reserved = false;
                try {
                    // 在点击按钮前进行鼠标微调，先向右移动2像素，再向左移动2像素
                    try {
                        var boundingBox = button.boundingBox();
                        if (boundingBox != null) {
                            double centerX = boundingBox.x + boundingBox.width / 2;
                            double centerY = boundingBox.y + boundingBox.height / 2;
                            
                            // 先移动到按钮中心
                            page.mouse().move(centerX, centerY);
                            Thread.sleep(50);
                            
                            // 向右移动2像素
                            page.mouse().move(centerX + 2, centerY);
                            Thread.sleep(50);
                            
                            // 向左移动2像素（回到中心再向左2像素）
                            page.mouse().move(centerX - 2, centerY);
                            Thread.sleep(50);
                            
                            // 回到中心位置
                            page.mouse().move(centerX, centerY);
                            Thread.sleep(50);
                            
                            log.debug("完成鼠标微调，准备点击按钮");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("用户取消猎聘任务");
                    } catch (LiepinTaskPausedException | CancellationException stopped) {
                        throw stopped;
                    } catch (Exception moveError) {
                        log.warn("鼠标微调失败，直接点击按钮: {}", moveError.getMessage());
                    }
                    
                    pauseBeforeAction(page, "发起沟通");
                    if (!cardJobId.equals(extractJobIdFromCard(currentJobCard))) {
                        log.info("猎聘岗位卡片已变化，取消本次沟通，不更新投递状态");
                        continue;
                    }
                    requireActionAllowed(page);
                    if (!reserveCommunicationOrPause(cardJobId)) {
                        log.info("猎聘岗位未取得沟通预留：已处理、待确认或记录不存在，不点击 | 岗位ID：{}", cardJobId);
                        continue;
                    }
                    reserved = true;
                    apiEntity.setDelivered(2);
                    requireActionAllowed(page);
                    if (!cardJobId.equals(extractJobIdFromCard(currentJobCard))
                            || !button.isVisible() || !"聊一聊".equals(button.textContent().strip())) {
                        throw LiepinTaskPausedException.communicationUnconfirmed(cardJobId, "点击沟通", null);
                    }
                    button.click();
                    requireActionAllowed(page);
                    info("猎聘岗位 " + cardJobId + " 已执行一次沟通点击，正在确认；不自动重发");
                    communicationObserver.awaitEstablished(page, cardJobId, () -> requireActionAllowed(page));
                    confirmCommunicationOrPause(cardJobId);
                    apiEntity.setDelivered(1);
                    resultList.add(sb.append("【").append(companyName).append(" ").append(jobName).append(" ")
                            .append(salary).append(" ").append(recruiterName).append("】").toString());
                    sb.setLength(0);
                    log.info("猎聘已确认同岗位建立沟通；不代表招呼或简历送达 | 岗位ID：{}", cardJobId);
                    closeChatAfterConfirmation(cardJobId);
                    
                } catch (LiepinTaskPausedException | CancellationException stopped) {
                    throw stopped;
                } catch (Exception e) {
                    if (reserved) {
                        // A click timeout can happen after the platform side effect: never retry it.
                        throw LiepinTaskPausedException.communicationUnconfirmed(cardJobId, "点击沟通",
                                e instanceof RuntimeException runtime ? runtime : null);
                    }
                    log.warn("猎聘沟通点击前处理失败，未发起沟通 | 岗位ID：{} | 异常类型：{}",
                            cardJobId, e.getClass().getSimpleName());
                }
            } else {
                requireActionAllowed(page);
                if (!cardJobId.equals(extractJobIdFromCard(currentJobCard))) continue;
                // Reconcile explicit existing contact without clicking or counting it as a new action.
                if (button != null && "继续聊".equals(buttonText.strip()) && jobIdForUpdate != null) {
                    communicationObserver.awaitEstablished(page, cardJobId, () -> requireActionAllowed(page));
                    if (reserveCommunicationOrPause(cardJobId)) {
                        confirmCommunicationOrPause(cardJobId);
                        apiEntity.setDelivered(1);
                    }
                }
                if (button != null) {
                    log.debug("跳过岗位（按钮文本不匹配）: 【{}】的【{}·{}】岗位，按钮文本: '{}'", companyName, jobName, salary, buttonText);
                } else {
                    // 不再保存页面源码
                }
            }
        }
    }

    private boolean reserveCommunicationOrPause(Long jobId) {
        try {
            return liepinService.reserveCommunication(jobId);
        } catch (LiepinTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            throw LiepinTaskPausedException.recordStateUnavailable(jobId, "预留记录", failure);
        }
    }

    private void confirmCommunicationOrPause(Long jobId) {
        requireActionAllowed(page);
        try {
            if (!liepinService.confirmCommunication(jobId)) {
                throw LiepinTaskPausedException.recordStateUnavailable(jobId, "确认记录", null);
            }
        } catch (LiepinTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            throw LiepinTaskPausedException.recordStateUnavailable(jobId, "确认记录", failure);
        }
    }

    /** Cleanup cannot turn an unconfirmed click into success, nor cause another send. */
    private void closeChatAfterConfirmation(Long jobId) {
        String stage = "等待聊天界面";
        try {
            page.waitForSelector(CHAT_HEADER, new Page.WaitForSelectorOptions().setTimeout(3000));
            requireActionAllowed(page);
            stage = "关闭聊天窗口";
            Locator close = page.locator(CHAT_CLOSE);
            if (close.count() > 0) {
                PlaywrightUtil.sleep(1);
                requireActionAllowed(page);
                close.click(new Locator.ClickOptions().setTimeout(2000));
            }
        } catch (LiepinTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            requireActionAllowed(page);
            log.warn("猎聘已确认沟通后的清理失败，不重复发送 | 岗位ID：{} | 阶段：{} | 异常类型：{}",
                    jobId, stage, failure.getClass().getSimpleName());
        }
    }

    // 从岗位卡片的 data 属性中提取 jobId（兼容 lastApiEntities 缺失场景）
    private Long extractJobIdFromCard(Locator card) {
        try {
            // 新版页面：jobId 在"聊一聊"容器的 data-tlg-ext 里（%7B%22jobId%22%3A%22...）
            Locator chatBox = card.locator("[data-tlg-elem-id='c_pc_search_job_listcard_chat_btn']");
            if (chatBox.count() > 0) {
                String chatExt = chatBox.first().getAttribute("data-tlg-ext");
                if (chatExt != null && !chatExt.isEmpty()) {
                    String decoded = java.net.URLDecoder.decode(chatExt, java.nio.charset.StandardCharsets.UTF_8);
                    String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded).path("jobId").asText(null);
                    if (id != null && !id.isEmpty()) {
                        return Long.parseLong(id);
                    }
                }
            }
        } catch (LiepinTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (Exception ignore) {}
        try {
            String ext = card.getAttribute("data-tlg-ext");
            if (ext != null && !ext.isEmpty()) {
                try {
                    String decoded = java.net.URLDecoder.decode(ext, java.nio.charset.StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded);
                    String jobIdStr = node.path("jobId").asText(null);
                    if (jobIdStr != null && !jobIdStr.isEmpty()) {
                        return Long.parseLong(jobIdStr);
                    }
                } catch (Exception ignore) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\\\"jobId\\\\\":\\\\\"(\\d+)\\\\\"").matcher(ext);
                    if (m.find()) {
                        return Long.parseLong(m.group(1));
                    }
                }
            }
            String scm = card.getAttribute("data-tlg-scm");
            if (scm != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("jobId=(\\d+)").matcher(scm);
                if (m.find()) {
                    return Long.parseLong(m.group(1));
                }
            }
        } catch (LiepinTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (Exception ignore) {}
        try {
            Locator jobLinks = card.locator("a[href*='/job/']");
            if (jobLinks.count() > 0) {
                String href = jobLinks.first().getAttribute("href");
                if (href != null) {
                    java.util.regex.Matcher id = java.util.regex.Pattern
                            .compile("/job/(\\d+)(?:\\.shtml|\\.html|[/?#]|$)").matcher(href);
                    if (id.find()) return Long.parseLong(id.group(1));
                }
            }
        } catch (LiepinTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (Exception ignore) {}
        return null;
    }
}
