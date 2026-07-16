package zxf.logging.springboot.cred;

import java.util.Properties;

/**
 * 不可变凭据载体，天然线程安全。
 */
public record DbCredentials(String username, String password) {
    public boolean isEmpty() {
        return username == null || username.isBlank()
            || password == null || password.isBlank();
    }

    public Properties toProps() {
        Properties props = new Properties();
        props.setProperty("user", this.username());
        props.setProperty("password", this.password());
        return props;
    }
}
