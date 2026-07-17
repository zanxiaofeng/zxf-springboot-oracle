package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oracle.ucp.jdbc.PoolDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 凭据应用器：先用旁路连接验证新凭据，再切换 UCP 连接池配置。
 * 职责单一——只回答「如何切换」，不关心「何时切换」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UcpCredentialApplier {

    /** 借连验证的超时秒数 */
    private static final int VALIDATION_TIMEOUT_SECONDS = 3;

    private final DataSource dataSource;
    /** 上次成功应用的凭据缓存；用于变更比较，避免调用已废弃的 PoolDataSource.getPassword() */
    private volatile DbCredentials lastApplied = DbCredentials.EMPTY;

    /**
     * 应用凭据：相同则跳过；不同则先旁路验证，再 reconfigure。
     * @return true=已切换或无变化；false=验证失败（池未被改动，旧凭据继续服务，等下轮事件）
     */
    public boolean apply(DbCredentials credentials) {
        if (credentials.isEmpty()) {
            log.warn("Read empty credentials, skipping apply");
            return false;
        }
        if (credentials.equals(lastApplied)) {
            log.debug("Credentials unchanged, skip");
            return true;
        }
        return doApply(credentials);
    }

    /** 验证 → 切换 → 记录；任何异常仅记消息（异常信息可能携带含密码的 Properties） */
    private boolean doApply(DbCredentials credentials) {
        try {
            // unwrap 是纯函数且代价可忽略，按次解出，不持有派生状态字段
            PoolDataSource pool = unwrap(dataSource);
            if (!verifyNewCredentials(pool.getURL(), credentials)) {
                // 验证失败不动池：旧连接仍可服务，等待下一轮事件
                log.error("New credentials failed out-of-band check; pool left untouched, will retry next cycle");
                return false;
            }
            log.info("Refreshing UCP credentials for user: {}", credentials.username());
            pool.reconfigureDataSource(credentials.toProperties());
            lastApplied = credentials;
            log.info("UCP credentials refreshed. borrowed={}, available={}",
                    pool.getBorrowedConnectionsCount(),
                    pool.getAvailableConnectionsCount());
            return true;
        } catch (Exception exception) {
            log.error("Failed to refresh UCP credentials: {}", exception.toString());
            return false;
        }
    }

    /**
     * 旁路验证：不经池，直接用 DriverManager 以新凭据建连。
     * 结论确定——不受池中旧连接（reconfigure 后仍可能借出旧会话）干扰。
     */
    private boolean verifyNewCredentials(String jdbcUrl, DbCredentials credentials) {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, credentials.username(), credentials.password())) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception exception) {
            log.warn("Out-of-band credential check failed: {}", exception.toString());
            return false;
        }
    }

    /** DataSource 可能被 LazyConnectionDataSourceProxy 包装（connection-fetch=lazy），需 unwrap 到真实 PoolDataSource */
    private static PoolDataSource unwrap(DataSource dataSource) {
        try {
            return dataSource.isWrapperFor(PoolDataSource.class)
                    ? dataSource.unwrap(PoolDataSource.class)
                    : (PoolDataSource) dataSource;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to unwrap UCP PoolDataSource", exception);
        }
    }
}
