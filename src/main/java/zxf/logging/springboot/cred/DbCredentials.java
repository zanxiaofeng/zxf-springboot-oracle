package zxf.logging.springboot.cred;

import java.util.Properties;

/**
 * 不可变凭据载体，天然线程安全。
 */
public record DbCredentials(String username, String password) {

    /** 空凭据哨兵：作为「尚未应用任何凭据」的初始值 */
    public static final DbCredentials EMPTY = new DbCredentials(null, null);

    public boolean isEmpty() {
        return username == null || username.isBlank()
            || password == null || password.isBlank();
    }

    /** 转为 reconfigureDataSource 所需的 Properties（user/password） */
    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", username());
        properties.setProperty("password", password());
        return properties;
    }
}
