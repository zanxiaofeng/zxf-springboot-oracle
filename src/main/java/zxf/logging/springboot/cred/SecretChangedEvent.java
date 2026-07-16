package zxf.logging.springboot.cred;

/**
 * 凭据文件变化信号事件。无 payload——仅表示「重新读取并应用凭据」。
 * Spring 4+ 允许发布任意对象作为事件，无需继承 ApplicationEvent。
 */
public record SecretChangedEvent() {
}
