package com.aivca.model.session;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Episode —— 一次完整交互的时间窗口。
 *
 * 生命周期: OPEN → CLOSED → RESPONDING → 废弃
 * 一个 session 同时最多存在一个未关闭的 episode。
 */
public class Episode {

    /** 唯一标识（UUID 前 8 位） */
    private final String id;

    /** 创建时间 */
    private final Instant startTime;

    /** 关闭时间 */
    private Instant closeTime;

    /** 关闭原因: speech / action_end / scene_change */
    private String closeReason;

    /** episode 内用户说的话（可为 null） */
    private String speech;

    /** episode 内最新的画面描述 */
    private String visionDesc;

    /** episode 内最新的动作 */
    private String action;

    /** 是否已关闭 */
    private boolean closed;

    public Episode() {
        this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.startTime = Instant.now();
    }

    /** 更新画面数据 */
    public void updateVision(String desc, String act) {
        if (desc != null) this.visionDesc = desc;
        if (act != null) this.action = act;
    }

    /** 设置语音（锚点） */
    public void setSpeech(String text) {
        this.speech = text;
    }

    /** 检查是否应该关闭 episode */
    public boolean shouldClose() {
        if (closed) return false;

        // 用户说话了 → 关闭
        if (speech != null && !speech.isEmpty()) {
            closeReason = "speech";
            return true;
        }

        // 明确动作词 → 关闭并回复
        if (action != null && isSignificantAction(action)) {
            closeReason = "action";
            return true;
        }

        // 10 秒超时 → 关闭
        if (Duration.between(startTime, Instant.now()).getSeconds() > 10) {
            closeReason = "timeout";
            return true;
        }

        return false;
    }

    /** 判断 action 是否包含显著动作词 */
    private boolean isSignificantAction(String act) {
        for (String kw : new String[]{"挥手","点头","站起","坐下","微笑","离开","出现","走进","走过",
                "抬起","放下","展示","比划","遮挡","靠近","远离"}) {
            if (act.contains(kw)) return true;
        }
        return false;
    }

    /** 强制关闭（场景显著变化） */
    public void forceClose(String reason) {
        this.closeReason = reason;
        this.closed = true;
        this.closeTime = Instant.now();
    }

    // getters
    public String getId() { return id; }
    public Instant getStartTime() { return startTime; }
    public Instant getCloseTime() { return closeTime; }
    public String getCloseReason() { return closeReason; }
    public String getSpeech() { return speech; }
    public String getVisionDesc() { return visionDesc; }
    public String getAction() { return action; }
    public boolean isClosed() { return closed; }

    // setters for close
    public void setClosed(boolean closed) { this.closed = closed; }
    public void setCloseTime(Instant closeTime) { this.closeTime = closeTime; }
}
