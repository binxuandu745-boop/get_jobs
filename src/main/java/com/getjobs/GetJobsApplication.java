package com.getjobs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GetJobs应用程序启动类
 * 自动化求职平台的主入口
 *
 * @author GetJobs
 * @version 0.0.1-SNAPSHOT
 */
@SpringBootApplication(scanBasePackages = "com.getjobs")
@EnableScheduling
@EnableAsync
public class GetJobsApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GetJobsApplication.class);
        // Windows desktop pauses can show a native alert; other environments retain Boot's default.
        // An explicit -Djava.awt.headless=true still disables desktop notifications.
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            application.setHeadless(false);
        }
        application.run(args);
    }
}
