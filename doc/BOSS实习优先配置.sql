-- SQLite only. Target: get_jobs/db/getjobs.db
-- 2026-10-13用户确认：实习优先，经验收窄为在校生，日薪最低125元。
-- 已执行并使用只读连接核对。原三个搜索字段的恢复SQL在.local/config-backups中。
-- 本SQL只修改搜索条件；日薪阈值和随机城市在application.yaml中配置。
-- 不修改密钥、Cookie、AI介绍、招呼语、已投递记录或其他平台。
PRAGMA busy_timeout = 5000;
BEGIN IMMEDIATE;

CREATE TEMP TABLE _boss_intern_options_guard (
    valid INTEGER NOT NULL CHECK (valid = 1)
);
INSERT INTO _boss_intern_options_guard(valid)
SELECT CASE WHEN EXISTS (
    SELECT 1 FROM boss_option
    WHERE type = 'experience' AND name = '在校生' AND code = '108'
) AND EXISTS (
    SELECT 1 FROM boss_option
    WHERE type = 'salary' AND name = '不限' AND code = '0'
) AND EXISTS (SELECT 1 FROM boss_config) THEN 1 ELSE 0 END;

UPDATE boss_config
SET keywords = '[Java后端实习,Java实习,Java开发实习生,Java研发实习生,后端开发实习生,后端研发实习生,服务端开发实习生,服务端研发实习生,后台开发实习生]',
    experience = '[在校生]',
    salary = '[不限]',
    updated_at = CURRENT_TIMESTAMP
WHERE id = (SELECT MIN(id) FROM boss_config);

-- 网站月薪区间放开，是为了让日薪岗位进入候选。
-- 投递前依然由SalaryThreshold分别复核：日薪>=125元，月薪>=5000元。
-- 2026-10-22已根据用户网页地址确认实习为1902，管理页已补齐该选项。
-- 本历史SQL不覆盖职位类型；实习应在管理页保存为“实习”，不使用“兼职”。
DROP TABLE _boss_intern_options_guard;
COMMIT;

SELECT id, keywords, experience, salary
FROM boss_config WHERE id = (SELECT MIN(id) FROM boss_config);
