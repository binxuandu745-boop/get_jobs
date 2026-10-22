

/*
   现在还未解决的问题是1.鼠标移动十分脚本化，
                   2.每次都截图也是十分的脚本化，需要用随机数来随机从浏览器获取或是截图取得信息。
                   3，
 */

package com.getjobs.worker.boss;


import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.screenshot.BossScreenshotSettingsService;
import com.getjobs.application.screenshot.ScreenshotAnalysisResult;
import com.getjobs.application.screenshot.ScreenshotFields;
import com.getjobs.application.screenshot.ScreenshotRules;
import com.getjobs.application.screenshot.ScreenshotVisionException;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href=
 * "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * Boss直聘自动投递
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Boss {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final AiService aiService;
    @Autowired
    private BossScreenshotScreening screenshotScreening;
    private BossScreenshotSettingsService.Settings screenshotSettings =
            new BossScreenshotSettingsService.Settings(false, ScreenshotRules.defaults());
    @Value("${getjobs.boss.minimum-monthly-salary:0}")
    private int minimumMonthlySalary;
    @Value("${getjobs.boss.minimum-daily-salary:0}")
    private int minimumDailySalary;
    @Value("${getjobs.boss.max-preview-jobs:5}")
    private int maxPreviewJobs;
    @Value("${getjobs.boss.required-role-terms:}")
    private List<String> requiredRoleTerms;
    @Value("${getjobs.boss.require-internship:false}")
    private boolean requireInternship;
    @Value("${getjobs.boss.action-delay-min-ms:3000}")
    private int actionDelayMinMs = 3000;
    @Value("${getjobs.boss.action-delay-max-ms:5000}")
    private int actionDelayMaxMs = 5000;
    @Autowired
    private BossMouseMovement mouseMovement;
    @Value("${getjobs.boss.mouse-movement-enabled:true}")
    private boolean mouseMovementEnabled;
    @Value("${getjobs.boss.mouse-movement-min-ms:450}")
    private int mouseMovementMinMs = 450;
    @Value("${getjobs.boss.mouse-movement-max-ms:1100}")
    private int mouseMovementMaxMs = 1100;
    @Value("${getjobs.boss.detail-response-timeout-ms:60000}")
    private int detailResponseTimeoutMs = 60_000;
    private int previewInspectedCount;
    private int previewMatchedCount;
    /** Cards skipped because neither the card nor its detail carried a usable job identity. */
    private int unidentifiedCardSkips;
    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    // 记录 encryptId -> encryptUserId 的映射，用于后续更新投递状态
    private final ConcurrentMap<String, String> encryptIdToUserId = new ConcurrentHashMap<>();
    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();
    private final BossTaskVisitLedger taskVisits = new BossTaskVisitLedger();
    private BossJobDetailSession detailSession;
    private String expectedSearchUrl;

    /** Boss 首页，UI 搜索的入口 */
    private static final String BOSS_HOME_URL = "https://www.zhipin.com";
    /**
     * true=先回首页、在搜索框里做一次真实 UI 搜索再进列表页；false=直接拼 URL 跳转。
     * <p>
     * 默认关掉。实测 Boss 首页在受控标签页里会一直转圈加载不完，navigate 要 60 秒才返回，
     * 期间 evaluate / locator 全部无限期阻塞（这两个 API 没有默认超时）—— 启动慢、投递卡死、
     * 管理页面按钮没反应都出在这。而岗位列表页在同一个浏览器里是秒开的。
     * 想重新试 UI 搜索路径时再打开。
     */
    private static final boolean USE_UI_SEARCH = false;
    /** 等待页面加载状态的超时（毫秒），绝不能不设 —— 见 waitForPageSettled 的说明 */
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入 bossService 与 aiService

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 从数据库加载黑名单
        this.blackCompanies = bossService.getBlackCompanies();
        this.blackRecruiters = bossService.getBlackRecruiters();
        this.blackJobs = bossService.getBlackJobs();

        log.info("黑名单加载完成: 公司({}) 招聘者({}) 职位({})",
                blackCompanies != null ? blackCompanies.size() : 0,
                blackRecruiters != null ? blackRecruiters.size() : 0,
                blackJobs != null ? blackJobs.size() : 0);
        // 不在页面初始化阶段入库，仅用于后续点击卡片时按需入库
    }

    /**
     * 执行投递
     */
    public int execute() {
        checkStopRequested();
        // Freeze the rules for this run. Preview remains local and never calls the vision API.
        if (!Boolean.TRUE.equals(config.getDebugger()) && screenshotScreening != null) {
            try {
                screenshotSettings = screenshotScreening.beginTask();
            } catch (RuntimeException failure) {
                throw BossTaskPausedException.detailsUnavailable(
                        "自动截图配置不可用，请检查 Boss 自动截图规则及截图识别专用 API 配置");
            }
            if (progressCallback != null) progressCallback.accept(screenshotSettings.enabled()
                    ? "自动截图已启用：岗位字段由截图提取，Java筛选通过后才沟通"
                    : "自动截图已关闭：使用原岗位详情数据筛选", null, null);
        }
        taskVisits.reset();
        unidentifiedCardSkips = 0;
        for (String cityCode : config.getCityCode()) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
            postJobByCity(cityCode);
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
        }
        return resultList.size();
    }

    /**
     * 获取结果列表
     */
    public List<Job> getResultList() {
        return new ArrayList<>(resultList);
    }

    public int getPreviewInspectedCount() { return previewInspectedCount; }

    public int getPreviewMatchedCount() { return previewMatchedCount; }

    /**
     * 更新黑名单（从聊天记录中）
     */
    public Map<String, Set<String>> updateBlacklistFromChats() {
        page.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        int newBlacklistCount = 0;
        boolean shouldBreak = false;
        while (!shouldBreak) {
            try {
                Locator bottomLocator = page.locator(FINISHED_TEXT);
                if (bottomLocator.count() > 0 && "没有更多了".equals(bottomLocator.textContent())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }

            Locator items = page.locator(CHAT_LIST_ITEM);
            int itemCount = items.count();

            for (int i = 0; i < itemCount; i++) {
                try {
                    Locator companyElements = page.locator(COMPANY_NAME_IN_CHAT);
                    Locator messageElements = page.locator(LAST_MESSAGE);

                    if (i >= companyElements.count() || i >= messageElements.count()) {
                        break;
                    }

                    String companyName = null;
                    String message = null;
                    int retryCount = 0;

                    while (true) {
                        try {
                            companyName = companyElements.nth(i).textContent();
                            message = messageElements.nth(i).textContent();
                            break;
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount >= 2) {
                                log.info("尝试获取元素文本2次失败，放弃本次获取");
                                break;
                            }
                            log.info("页面元素已变更，正在重试第{}次获取元素文本...", retryCount);
                            PlaywrightUtil.sleep(1);
                        }
                    }

                    if (companyName != null && message != null) {
                        boolean match = message.contains("不") || message.contains("感谢") || message.contains("但")
                                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                        boolean nomatch = message.contains("不是") || message.contains("不生");
                        if (match && !nomatch) {
                            if (blackCompanies.stream().anyMatch(companyName::contains)) {
                                continue;
                            }
                            companyName = companyName.replaceAll("\\.{3}", "");
                            if (companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                                blackCompanies.add(companyName);
                                // 保存到数据库
                                bossService.addBlacklist("company", companyName);
                                newBlacklistCount++;
                                log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("寻找黑名单公司异常...", e);
                }
            }

            try {
                Locator scrollElement = page.locator(SCROLL_LOAD_MORE);
                if (scrollElement.count() > 0) {
                    scrollElement.scrollIntoViewIfNeeded();
                } else {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
                }
            } catch (Exception e) {
                log.error("滚动元素出错", e);
                break;
            }
        }
        log.info("黑名单公司数量：{}，本次新增：{}", (blackCompanies != null ? blackCompanies.size() : 0), newBlacklistCount);

        Map<String, Set<String>> result = new HashMap<>();
        result.put("blackCompanies", new HashSet<>(blackCompanies != null ? blackCompanies : Collections.emptySet()));
        result.put("blackRecruiters", new HashSet<>(blackRecruiters != null ? blackRecruiters : Collections.emptySet()));
        result.put("blackJobs", new HashSet<>(blackJobs != null ? blackJobs : Collections.emptySet()));
        return result;
    }

    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()) {
            // 检查是否需要停止
            if (shouldStopCallback.get()) {
                progressCallback.accept("用户取消投递", 0, 0);
                return;
            }

            int postCount = 0;
            // 使用 URLEncoder 对关键词进行编码
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            expectedSearchUrl = url;
            // 单个关键词失败不该让整个投递任务中止，下面整段都包在 try 里
            try (BossJobDetailSession session = new BossJobDetailSession(page,
                    screenshotSettings.enabled() && !Boolean.TRUE.equals(config.getDebugger()))) {
            detailSession = session;
            checkStopRequested();
            BossPageSafety.requireUsablePage(page);
            // 进列表页 + 等列表渲染，整段带重试
            if (!openJobListWithRetry(keyword, url, cityCode)) {
                log.info("【{}】网站明确返回空结果，不再等待或重试", keyword);
                progressCallback.accept("没有找到相关职位：" + keyword, 0, 0);
                continue;
            }
            session.bindSearchContext(url);

            // 只处理当前已加载的列表；耗尽后才请求下一批，不提前滚完整个列表。
            int loadedCount = countJobCards();
            log.info("【{}】当前已加载岗位数:{}（预览仅检查首屏）", keyword, loadedCount);
            progressCallback.accept("岗位加载完成：" + keyword, 0, loadedCount);
            int count = loadedCount;
            for (int i = 0; ; i++) {
                // 检查是否需要停止
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", i, count);
                    return;
                }
                checkStopRequested();
                BossPageSafety.requireUsablePage(page);
                session.requireSearchContextUnchanged();

                if (Boolean.TRUE.equals(config.getDebugger())
                        && i >= Math.min(loadedCount, Math.max(1, maxPreviewJobs))) break;
                count = countJobCards();
                if (i >= count) {
                    if (!loadNextJobBatch(keyword, count)) break;
                    count = countJobCards();
                }

                Locator cardToClick = page.locator(JOB_LIST_SELECTOR).nth(i);
                String expectedJobId = cardJobId(cardToClick);
                if (!taskVisits.claim(expectedJobId)) {
                    log.info("【{}】跳过本次任务已查看的岗位（卡片序号：{}）", keyword, i + 1);
                    continue;
                }

                // 所有岗位都在点击前等待，包括上一岗位被过滤后走 continue 的情况。
                pauseBeforeAction(page, "打开下一个岗位");

                if (Boolean.TRUE.equals(config.getDebugger())) previewInspectedCount++;
                int responseTimeout = detailResponseTimeoutMs >= 1000 && detailResponseTimeoutMs <= 120_000
                        ? detailResponseTimeoutMs : 60_000;
                BossJobDetailReader.Detail detail;
                if (expectedJobId == null) {
                    // 无法识别卡片ID时不猜测、不把未知岗位合并；仍只点击当前卡片一次。
                    // Finish hovering before observing the click response: another card's hover
                    // prefetch must not be mistaken for this unidentified card's selected detail.
                    prepareMouseClick(page, cardToClick, () -> { });
                    Runnable selectUnknownJob = () -> finishMouseClick(page, cardToClick, () -> { });
                    detail = screenshotSettings.enabled() && !Boolean.TRUE.equals(config.getDebugger())
                            ? BossJobDetailAwaiter.await(page, selectUnknownJob,
                                    this::checkStopRequested, responseTimeout, true)
                            : BossJobDetailAwaiter.await(page, selectUnknownJob,
                                    this::checkStopRequested, responseTimeout);
                    String returnedId = detail.root().getJSONObject("zpData")
                            .getJSONObject("jobInfo").optString("encryptId", null);
                    returnedId = BossTaskVisitLedger.identityOf(returnedId);
                    if (returnedId == null) {
                        // A short or promoted card can have no detail link and no usable identity.
                        // That is a data condition, not a challenge: skip this card and keep the run.
                        skipCardWithoutIdentity(keyword, i, detail.root());
                        continue;
                    }
                    if (!Boolean.TRUE.equals(config.getDebugger())) {
                        // Legacy cards without a stable href must still match the visible detail
                        // before any path can reach communication or sending.
                        awaitSelectedJob(returnedId, responseTimeout);
                    }
                    if (!taskVisits.claim(returnedId)) {
                        log.info("【{}】详情确认属于本次已查看岗位，跳过后续处理", keyword);
                        continue;
                    }
                } else {
                    detail = session.await(expectedJobId, () -> {
                        // 第一张可能已由网页默认选中，复用其自然返回的详情，不切去第二张。
                        if (!expectedJobId.equals(selectedJobId())) clickWithMouse(page, cardToClick);
                    }, this::checkStopRequested, responseTimeout);
                    awaitSelectedJob(expectedJobId, responseTimeout);
                }

                // 统一从请求返回的 JSON 中获取数据并做过滤
                String jobName = null;
                String jobSalary = null;
                java.util.List<String> tags = new java.util.ArrayList<>();
                String jobDesc = null;
                String bossName = null;
                String bossActive = null;
                String bossCompany = null;
                String bossJobTitle = null;

                // 从已校验的 JSON 构建用于投递与过滤的字段
                JSONObject zpData = detail.root().getJSONObject("zpData");
                JSONObject jobInfo = zpData.getJSONObject("jobInfo");
                JSONObject brand = zpData.optJSONObject("brandComInfo");
                JSONObject recruiter = zpData.optJSONObject("bossInfo");
                jobName = jobInfo.optString("jobName", "");
                jobSalary = jobInfo.optString("salaryDesc", "");
                String city = jobInfo.optString("locationName", "");
                String exp = jobInfo.optString("experienceName", "");
                String deg = jobInfo.optString("degreeName", "");
                if (!city.isEmpty()) tags.add(city);
                if (!exp.isEmpty()) tags.add(exp);
                if (!deg.isEmpty()) tags.add(deg);
                jobDesc = jobInfo.optString("postDescription", "");
                if (recruiter != null) {
                    bossName = recruiter.optString("name", "");
                    bossActive = recruiter.optString("activeTimeDesc", "");
                    bossJobTitle = recruiter.optString("title", "");
                }
                if (brand != null) {
                    bossCompany = brand.optString("brandName", "");
                }

                // Never persist an invalid/blocked response as a filtered job.
                checkStopRequested();
                BossPageSafety.requireUsablePage(page);
                session.requireSearchContextUnchanged();
                appendRawJson(detail.body());
                if (screenshotSettings.enabled() && !Boolean.TRUE.equals(config.getDebugger())) {
                    if (processScreenshotJob(keyword, detail)) postCount++;
                    continue;
                }
                processJobDetailJsonAndInsert(detail.body());
                if (bossCompany == null || bossCompany.isBlank()) {
                    log.warn("岗位公司信息缺失，不能据此确认风控 | 岗位：{}", jobName);
                }

                // 过滤（全部基于 JSON 字段），并输出过滤原因
                if (!JobTargetFilter.matches(jobName, jobDesc, requiredRoleTerms, requireInternship)) {
                    log.info("被过滤：不符合目标岗位方向或实习配置 | 公司：{} | 岗位：{}", bossCompany, jobName);
                    continue;
                }
                if (!SalaryThreshold.matches(jobSalary, minimumMonthlySalary, minimumDailySalary)) {
                    log.info("被过滤：薪资下限不符合要求或单位不明 | 公司：{} | 岗位：{} | 薪资：{}",
                            bossCompany, jobName, jobSalary);
                    continue;
                }
                if (jobName != null && blackJobs != null && blackJobs.stream().anyMatch(jobName::contains)) {
                    String term = findMatchedTerm(blackJobs, jobName);
                    log.info("被过滤：职位黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName, term != null ? term : "");
                    continue;
                }
                // HR活跃状态过滤：当开启过滤开关且活跃描述包含“年”时，视为不活跃
                boolean hrInactiveByYear = bossActive != null && bossActive.contains("年");
                if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hrInactiveByYear) {
                    log.info("被过滤：HR活跃状态包含‘年’ | 公司：{} | 岗位：{} | 活跃：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossActive);
                    continue;
                }
                if (bossCompany != null && blackCompanies != null && blackCompanies.stream().anyMatch(bossCompany::contains)) {
                    String term = findMatchedTerm(blackCompanies, bossCompany);
                    log.info("被过滤：公司黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany, jobName != null ? jobName : "", term != null ? term : "");
                    continue;
                }
                if (bossJobTitle != null && blackRecruiters != null && blackRecruiters.stream().anyMatch(bossJobTitle::contains)) {
                    String term = findMatchedTerm(blackRecruiters, bossJobTitle);
                    log.info("被过滤：招聘者黑名单命中 | 公司：{} | 岗位：{} | 招聘者：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossJobTitle, term != null ? term : "");
                    continue;
                }

                // The response is already parsed locally. Rejected jobs need no processing delay.
                // Keep the next job's pre-click delay and all safety/cancellation checks unchanged.
                pauseBeforeAction(page, "筛选通过，继续处理岗位");

                // 创建Job对象（全部基于 JSON 字段）
                Job job = new Job();
                job.setJobName(jobName != null ? jobName : "");
                job.setSalary(jobSalary != null ? jobSalary : "");
                job.setJobArea(String.join(", ", tags));
                job.setCompanyName(bossCompany != null ? bossCompany : "");
                job.setRecruiter(bossName != null ? bossName : "");
                job.setJobInfo(jobDesc != null ? jobDesc : "");

                // 输出
                if (Boolean.TRUE.equals(config.getDebugger())) previewMatchedCount++;
                progressCallback.accept((Boolean.TRUE.equals(config.getDebugger()) ? "正在预览：" : "正在投递：") + jobName, i + 1, count);
                resumeSubmission(keyword, job);
                postCount++;

                // 下一岗位的等待统一位于循环顶部，过滤分支也会经过。
                BossPageSafety.requireUsablePage(page);
            }
            log.info("【{}】{}处理完成，符合筛选岗位数量:{}", keyword,
                    Boolean.TRUE.equals(config.getDebugger()) ? "预览" : "投递", postCount);
            } catch (CancellationException e) {
                progressCallback.accept("用户取消任务", 0, 0);
                return;
            } catch (BossTaskPausedException e) {
                throw e;
            } catch (Exception e) {
                log.error("【{}】处理失败，跳过该关键词继续下一个：{}", keyword, e.getMessage(), e);
                if (progressCallback != null) {
                    progressCallback.accept("关键词[" + keyword + "]失败已跳过：" + e.getMessage(), 0, 0);
                }
            } finally {
                detailSession = null;
                expectedSearchUrl = null;
            }
        }
    }

    private String cardJobId(Locator card) {
        Locator links = card.locator("a[href*='/job_detail/']");
        if (links.count() == 0) return null;
        return BossTaskVisitLedger.identityOf(links.first().getAttribute("href"));
    }

    /**
     * A card whose detail response carries no usable identity is skipped, never guessed and never
     * recorded: no detail processing, no communication. This is a page data condition (short or
     * promoted cards), so it must not stop the whole task; real challenges stay with the page checks.
     */
    private void skipCardWithoutIdentity(String keyword, int cardIndex, JSONObject detailRoot) {
        String reason = identityProblem(detailRoot);
        unidentifiedCardSkips++;
        log.info("Boss 跳过岗位 [岗位标识缺失]：{}（关键词 {}，卡片序号 {}，本任务第 {} 个）；未记录、未沟通",
                reason, keyword, cardIndex + 1, unidentifiedCardSkips);
        if (progressCallback != null) {
            progressCallback.accept("跳过岗位标识缺失的卡片：" + reason, null, null);
        }
        if (unidentifiedCardSkips == 3) {
            log.warn("本任务已跳过 3 个缺少岗位标识的卡片，可能是页面结构变化；任务继续，风控判断仍由页面检查负责");
        }
    }

    /** Names the missing identifier without echoing any value from the page or the response. */
    private static String identityProblem(JSONObject detailRoot) {
        JSONObject data = detailRoot == null ? null : detailRoot.optJSONObject("zpData");
        JSONObject job = data == null ? null : data.optJSONObject("jobInfo");
        if (job == null) return "详情响应缺少岗位信息";
        Object raw = job.opt("encryptId");
        if (raw == null) return "详情响应的岗位标识为空";
        if (!(raw instanceof String value)) return "详情响应的岗位标识类型异常";
        if (value.isBlank()) return "详情响应的岗位标识为空";
        return "详情响应的岗位标识格式无法核对（长度 " + value.length() + "）";
    }

    private String selectedJobId() {
        Locator more = page.locator("a.more-job-btn");
        if (more.count() == 0) return null;
        return BossTaskVisitLedger.identityOf(more.first().getAttribute("href"));
    }

    private void awaitSelectedJob(String expectedJobId, int timeoutMs) {
        try {
            page.waitForCondition(() -> {
                checkStopRequested();
                BossPageSafety.requireUsablePage(page);
                return expectedJobId.equals(selectedJobId());
            }, new Page.WaitForConditionOptions().setTimeout(timeoutMs));
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (com.microsoft.playwright.TimeoutError timeout) {
            checkStopRequested();
            BossPageSafety.requireUsablePage(page);
            throw BossTaskPausedException.detailsUnavailable("岗位详情已收到，但页面尚未确认切换到目标岗位");
        }
    }

    /** Load one additional batch only after every currently loaded card was considered. */
    boolean loadNextJobBatch(String keyword, int previousCount) {
        requireActionAllowed(page);
        // A late final batch may arrive between the loop's count and the end marker.
        int loadedCount = countJobCards();
        requireActionAllowed(page);
        if (loadedCount > previousCount) return true;
        boolean finished = listExplicitlyFinished();
        requireActionAllowed(page);
        if (finished) return false;
        pauseBeforeAction(page, "加载下一批岗位");
        // Scroll once. During the wait only inspect existing DOM; never repeatedly scroll/reload.
        page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
        int timeout = detailResponseTimeoutMs >= 1000 && detailResponseTimeoutMs <= 120_000
                ? detailResponseTimeoutMs : 60_000;
        try {
            page.waitForCondition(() -> {
                requireActionAllowed(page);
                boolean ready = countJobCards() > previousCount || listExplicitlyFinished();
                requireActionAllowed(page);
                return ready;
            }, new Page.WaitForConditionOptions().setTimeout(timeout));
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (com.microsoft.playwright.TimeoutError timedOut) {
            requireActionAllowed(page);
            throw BossTaskPausedException.detailsUnavailable("下一批岗位加载超时（" + timeout
                    + " ms），当前批次已处理完；未反复滚动或刷新");
        }
        requireActionAllowed(page);
        int currentCount = countJobCards();
        requireActionAllowed(page);
        if (currentCount > previousCount) {
            log.info("【{}】新增加载 {} 个岗位，继续按顺序处理", keyword, currentCount - previousCount);
            return true;
        }
        return false;
    }

    private boolean listExplicitlyFinished() {
        return page.locator(":text-is('没有更多了'):visible, :text-is('没有更多职位了'):visible, "
                + ":text-is('没有更多岗位了'):visible, :text-is('已经到底了'):visible").count() > 0;
    }

    /**
     * 解析岗位详情 JSON 并进行入库与黑名单处理（只在点击卡片时调用）。
     */
    private void processJobDetailJsonAndInsert(String body) {
        processJobDetailJsonAndInsert(body, null, null);
    }

    private void processJobDetailJsonAndInsert(String body, ScreenshotFields visual, Boolean visualMatched) {
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) return;

            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");
            if (jobInfo == null) return;

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    // 进一步兼容可能的字段命名
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            if (encryptId != null && encryptUserId != null) {
                encryptIdToUserId.put(encryptId, encryptUserId);
            }

            com.getjobs.application.entity.BossJobDataEntity entity = new com.getjobs.application.entity.BossJobDataEntity();
            entity.setJobName(jobInfo.optString("jobName", null));
            entity.setSalary(jobInfo.optString("salaryDesc", null));
            entity.setLocation(jobInfo.optString("locationName", null));
            entity.setExperience(jobInfo.optString("experienceName", null));
            entity.setDegree(jobInfo.optString("degreeName", null));
            entity.setJobDescription(jobInfo.optString("postDescription", null));
            entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
            entity.setCompanyAddress(jobInfo.optString("address", null));
            entity.setEncryptId(encryptId);
            entity.setEncryptUserId(encryptUserId);

            entity.setCompanyName(brand != null ? brand.optString("brandName", null) : null);
            entity.setIndustry(brand != null ? brand.optString("industryName", null) : null);
            entity.setIntroduce(brand != null ? brand.optString("introduce", null) : null);
            entity.setFinancingStage(brand != null ? brand.optString("stageName", null) : null);
            entity.setCompanyScale(brand != null ? brand.optString("scaleName", null) : null);

            if (visualMatched != null) {
                // Do not quietly fill missing visual observations with the old JSON job fields.
                entity.setJobName(visual == null ? null : visual.jobTitle());
                entity.setSalary(visual == null ? null : visual.salaryText());
                entity.setLocation(visual == null ? null : visual.city());
                entity.setExperience(null);
                entity.setDegree(visual == null ? null : visual.education());
                entity.setJobDescription(visual == null ? null : visual.description());
                entity.setCompanyName(visual == null ? null : visual.company());
            }

            entity.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            entity.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            entity.setHrActiveStatus(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);

            if (encryptId != null && !encryptId.isEmpty()) {
                entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }

            // 黑名单处理
            boolean filtered = visualMatched != null ? !visualMatched : !JobTargetFilter.matches(entity.getJobName(), entity.getJobDescription(),
                    requiredRoleTerms, requireInternship)
                    || !SalaryThreshold.matches(entity.getSalary(), minimumMonthlySalary, minimumDailySalary);
            String companyName = entity.getCompanyName() != null ? entity.getCompanyName() : "";
            String positionName = entity.getJobName() != null ? entity.getJobName() : "";
            String hrPosition = entity.getHrPosition() != null ? entity.getHrPosition() : "";
            try {
                if (blackCompanies != null && blackCompanies.stream().anyMatch(companyName::contains)) filtered = true;
                if (!filtered && blackJobs != null && blackJobs.stream().anyMatch(positionName::contains)) filtered = true;
                if (!filtered && blackRecruiters != null && blackRecruiters.stream().anyMatch(hrPosition::contains)) filtered = true;
            } catch (Throwable ignore) {}

            // HR活跃状态过滤：开启过滤且活跃描述包含“年”，则标记为已过滤，但仍入库
            if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
                String hrActive = entity.getHrActiveStatus();
                if (hrActive != null && hrActive.contains("年")) {
                    filtered = true;
                }
            }

            entity.setDeliveryStatus(filtered ? "已过滤" : "未投递");

            if (visualMatched != null) {
                bossService.recordScreenshotObservation(entity);
                return;
            }

            // 入库（若不存在），优先以 encrypt_id + encrypt_user_id 去重；若 userId 缺失，则以 encrypt_id 去重
            if (encryptId != null) {
                try {
                    boolean exists = false;
                    if (encryptUserId != null) {
        exists = bossService.existsBossJob(encryptId, encryptUserId);
                    } else {
        exists = bossService.existsBossJobByEncryptId(encryptId);
                    }
                    if (!exists) {
        bossService.insertBossJob(entity);
                        log.debug("岗位入库：{} | 公司：{} | HR：{} | 状态：{}", entity.getJobName(), entity.getCompanyName(), entity.getHrName(), entity.getDeliveryStatus());
                    } else if (filtered) {
                        bossService.markPendingJobFiltered(encryptId, encryptUserId);
                    }
                } catch (Exception e) {
                    log.warn("岗位入库失败：{}", e.getMessage());
                }
            }
        } catch (Throwable e) {
            if (visualMatched != null) {
                throw BossTaskPausedException.detailsUnavailable("截图岗位信息保存失败，未继续沟通");
            }
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
        }
    }

    public String decodeSalary(String text) {
        Map<Character, Character> fontMap = new HashMap<>();
        fontMap.put('\uE8F0', '0');
        fontMap.put('\uE8F1', '1');
        fontMap.put('\uE8F2', '2');
        fontMap.put('\uE8F3', '3');
        fontMap.put('\uE8F4', '4');
        fontMap.put('\uE8F5', '5');
        fontMap.put('\uE8F6', '6');
        fontMap.put('\uE8F7', '7');
        fontMap.put('\uE8F8', '8');
        fontMap.put('\uE8F9', '9');
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            result.append(fontMap.getOrDefault(c, c));
        }
        return result.toString();
    }

    // 安全获取单个文本内容
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() > 0 && node.innerText() != null) {
                return node.innerText().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    // 安全获取多个文本内容
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Boss姓名+活跃状态拆分
    public String[] splitBossName(String raw) {
        String[] bossParts = raw.trim().split("\\s+");
        String bossName = bossParts[0];
        String bossActive = bossParts.length > 1 ? String.join(" ", Arrays.copyOfRange(bossParts, 1, bossParts.length)) : "";
        return new String[]{bossName, bossActive};
    }

    // Boss公司+职位拆分
    public String[] splitBossTitle(String raw) {
        String[] parts = raw.trim().split(" · ");
        String company = parts[0];
        String job = parts.length > 1 ? parts[1] : "";
        return new String[]{company, job};
    }

    // 匹配命中词条（用于日志输出过滤原因）
    private String findMatchedTerm(java.util.Collection<String> patterns, String text) {
        if (patterns == null || text == null) return null;
        try {
            for (String p : patterns) {
                if (p != null && !p.isEmpty() && text.contains(p)) {
                    return p;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        addParam(params, JobUtils.appendParam("city", cityCode));
        // 职位方向（position）决定页面预选的岗位分类，例如 Java 为 100101
        addParam(params, JobUtils.appendParam("position", config.getPosition()));
        addParam(params, JobUtils.appendParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }

    /**
     * 进入岗位列表页。
     * <p>
     * 优先模拟真人路径：回首页 -> 在搜索框里逐字输入关键词 -> 点搜索按钮，
     * 而不是冷启动直接把拼好的列表页 URL 丢给浏览器。
     * <p>
     * UI 搜索只能带上关键词和首页当前城市，config 里的城市/薪资/经验等筛选项仍然只能靠 URL，
     * 所以落地之后如果条件对不上，会再做一次同标签页跳转 —— 此时已经是热会话 + 同源 referer，
     * 和冷启动直闯不是一回事。UI 搜索任何一步失败都会退回原来的直接跳转，不影响主流程。
     */
    private void openJobList(String keyword, String targetUrl, String cityCode) {
        boolean uiSearchDone = USE_UI_SEARCH && searchFromHomePage(keyword);
        if (!uiSearchDone) {
            navigateToJobList(targetUrl);
        } else if (needsUrlFilters(targetUrl, cityCode)) {
            log.info("【{}】UI搜索已落地，补充筛选条件跳转：{}", keyword, targetUrl);
            navigateToJobList(targetUrl);
        }
        warnIfSecurityCheck(keyword);
    }

    /**
     * 在 Boss 首页搜索框里做一次真实的 UI 搜索。
     *
     * @return 是否成功落到岗位列表页
     */
    private boolean searchFromHomePage(String keyword) {
        try {
            String current = page.url();
            if (current == null || !current.startsWith(BOSS_HOME_URL) || current.contains("/web/geek/")) {
                navigateTo(BOSS_HOME_URL);
            }
            // 首页落地后还会自己跳一次（/shanghai/?seoRefer=index 之类），
            // 不等它跳完就找搜索框，会卡在 "waiting for navigation to finish" 直到超时
            waitForPageSettled();

            Locator input = page.locator(HOME_SEARCH_INPUT).first();
            input.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
            clickWithMouse(page, input);
            input.fill("");
            // 逐字输入，模拟真人打字节奏；一次性 fill 在输入行为层面太干净了
            input.pressSequentially(keyword, new Locator.PressSequentiallyOptions().setDelay(140));
            PlaywrightUtil.sleep(1);

            Locator searchBtn = page.locator(HOME_SEARCH_BUTTON).first();
            if (searchBtn.count() > 0) {
                clickWithMouse(page, searchBtn);
            } else {
                input.press("Enter");
            }
            // 表单提交是当前标签页跳转；若 Boss 改成新开标签页，这里会超时并退回直接跳转
            page.waitForURL("**/web/geek/jobs**", new Page.WaitForURLOptions().setTimeout(15_000));
            log.info("【{}】已通过首页搜索框进入岗位列表：{}", keyword, page.url());
            return true;
        } catch (BossTaskPausedException | CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("【{}】首页UI搜索失败，退回直接跳转：{}", keyword, e.getMessage());
            return false;
        }
    }

    /**
     * UI 搜索落地后，判断还需不需要用 URL 把筛选条件补上。
     */
    private boolean needsUrlFilters(String targetUrl, String cityCode) {
        String landed = page.url();
        if (landed == null) {
            return true;
        }
        // 首页搜索用的是首页当前城市，不一定等于配置里的城市
        if (cityCode != null && !cityCode.isEmpty() && !landed.contains("city=" + cityCode)) {
            return true;
        }
        // 除 city/query 外还有别的筛选项（薪资、经验、学历……），UI 搜索带不上
        int queryStart = targetUrl.indexOf('?');
        if (queryStart < 0) {
            return false;
        }
        for (String param : targetUrl.substring(queryStart + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            String value = eq < 0 ? "" : param.substring(eq + 1);
            if (value.isEmpty() || "city".equals(name) || "query".equals(name)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void navigateToJobList(String url) {
        navigateTo(url);
    }

    /**
     * 导航并重试一次。
     * <p>
     * Boss 页面自己会做客户端跳转，撞上时 Playwright 报 net::ERR_ABORTED；
     * 这类失败重试一次基本就过了，不该让整个投递任务因此中止。
     */
    private void navigateTo(String url) {
        checkStopRequested();
        BossPageSafety.requireUsablePage(page);
        // Boss 的 SPA 在网络不佳或被限流时，15 秒常常不够，之前实测连着两次都超时
        Page.NavigateOptions options = new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(45_000);
        try {
            if (detailSession != null) detailSession.beginNavigation();
            page.navigate(url, options);
        } catch (Exception first) {
            checkStopRequested();
            BossPageSafety.requireUsablePage(page);
            // 导航期间页面自己又跳了一次时，Playwright 会抛 "Object doesn't exist: request@/frame@"，
            // 但页面其实已经到位了。先看落地 URL，别白白重试一遍。
            if (landedOn(url)) {
                log.debug("导航报了失效对象异常但页面已到位，忽略：{}", first.getMessage());
                settleAfterNavigation();
                return;
            }
            log.warn("导航失败，1秒后重试一次：{} | {}", url, first.getMessage());
            PlaywrightUtil.sleep(1);
            checkStopRequested();
            try {
                if (detailSession != null) detailSession.beginNavigation();
                page.navigate(url, options);
            } catch (Exception second) {
                BossPageSafety.requireUsablePage(page);
                if (!landedOn(url)) {
                    throw second;
                }
                log.debug("重试同样报失效对象异常但页面已到位，忽略：{}", second.getMessage());
            }
        }
        BossPageSafety.requireUsablePage(page);
        settleAfterNavigation();
    }

    /**
     * Search pages must match their effective filters; tracking-only rewrites are allowed.
     */
    private boolean landedOn(String targetUrl) {
        try {
            String current = page.url();
            if (current == null) {
                return false;
            }
            URI target = URI.create(targetUrl);
            if ("/web/geek/jobs".equals(target.getPath()) || "/web/geek/jobs/".equals(target.getPath())) {
                return BossSearchContext.fromUrl(targetUrl).matches(current);
            }
            URI actual = URI.create(current);
            return Objects.equals(target.getScheme(), actual.getScheme())
                    && Objects.equals(target.getHost(), actual.getHost())
                    && Objects.equals(target.getPath(), actual.getPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等页面真正稳定下来再继续。
     * <p>
     * DOMCONTENTLOADED 返回之后 Boss 还会自己做客户端跳转，此时 frame 被替换，
     * 后续任何 locator 调用都会报 "Object doesn't exist: frame@..."。
     */
    private void settleAfterNavigation() {
        waitForPageSettled();
    }

    /**
     * 等页面进入 domcontentloaded，最多等 {@value #LOAD_STATE_TIMEOUT} 毫秒。
     * <p>
     * 两个坑都踩过了：
     * 一是不能等 LOAD/NETWORKIDLE —— Boss 是 SPA + WebSocket 长连接，这两个状态可能永远不到；
     * 二是必须显式给超时 —— 不给超时就是无限期挂起，而"卡住"不是异常，
     * 外面包 try/catch 完全没用（实测把线程挂了两分钟以上还在等）。
     */
    private void waitForPageSettled() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception ignore) {
            // 等不到就算了，后面的 locator 调用自己有超时
        }
        PlaywrightUtil.sleep(2);
    }

    /**
     * 判断是不是 Playwright 的"对象已失效"异常。
     * <p>
     * 页面频繁增删 frame 时，Playwright Java 在派发事件时会引用到已经销毁的对象，
     * 抛 "Object doesn't exist: frame@..."。这个异常和当时在飞的那个调用没有因果关系，
     * 连接本身仍然可用，重试即可。
     */
    private static boolean isStaleObjectError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 两类都是"页面在动"导致的瞬时异常，跟调用本身没有因果关系，重试就好：
        // - Object doesn't exist: frame@/request@  事件派发时引用到已销毁的对象
        // - Execution context was destroyed         求值期间页面发生了导航
        return message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed");
    }

    /**
     * 进入岗位列表并等列表渲染出来，整段带重试。
     * Boss 首页/列表页在登录态下会连着跳好几次，一次失败很正常。
     */
    private boolean openJobListWithRetry(String keyword, String targetUrl, String cityCode) {
        // 只重试一次：每次都要走导航(最长45秒×2) + 等列表(15秒×3)，
        // 试三轮的话一个关键词失败要耗掉好几分钟，界面上看着就是"卡住不动"
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            checkStopRequested();
            BossPageSafety.requireUsablePage(page);
            try {
                openJobList(keyword, targetUrl, cityCode);
                return waitForJobList();
            } catch (CancellationException | BossTaskPausedException e) {
                throw e;
            } catch (RuntimeException e) {
                checkStopRequested();
                last = e;
                log.warn("【{}】进入岗位列表失败（第{}/2次）：{}", keyword, attempt,
                        e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0]);
                if (attempt < 2) {
                    PlaywrightUtil.sleep(3);
                }
            }
        }
        throw last;
    }

    /**
     * 统计当前列表里的岗位卡片数量，对失效对象异常做重试。
     */
    private int countJobCards() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            checkStopRequested();
            try {
                return page.locator(JOB_LIST_SELECTOR).count();
            } catch (Exception e) {
                if (!isStaleObjectError(e) || attempt == 3) {
                    if (isStaleObjectError(e)) {
                        log.warn("统计岗位数量始终失败，按 0 处理：{}", e.getMessage());
                        return 0;
                    }
                    throw e;
                }
                PlaywrightUtil.sleep(1);
            }
        }
        return 0;
    }

    /**
     * 等待岗位列表容器出现，带重试。
     * 页面在这期间可能还在跳转，一次失败不代表真的没有列表。
     */
    private boolean waitForJobList() {
        // Boss 改版频繁，推荐页和搜索结果页的容器类名不一样。
        // 用逗号把候选选择器拼成一个，让 Playwright 一次性等"任意一个先出现"，
        // 不要逐个 8 秒串行试 —— 那样一轮就要 48 秒，页面正常时也慢得像卡死。
        String containers = String.join(", ",
                "ul.rec-job-list",
                "ul.job-list-box",
                ".job-list-box",
                ".search-job-result",
                "li.job-card-box",
                "li.job-card-wrapper");

        for (int probe = 0; probe < 30; probe++) {
            // Once armed, a changed URL must pause rather than wait for it to change back.
            requireActionAllowed(page);
            if (expectedSearchUrl != null
                    && !BossSearchContext.fromUrl(expectedSearchUrl).matches(page.url())) {
                // A visible old list is not evidence that the new city/type filters have landed.
                page.waitForTimeout(500);
                continue;
            }
            if (expectedSearchUrl != null && detailSession != null) {
                // Arm before DOM probes: SDK calls can dispatch A -> B -> A navigation events.
                detailSession.bindSearchContext(expectedSearchUrl);
            }
            boolean empty = page.getByText("没有找到相关职位").isVisible();
            requireActionAllowed(page);
            if (empty) return false;
            boolean visible = page.locator(containers).first().isVisible();
            requireActionAllowed(page);
            if (visible) return true;
            page.waitForTimeout(500);
        }
        requireActionAllowed(page);
        if (expectedSearchUrl != null
                && !BossSearchContext.fromUrl(expectedSearchUrl).matches(page.url())) {
            throw BossTaskPausedException.detailsUnavailable(
                    "搜索页面尚未应用本轮城市、工作类型等条件，未继续处理旧列表");
        }
        dumpListPageStructure();
        throw new IllegalStateException("岗位列表始终未出现，当前页面: " + safeUrl());
    }

    private String safeUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return "(取不到URL)";
        }
    }

    /**
     * 列表容器一个都没命中时，把页面上的候选列表结构打出来，方便修选择器。
     */
    private void dumpListPageStructure() {
        try {
            Object info = page.evaluate("""
                    () => {
                      const uls = Array.from(document.querySelectorAll('ul,div'))
                        .filter(el => el.className && typeof el.className === 'string'
                                   && /job|list|rec/i.test(el.className))
                        .slice(0, 15)
                        .map(el => el.tagName.toLowerCase() + '.' + el.className.trim().replace(/\\s+/g, '.')
                                 + ' (children=' + el.childElementCount + ')');
                      return { title: document.title, url: location.href, candidates: uls };
                    }""");
            log.warn("列表页结构快照: {}", info);
        } catch (Exception e) {
            log.warn("抓取列表页结构失败: {}", e.getMessage());
        }
    }

    /**
     * 落地后检查是否被弹到风控/验证页，日志里能直接看出是哪一步触发的。
     */
    private void warnIfSecurityCheck(String keyword) {
        BossPageSafety.requireUsablePage(page);
    }

    /**
     * 备注：目前Boss无法通过新标签页打开立即沟通按钮，所以只能点击更多详情，然后从更多详情里打开聊天按钮
     */
    @SneakyThrows
    private void resumeSubmission(String keyword, Job job) {
        // 若收到停止指令，直接短路返回
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
            log.info("停止指令已触发，跳过投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }
        // 调试模式：仅遍历不投递
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅遍历岗位，不投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }

        BossPageSafety.requireUsablePage(page);

        // 1. 查找"查看更多信息"按钮（必须存在且新开页）
        Locator moreInfoBtn = page.locator("a.more-job-btn");
        if (moreInfoBtn.count() == 0) {
            log.warn("未找到\"查看更多信息\"按钮，跳过...");
            return;
        }
        // 强制用js新开tab
        String href = moreInfoBtn.first().getAttribute("href");
        if (href == null || !href.startsWith("/job_detail/")) {
            log.warn("未获取到岗位详情链接，跳过...");
            return;
        }
        String detailUrl = "https://www.zhipin.com" + href;
        // 2. 在新窗口打开详情页
        pauseBeforeAction(page, "打开完整岗位详情");
        Page detailPage = page.context().newPage();
        try {
            submitFromDetailPage(keyword, job, detailUrl, detailPage);
        } catch (CancellationException canceled) {
            try {
                detailPage.close();
            } catch (RuntimeException closeFailure) {
                log.debug("取消任务后关闭详情页失败：{}", closeFailure.getClass().getSimpleName());
            }
            throw canceled;
        }
    }

    private boolean processScreenshotJob(String keyword, BossJobDetailReader.Detail detail) {
        JSONObject data = detail.root().getJSONObject("zpData");
        String jobId = data.getJSONObject("jobInfo").getString("encryptId");
        requireActionAllowed(page);
        requireSelectedScreenshotJob(jobId);
        Locator more = page.locator("a.more-job-btn").first();
        String href = more.getAttribute("href");
        String detailUrl = href != null && href.startsWith("/job_detail/")
                ? "https://www.zhipin.com" + href : null;
        if (detailUrl == null || !jobId.equals(extractEncryptId(detailUrl))) {
            throw BossTaskPausedException.detailsUnavailable("无法核对截图目标与已选择岗位");
        }
        pauseBeforeAction(page, "打开岗位详情并自动截图");
        Page detailPage = page.context().newPage();
        try {
            navigateToDetailPage(detailUrl, detailPage);
            Runnable allowed = () -> {
                requireActionAllowed(detailPage);
                BossScreenshotScreening.requireJobPage(detailPage, jobId);
                requireNoConflictingScreenshotSelection(jobId);
            };
            allowed.run();
            screenshotProgress("等待岗位内容加载并稳定，随后自动截图识别", null);
            ScreenshotAnalysisResult result = screenshotScreening.analyze(
                    detailPage, jobId, screenshotSettings.rules(), allowed);
            allowed.run();
            if (result == null || result.evaluation() == null) {
                throw BossTaskPausedException.detailsUnavailable("截图识别没有返回有效判断结果");
            }
            String decision = result.evaluation().decision();
            screenshotProgress("截图识别完成：" + result.imageCount() + "张，" + decision, result.id());
            if (!"COMPLETE".equals(result.status()) || result.fields() == null) {
                String code = result.errorCode() == null ? "INVALID_RESULT" : result.errorCode();
                if ("INVALID_EXTRACTION".equals(code)) {
                    // The vision call itself succeeded; only this job's model JSON was unusable.
                    // That is not an account or page problem, so skip the job and keep the run.
                    screenshotProgress("截图识别跳过该岗位："
                            + Objects.toString(result.errorMessage(), "模型输出不合规"), result.id());
                    return false;
                }
                throw BossTaskPausedException.detailsUnavailable("截图识别未完成（" + code
                        + "），记录 " + result.id() + "；本轮未继续沟通");
            }
            ScreenshotFields fields = result.fields();
            String blocked = screenshotMetadataBlockReason(fields, data);
            boolean matched = "MATCH".equals(decision) && blocked == null;
            processJobDetailJsonAndInsert(detail.body(), fields, matched);
            if (!matched) {
                screenshotProgress("截图岗位已过滤：" + (blocked != null ? blocked
                        : String.join("；", result.evaluation().reasons())), result.id());
                return false;
            }
            if (!result.evaluation().warnings().isEmpty()) {
                screenshotProgress("截图识别提醒：" + String.join("；", result.evaluation().warnings()), result.id());
            }
            Job job = new Job();
            job.setJobName(Objects.toString(fields.jobTitle(), ""));
            job.setSalary(Objects.toString(fields.salaryText(), ""));
            job.setCompanyName(Objects.toString(fields.company(), ""));
            job.setJobArea(Objects.toString(fields.city(), ""));
            job.setJobInfo(Objects.toString(fields.description(), ""));
            JSONObject hr = data.optJSONObject("bossInfo");
            job.setRecruiter(hr == null ? "" : hr.optString("name", ""));
            allowed.run();
            screenshotProgress("截图岗位符合条件，准备沟通：" + job.getJobName(), result.id());
            communicateFromDetailPage(keyword, job, detailUrl, detailPage, allowed);
            return true;
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (BossJobScreenshotCapture.CaptureException failure) {
            requireActionAllowed(detailPage);
            // Capture messages contain controlled stage/reason text, never raw browser errors or page content.
            log.warn("自动截图未完成：{}", failure.getMessage());
            throw BossTaskPausedException.captureUnavailable(failure.getMessage());
        } catch (ScreenshotVisionException failure) {
            throw BossTaskPausedException.detailsUnavailable("截图识别暂不可用（" + failure.getCode()
                    + "），请检查截图识别配置或等待其他识别任务完成");
        } catch (java.io.IOException failure) {
            throw BossTaskPausedException.detailsUnavailable("截图识别记录保存失败，请检查本地目录和磁盘空间");
        } catch (RuntimeException failure) {
            requireActionAllowed(detailPage);
            throw BossTaskPausedException.detailsUnavailable("自动截图或识别未完成（"
                    + failure.getClass().getSimpleName() + "），未继续沟通");
        } finally {
            try { detailPage.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void requireSelectedScreenshotJob(String expectedId) {
        if (!expectedId.equals(selectedJobId())) {
            throw BossTaskPausedException.detailsUnavailable("打开截图详情前，列表选中岗位尚未确认，未打开详情或沟通");
        }
    }

    /** The bound detail tab is authoritative; an absent background link is not a job switch. */
    private void requireNoConflictingScreenshotSelection(String expectedId) {
        final String selectedId;
        try {
            selectedId = selectedJobId();
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException unavailable) {
            // A re-render/detached background link says nothing about the bound detail tab.
            // Recheck cancellation and page safety without refreshing or repeating an action.
            requireActionAllowed(page);
            log.debug("截图详情目标已核对，后台列表选择暂不可读，继续使用已绑定详情页；异常类型：{}",
                    unavailable.getClass().getSimpleName());
            return;
        }
        if (selectedId != null && !expectedId.equals(selectedId)) {
            throw BossTaskPausedException.detailsUnavailable("后台列表明确选中了其他岗位，未继续沟通");
        }
    }

    private String screenshotMetadataBlockReason(ScreenshotFields fields, JSONObject data) {
        JSONObject hr = data.optJSONObject("bossInfo");
        JSONObject company = data.optJSONObject("brandComInfo");
        if (blacklistContains(blackJobs, fields.jobTitle())) return "职位黑名单命中";
        if (blacklistContains(blackCompanies, fields.company())
                || (company != null && blacklistContains(blackCompanies, company.optString("brandName", "")))) {
            return "公司黑名单命中";
        }
        if (hr != null) {
            if (blacklistContains(blackRecruiters, hr.optString("title", ""))) return "招聘者黑名单命中";
            if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hr.optString("activeTimeDesc", "").contains("年")) {
                return "HR活跃状态包含‘年’";
            }
        }
        return null;
    }

    private static boolean blacklistContains(Set<String> terms, String value) {
        return terms != null && value != null && terms.stream().anyMatch(value::contains);
    }

    private void screenshotProgress(String message, String resultId) {
        String text = resultId == null ? message : message + "（识别记录：" + resultId + "）";
        log.info(text);
        if (progressCallback != null) progressCallback.accept(text, null, null);
    }

    @SneakyThrows
    private void submitFromDetailPage(String keyword, Job job, String detailUrl, Page detailPage) {
        navigateToDetailPage(detailUrl, detailPage);
        communicateFromDetailPage(keyword, job, detailUrl, detailPage);
    }

    private void navigateToDetailPage(String detailUrl, Page detailPage) {
        try {
            detailPage.navigate(detailUrl, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(45_000));
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            requireActionAllowed(detailPage);
            String currentUrl = detailPage.url();
            String targetPath = detailUrl.split("\\?", 2)[0];
            if (currentUrl == null || !(currentUrl.equals(targetPath) || currentUrl.startsWith(targetPath + "?"))) {
                throw BossTaskPausedException.detailsUnavailable(
                        "岗位详情导航失败，且尚未到达目标页面（" + failure.getClass().getSimpleName() + "）");
            }
            log.warn("岗位详情导航等待异常但已到达目标页面，继续检查沟通控件；异常类型：{}",
                    failure.getClass().getSimpleName());
        }
    }

    @SneakyThrows
    private void communicateFromDetailPage(String keyword, Job job, String detailUrl, Page detailPage) {
        communicateFromDetailPage(keyword, job, detailUrl, detailPage, () -> { });
    }

    @SneakyThrows
    private void communicateFromDetailPage(String keyword, Job job, String detailUrl, Page detailPage,
                                           Runnable identityGuard) {
        pauseBeforeAction(detailPage, "打开沟通窗口");
        identityGuard.run();

        // 3. 查找"立即沟通"按钮
        Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
        boolean foundChatBtn = false;
        for (int i = 0; i < 5; i++) {
            identityGuard.run();
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束查找聊天按钮 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                try { detailPage.close(); } catch (Exception ignore) {}
                return;
            }
            BossPageSafety.requireUsablePage(detailPage);
            if (chatBtn.count() > 0 && (chatBtn.first().textContent().contains("立即沟通"))) {
                foundChatBtn = true;
                break;
            }
            PlaywrightUtil.sleep(1);
        }
        if (!foundChatBtn) {
            log.warn("未找到立即沟通按钮，跳过岗位: {}", job.getJobName());
            // 关闭详情页
            try {
                detailPage.close();
            } catch (Exception ignore) {
            }
            return;
        }
        BossPageSafety.requireUsablePage(detailPage);
        identityGuard.run();
        clickWithMouse(detailPage, chatBtn.first(), identityGuard);
        pauseBeforeAction(detailPage, "准备招呼内容");
        identityGuard.run();

        // 4. 等待聊天输入框
        Locator inputLocator = detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
        boolean inputReady = false;
        for (int i = 0; i < 10; i++) {
            identityGuard.run();
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束等待聊天输入框 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                try { detailPage.close(); } catch (Exception ignore) {}
                return;
            }
            BossPageSafety.requireUsablePage(detailPage);
            if (inputLocator.count() > 0 && inputLocator.first().isVisible()) {
                inputReady = true;
                break;
            }
            PlaywrightUtil.sleep(1);
        }
        if (!inputReady) {
            log.warn("聊天输入框未出现，跳过: {}", job.getJobName());
            // 关闭详情页
            try {
                detailPage.close();
            } catch (Exception ignore) {
            }
            return;
        }

        // 5. AI智能生成打招呼语
        String aiMessage = null;
        if (config.getEnableAI()) {
            String jd = job.getJobInfo();
            if (jd != null && !jd.isEmpty()) {
                aiMessage = generateAiMessage(keyword, job.getJobName(), jd);
            }
        }
        String message = isValidString(aiMessage) ? aiMessage : config.getSayHi();

        // 6. 输入打招呼语
        checkStopRequested();
        BossPageSafety.requireUsablePage(detailPage);
        identityGuard.run();
        Locator input = inputLocator.first();
        clickWithMouse(detailPage, input, identityGuard);
        Object tagObj = input.evaluate("el => el.tagName.toLowerCase()");
        if (tagObj instanceof String && ((String) tagObj).equals("textarea")) {
            input.fill(message);
        } else {
            // 对 contenteditable 节点写入文本并派发 input 事件
            input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }", message);
        }

        // 7. 点击发送按钮（div.send-message 或 button.btn-send）
        pauseBeforeAction(detailPage, "发送招呼");
        Locator sendText = detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
        boolean sendSuccess = false;
        if (sendText.count() > 0) {
            checkStopRequested();
            BossPageSafety.requireUsablePage(detailPage);
            identityGuard.run();
            clickWithMouse(detailPage, sendText.first(), identityGuard);
            PlaywrightUtil.sleep(1);
            sendSuccess = true;
            try {
                clickWithMouse(detailPage, detailPage.locator("i.icon-close").first(), identityGuard);
            } catch (BossTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (Exception e) {
                log.error("发送文本小窗口关闭失败！");
            }
        } else {
            log.warn("未找到发送按钮，自动跳过！岗位：{}", job.getJobName());
        }

        // 8. 发送图片简历（可选）
        boolean imgResume = false;
        if (Boolean.TRUE.equals(config.getSendImgResume())) {
            pauseBeforeAction(detailPage, "发送图片简历");
            identityGuard.run();
            imgResume = sendImageResume(detailPage);
        }

        log.info("投递完成 | 公司：{} | 岗位：{} | 薪资：{} | 招呼语：{} | 图片简历：{}", job.getCompanyName(), job.getJobName(), job.getSalary(), message, imgResume ? "已发送" : "未发送");

        // 9. 关闭新打开的详情页
        try {
            detailPage.close();
        } catch (Exception ignore) {
        }
        // 10. 更新数据库投递状态 & 成功投递加入结果
        if (sendSuccess) {
            // 从详情链接提取 encrypt_id，并映射到 encrypt_user_id
            String encryptId = extractEncryptId(detailUrl);
            String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
            if (encryptId != null && encryptUserId != null) {
                try {
        bossService.updateDeliveryStatus(encryptId, encryptUserId, "已投递");
                    log.info("投递成功 | 公司：{} | 岗位：{} | encryptId：{} | encryptUserId：{}", job.getCompanyName(), job.getJobName(), encryptId, encryptUserId);
                } catch (Exception e) {
                    log.warn("更新投递状态为已投递失败：{}", e.getMessage());
                }
            } else {
                log.debug("未能找到 encryptId/encryptUserId 用于更新投递状态，detailUrl: {}", detailUrl);
            }
            resultList.add(job);
        } else {
            // 若发生发送失败，也进行状态更新
            String encryptId = extractEncryptId(detailUrl);
            String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
            if (encryptId != null && encryptUserId != null) {
                try {
        bossService.updateDeliveryStatus(encryptId, encryptUserId, "投递失败");
                    log.warn("投递失败 | 公司：{} | 岗位：{} | encryptId：{} | encryptUserId：{}", job.getCompanyName(), job.getJobName(), encryptId, encryptUserId);
                } catch (Exception e) {
                    log.warn("更新投递状态为投递失败异常：{}", e.getMessage());
                }
            }
        }
    }

    

    /**
     * 注册页面响应监听：拦截 /wapi/zpgeek/job/detail.json 请求并解析写库
     */
    private void attachJobDetailResponseListener() {
        if (page == null) return;
        page.onResponse(resp -> {
            try {
                String url = resp.url();
                if (url == null) return;
                // 仅处理 Boss 岗位详情接口（GET）
                if (url.contains("/wapi/zpgeek/job/detail.json") &&
                        "GET".equalsIgnoreCase(resp.request().method())) {
                    String body = null;
                    try {
                        body = resp.text();
                    } catch (Throwable ignore) {
                        // 某些情况下可能拿不到文本，忽略
                    }
                    if (body == null || body.isEmpty()) return;

                    // 保存原始 JSON 到 target/job.txt
                    appendRawJson(body);

                    // 仅记录映射与原始 JSON；入库逻辑已移动到点击卡片时
                    JSONObject root = new JSONObject(body);
                    JSONObject zpData = root.optJSONObject("zpData");
                    if (zpData == null) return;
                    JSONObject jobInfo = zpData.optJSONObject("jobInfo");
                    if (jobInfo == null) return;
                    String encryptId = jobInfo.optString("encryptId", null);
                    String encryptUserId = jobInfo.optString("encryptUserId", null);
                    if (encryptId != null && encryptUserId != null) {
                        encryptIdToUserId.put(encryptId, encryptUserId);
                    }
                }
            } catch (Throwable e) {
                log.debug("监听岗位详情响应处理异常：{}", e.getMessage());
            }
        });
    }


    /**
     * 追加保存原始 JSON 到 target/job.txt
     */
    void appendRawJson(String body) {
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "job.txt");
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(body);
                fw.write(System.lineSeparator());
                fw.write("\n");
            }
        } catch (Exception e) {
            log.debug("写入 target/job.txt 失败：{}", e.getMessage());
        }
    }

    /**
     * 从详情页 URL 中提取 encrypt_id
     */
    private String extractEncryptId(String detailUrl) {
        try {
            if (detailUrl == null) return null;
            String key = "/job_detail/";
            int idx = detailUrl.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = detailUrl.indexOf(".html", start);
            if (end < 0) end = detailUrl.length();
            return detailUrl.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidString(String str) {
        return str != null && !str.isEmpty();
    }

    private boolean sendImageResume(Page page) {
        try {
            requireActionAllowed(page);
            // 0) 资源存在性校验，避免后续无效操作
            URL resourceUrlCheck = Boss.class.getResource("/resume.jpg");
            if (resourceUrlCheck == null) {
                log.error("资源文件 resume.jpg 不存在，跳过发送图片简历");
                return false;
            }

            // 进入聊天页
            if (!page.url().contains("/web/geek/chat")) {
                Locator chatBtn = page.locator("a.btn-startchat, a.op-btn-chat");
                if (chatBtn.count() == 0) {
                    log.warn("未找到【继续沟通/立即沟通】按钮，跳过发送图片简历");
                    return false;
                }
                requireActionAllowed(page);
                clickWithMouse(page, chatBtn.first());
                page.waitForURL("**/web/geek/chat**", new Page.WaitForURLOptions().setTimeout(15_000));
            }
            requireActionAllowed(page);

            // 1) 解析图片路径（在可能触发文件选择器前就准备好）
            java.nio.file.Path imagePath = resolveResumeImage();

            // 精准定位聊天工具栏内的图片输入，避免匹配到页面其他上传控件
            Locator imgContainer = page.locator("div.btn-sendimg[aria-label='发送图片'], div[aria-label='发送图片'].btn-sendimg");
            Locator imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
            if (imageInput.count() == 0) {
                // 若未渲染，尝试拦截系统文件选择器；若未出现则普通点击促使 input 出现
                if (imgContainer.count() > 0) {
                    boolean chooserHandled = false;
                    try {
                        com.microsoft.playwright.FileChooser chooser = page.waitForFileChooser(() -> {
                            requireActionAllowed(page);
                            clickWithMouse(page, imgContainer.first());
                        });
                        requireActionAllowed(page);
                        chooser.setFiles(imagePath);
                        chooserHandled = true;
                        log.info("通过 FileChooser 直接提交图片文件，避免系统窗口阻塞");
                    } catch (com.microsoft.playwright.PlaywrightException ignore) {
                        // 未弹出系统文件选择器，继续常规流程
                    }
                    if (!chooserHandled) {
                        PlaywrightUtil.sleep(1);
                        imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
                    }
                }
            }
            imageInput.waitFor(new Locator.WaitForOptions().setTimeout(10_000));

            // 上传图片
            requireActionAllowed(page);
            imageInput.setInputFiles(imagePath);
            PlaywrightUtil.sleep(1);
            return true;
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (Throwable e) {
            requireActionAllowed(page);
            log.error("发送图片简历失败：{}", e.getMessage(), e);
            return false;
        }
    }

    private java.nio.file.Path resolveResumeImage() throws Exception {
        URL resourceUrl = Boss.class.getResource("/resume.jpg");
        if (resourceUrl == null) {
            throw new IllegalStateException("资源文件 /resume.jpg 未找到，请将图片放置到 src/main/resources 目录下");
        }
        if ("file".equalsIgnoreCase(resourceUrl.getProtocol())) {
            return java.nio.file.Paths.get(resourceUrl.toURI());
        }
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("resume-", ".jpg");
        try (java.io.InputStream in = Boss.class.getResourceAsStream("/resume.jpg")) {
            if (in == null) {
                throw new IllegalStateException("无法从类路径读取 /resume.jpg 资源");
            }
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     * true 不符合预期
     * false 符合预期
     * 期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     * 期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private boolean isSalaryNotExpected(String salary) {
        try {
            // 1. 如果没有期望薪资范围，直接返回 false，表示"薪资并非不符合预期"
            List<Integer> expectedSalary = config.getExpectedSalary();
            if (!hasExpectedSalary(expectedSalary)) {
                return false;
            }

            // 2. 清理薪资文本（比如去掉 "·15薪"）
            salary = removeYearBonusText(salary);

            // 3. 如果薪资格式不符合预期（如缺少 "K" / "k"），直接返回 true，表示"薪资不符合预期"
            if (!isSalaryInExpectedFormat(salary)) {
                return true;
            }

            // 4. 进一步清理薪资文本，比如去除 "K"、"k"、"·" 等
            salary = cleanSalaryText(salary);

            // 5. 判断是 "月薪" 还是 "日薪"
            String jobType = detectJobType(salary);
            salary = removeDayUnitIfNeeded(salary); // 如果是按天，则去除 "元/天"

            // 6. 解析薪资范围并检查是否超出预期
            Integer[] jobSalaryRange = parseSalaryRange(salary);
            return isSalaryOutOfRange(jobSalaryRange,
                    getMinimumSalary(expectedSalary),
                    getMaximumSalary(expectedSalary),
                    jobType);

        } catch (Exception e) {
            log.error("岗位薪资获取异常！薪资文本【{}】,异常信息【{}】", salary, e.getMessage(), e);
            // 出错时，您可根据业务需求决定返回 true 或 false
            // 这里假设出错时无法判断，视为不满足预期 => 返回 true
            return true;
        }
    }

    /**
     * 是否存在有效的期望薪资范围
     */
    private boolean hasExpectedSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty();
    }

    /**
     * 去掉年终奖信息，如 "·15薪"、"·13薪"。
     */
    private String removeYearBonusText(String salary) {
        if (salary.contains("薪")) {
            // 使用正则去除 "·任意数字薪"
            return salary.replaceAll("·\\d+薪", "");
        }
        return salary;
    }

    /**
     * 判断是否是按天计薪，如发现 "元/天" 则认为是日薪
     */
    private String detectJobType(String salary) {
        if (salary.contains("元/天")) {
            return "day";
        }
        return "mouth";
    }

    /**
     * 如果是日薪，则去除 "元/天"
     */
    private String removeDayUnitIfNeeded(String salary) {
        if (salary.contains("元/天")) {
            return salary.replaceAll("元/天", "");
        }
        return salary;
    }

    private Integer getMinimumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty() ? expectedSalary.get(0) : null;
    }

    private Integer getMaximumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && expectedSalary.size() > 1 ? expectedSalary.get(1) : null;
    }

    private boolean isSalaryInExpectedFormat(String salaryText) {
        return salaryText.contains("K") || salaryText.contains("k") || salaryText.contains("元/天");
    }

    private String cleanSalaryText(String salaryText) {
        salaryText = salaryText.replace("K", "").replace("k", "");
        int dotIndex = salaryText.indexOf('·');
        if (dotIndex != -1) {
            salaryText = salaryText.substring(0, dotIndex);
        }
        return salaryText;
    }

    private boolean isSalaryOutOfRange(Integer[] jobSalary, Integer miniSalary, Integer maxSalary,
                                       String jobType) {
        if (jobSalary == null) {
            return true;
        }
        if (miniSalary == null) {
            return false;
        }
        if (Objects.equals("day", jobType)) {
            // 期望薪资转为平均每日的工资
            maxSalary = BigDecimal.valueOf(maxSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
            miniSalary = BigDecimal.valueOf(miniSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
        }
        // 如果职位薪资下限低于期望的最低薪资，返回不符合
        if (jobSalary[1] < miniSalary) {
            return true;
        }
        // 如果职位薪资上限高于期望的最高薪资，返回不符合
        return maxSalary != null && jobSalary[0] > maxSalary;
    }

    public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    private String generateAiMessage(String keyword, String jobName, String jd) {
        AiEntity aiConfig = aiService.getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";
        String prompt = (aiConfig != null) ? aiConfig.getPrompt() : null;

        String requestMessage = (prompt != null)
                ? String.format(prompt, introduce, keyword, jobName, jd, config.getSayHi())
                : buildDefaultPrompt(introduce, keyword, jobName, jd);

        try {
            String result = aiService.sendRequest(requestMessage);
            if (result == null) {
                return config.getSayHi();
            }
            return result.toLowerCase().contains("false") ? config.getSayHi() : result;
        } catch (Exception e) {
            log.warn("AI请求失败，使用原有打招呼语: {}", e.getMessage());
            return config.getSayHi();
        }
    }

    private String buildDefaultPrompt(String introduce, String keyword, String jobName, String jd) {
        return "请基于以下信息生成简洁友好的中文打招呼语，不超过60字：\n" +
                "个人介绍：" + introduce + "\n" +
                "关键词：" + keyword + "\n" +
                "职位名称：" + jobName + "\n" +
                "职位描述：" + jd + "\n" +
                "参考语：" + config.getSayHi();
    }

    private Integer[] parseSalaryRange(String salaryText) {
        try {
            return Arrays.stream(salaryText.split("-")).map(s -> s.replaceAll("[^0-9]", "")) // 去除非数字字符
                    .map(Integer::parseInt) // 转换为Integer
                    .toArray(Integer[]::new); // 转换为Integer数组
        } catch (Exception e) {
            log.error("薪资解析异常！{}", e.getMessage(), e);
        }
        return null;
    }

    private void clickWithMouse(Page actionPage, Locator target) {
        clickWithMouse(actionPage, target, () -> { });
    }

    private void clickWithMouse(Page actionPage, Locator target, Runnable identityGuard) {
        prepareMouseClick(actionPage, target, identityGuard);
        finishMouseClick(actionPage, target, identityGuard);
    }

    private void prepareMouseClick(Page actionPage, Locator target, Runnable identityGuard) {
        Runnable allowed = () -> {
            requireActionAllowed(actionPage);
            identityGuard.run();
        };
        allowed.run();
        if (!mouseMovementEnabled) return;
        if (mouseMovement == null) {
            throw BossTaskPausedException.detailsUnavailable("鼠标移动助手未初始化，未执行点击");
        }
        try {
            mouseMovement.moveTo(actionPage, target, allowed, mouseMovementMinMs, mouseMovementMaxMs);
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            allowed.run();
            throw BossTaskPausedException.detailsUnavailable("鼠标移动未完成（"
                    + failure.getClass().getSimpleName() + "），未执行点击");
        }
    }

    private void finishMouseClick(Page actionPage, Locator target, Runnable identityGuard) {
        requireActionAllowed(actionPage);
        identityGuard.run();
        target.click();
    }

    /** 步骤间等待，以毫秒配置；分段等待以便及时处理停止和安全验证。 */
    void pauseBeforeAction(Page actionPage, String nextStep) {
        requireActionAllowed(actionPage);
        int min = actionDelayMinMs;
        int max = actionDelayMaxMs;
        if (min <= 0 || max < min || max > 60_000) {
            log.warn("步骤等待配置无效，使用默认 3000-5000 毫秒");
            min = 3000;
            max = 5000;
        }
        int milliseconds = ThreadLocalRandom.current().nextInt(min, max + 1);
        log.info("步骤等待 {} 毫秒 → {}", milliseconds, nextStep);
        for (int remaining = milliseconds; remaining > 0;) {
            requireActionAllowed(actionPage);
            int slice = Math.min(250, remaining);
            try {
                actionPage.waitForTimeout(slice);
            } catch (BossTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                requireActionAllowed(actionPage);
                if (!isStaleObjectError(failure)) {
                    throw BossTaskPausedException.detailsUnavailable(
                            "步骤等待失败（" + failure.getClass().getSimpleName() + "），页面检查未发现明确验证");
                }
                // A discarded frame during a read-only wait is not a detected security challenge.
                log.debug("步骤等待遇到页面切换，页面复查正常，继续等待；异常类型：{}",
                        failure.getClass().getSimpleName());
            }
            remaining -= slice;
        }
        requireActionAllowed(actionPage);
    }

    private void requireActionAllowed(Page actionPage) {
        checkStopRequested();
        BossPageSafety.requireUsablePage(actionPage);
        if (actionPage != page) {
            BossPageSafety.requireUsablePage(page);
        }
        if (detailSession != null) detailSession.requireSearchContextUnchanged();
    }

    private void checkStopRequested() {
        if (Thread.currentThread().isInterrupted()
                || (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get()))) {
            throw new CancellationException("用户取消任务");
        }
    }


    private boolean isLoginRequired() {
        try {
            Locator buttonLocator = page.locator(LOGIN_BTNS);
            if (buttonLocator.count() > 0 && buttonLocator.textContent().contains("登录")) {
                return true;
            }
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (Exception e) {
            try {
                page.locator(PAGE_HEADER).waitFor();
                Locator errorLoginLocator = page.locator(ERROR_PAGE_LOGIN);
                if (errorLoginLocator.count() > 0) {
                    clickWithMouse(page, errorLoginLocator);
                }
                return true;
            } catch (BossTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (Exception ex) {
                log.info("没有出现403访问异常");
            }
            log.info("cookie有效，已登录...");
            return false;
        }
        return false;
    }

}
