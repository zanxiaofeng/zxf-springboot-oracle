package zxf.logging.springboot.cred;

import java.time.Instant;

/**
 * 凭据变更事件：secret 卷中的凭据文件发生变化（去抖合并后发布）。
 * Spring 支持任意对象作为事件载荷（无需继承 ApplicationEvent）。
 */
public record CredentialsChangedEvent(Instant detectedAt) {
}
