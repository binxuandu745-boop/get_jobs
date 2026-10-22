-- SQLite only: 在 DataGrip 的 getjobs SQLite 数据源执行整个文件。
-- 数据库文件：C:/Users/lenovo/IdeaProjects/get_jobs/db/getjobs.db
-- 2026-10-13用户已确认并执行此配置，三个配置表各有1条记录，已读回核对。
-- 已确认：Java 后端方向，地区不限定，另外三个平台最低期望 4500 元/月。
-- 用户已确认薪资范围4500～20000元/月。
-- 51job 界面最多 5 档；当前 04～08 为 4.5千～2万，不能称作“4500以上无上限”。
-- 猎聘和智联范围值采用本项目界面的输入格式，尚未做网站端实际验证。
-- 不修改 BOSS、AI配置、API密钥、Cookie、登录记录、browser-data 或启动模式。
-- 不启动浏览器、发送招呼或执行投递。
-- 配置表没有毕业年份字段；真实年份保持2028，岗位是否接受在校生须人工确认。
-- 若运行报错，请在该连接执行 ROLLBACK;，排查后再重试整个文件。

PRAGMA busy_timeout = 5000;
BEGIN IMMEDIATE;

-- 验证本数据库的51job档位，避免把BOSS枚举或其他版本的代码套进来。
CREATE TEMP TABLE _get_jobs_multiplatform_guard (
    valid INTEGER NOT NULL CHECK (valid = 1)
);
INSERT INTO _get_jobs_multiplatform_guard(valid)
SELECT CASE WHEN COUNT(*) = 5 THEN 1 ELSE 0 END
FROM job51_option
WHERE type = 'salary'
  AND ((code = '04' AND name = '4.5-6千')
    OR (code = '05' AND name = '6-8千')
    OR (code = '06' AND name = '0.8-1万')
    OR (code = '07' AND name = '1-1.5万')
    OR (code = '08' AND name = '1.5-2万'));

-- 猎聘：关键词使用无引号的括号列表，兼容其现有解析代码。
-- city='' 省略地区条件；salary_code 中的数值单位为千元。
INSERT INTO liepin_config(keywords, city, salary_code, created_at, updated_at)
SELECT '[Java后端实习,Java实习,Java后端开发,Java开发工程师,Java后端研发,Java服务端开发,Java服务端研发]',
       '', '4.5$20', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM liepin_config);

UPDATE liepin_config
SET keywords = '[Java后端实习,Java实习,Java后端开发,Java开发工程师,Java后端研发,Java服务端开发,Java服务端研发]',
    city = '',
    salary_code = '4.5$20',
    updated_at = CURRENT_TIMESTAMP
WHERE id = (SELECT MIN(id) FROM liepin_config);

-- 前程无忧：空区域列表省略地区条件；薪资代码必须保留前导零。
INSERT INTO job51_config(keywords, job_area, salary, created_at, updated_at)
SELECT '[Java后端实习,Java实习,Java后端开发,Java开发工程师,Java后端研发,Java服务端开发,Java服务端研发]',
       '[]', '["04","05","06","07","08"]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM job51_config);

UPDATE job51_config
SET keywords = '[Java后端实习,Java实习,Java后端开发,Java开发工程师,Java后端研发,Java服务端开发,Java服务端研发]',
    job_area = '[]',
    salary = '["04","05","06","07","08"]',
    updated_at = CURRENT_TIMESTAMP
WHERE id = (SELECT MIN(id) FROM job51_config);

-- 智联招聘：city_code='0' 为不限；salary 的单位为元，格式为“最低,最高”。
INSERT INTO zhilian_config(keywords, city_code, salary, created_at, updated_at)
SELECT '[Java后端实习,Java实习,Java后端开发,Java开发工程师,Java后端研发,Java服务端开发,Java服务端研发]',
       '0', '4500,20000', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM zhilian_config);

UPDATE zhilian_config
SET keywords = '[Java后端实习,Java实习,Java后端开发,Java开发工程师,Java后端研发,Java服务端开发,Java服务端研发]',
    city_code = '0',
    salary = '4500,20000',
    updated_at = CURRENT_TIMESTAMP
WHERE id = (SELECT MIN(id) FROM zhilian_config);

DROP TABLE _get_jobs_multiplatform_guard;
COMMIT;

-- 仅核对三个配置表，不读取API密钥或登录会话。
SELECT '猎聘' AS platform, keywords, city AS area, salary_code AS salary_range
FROM liepin_config WHERE id = (SELECT MIN(id) FROM liepin_config)
UNION ALL
SELECT '前程无忧', keywords, job_area, salary
FROM job51_config WHERE id = (SELECT MIN(id) FROM job51_config)
UNION ALL
SELECT '智联招聘', keywords, city_code, salary
FROM zhilian_config WHERE id = (SELECT MIN(id) FROM zhilian_config);
