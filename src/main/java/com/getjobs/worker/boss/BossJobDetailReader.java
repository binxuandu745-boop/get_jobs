package com.getjobs.worker.boss;

import com.microsoft.playwright.Response;
import org.json.JSONObject;

import java.util.concurrent.CancellationException;

/** Validate a response before filtering or persisting it; missing company alone is not a challenge. */
public final class BossJobDetailReader {
    public record Detail(String body, JSONObject root) { }

    private BossJobDetailReader() { }

    public static Detail read(Response response) {
        return read(response, false);
    }

    /** Screenshot mode uses the response only to bind the visible job to a stable website ID. */
    public static Detail read(Response response, boolean metadataOnly) {
        if (response == null) {
            throw BossTaskPausedException.detailsUnavailable("未取得岗位详情响应");
        }
        try {
            int status = response.status();
            if (status < 200 || status >= 300) {
                throw BossTaskPausedException.detailsUnavailable("岗位详情接口返回 HTTP " + status);
            }
            String body = response.text();
            if (body == null || body.isBlank()) {
                throw BossTaskPausedException.detailsUnavailable("岗位详情响应为空");
            }
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("zpData");
            JSONObject job = data == null ? null : data.optJSONObject("jobInfo");
            if (metadataOnly) {
                Object id = job == null ? null : job.opt("encryptId");
                if (!(id instanceof String value) || value.isBlank()) {
                    throw BossTaskPausedException.detailsUnavailable("岗位详情缺少可核对的唯一标识");
                }
            } else {
                String name = job == null ? null : job.optString("jobName", null);
                if (name == null || name.isBlank()) {
                    throw BossTaskPausedException.detailsUnavailable("岗位详情缺少有效岗位名称或关键数据");
                }
            }
            return new Detail(body, root);
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            // Do not expose the raw response, tokens, or an exception's URL in the UI/log message.
            throw BossTaskPausedException.detailsUnavailable(
                    "岗位详情读取或解析失败（" + failure.getClass().getSimpleName() + "）");
        }
    }
}
