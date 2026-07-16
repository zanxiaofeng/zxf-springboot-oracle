package zxf.logging.springboot.cred;

/**
 * 不可变凭据载体，天然线程安全。
 */
public record DbCredentials(String username, String password) {
    public boolean isEmpty() {
        return username == null || username.isBlank()
            || password == null || password.isBlank();
    }
}
