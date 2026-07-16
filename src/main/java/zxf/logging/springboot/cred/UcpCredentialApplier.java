package zxf.logging.springboot.cred;

import lombok.extern.slf4j.Slf4j;
import oracle.ucp.jdbc.PoolDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 凭据应用器：把新凭据安全切换到 UCP 连接池（reconfigureDataSource + 多次借连验证）。
 * 职责单一——只回答「如何切换」，不关心「何时切换」。
 */
@Slf4j
@Component
public class UcpCredentialApplier {

    /** 验证借连次数：reconfigure 后旧连接未立即销毁，需多次确认拿到新凭据连接 */
    private static final int VERIFY_BORROWS = 3;

    private final PoolDataSource poolDataSource;
    /** 上次成功应用的凭据缓存；用于变更比较，避免调用已废弃的 PoolDataSource.getPassword() */
    private volatile DbCredentials lastApplied = new DbCredentials(null, null);

    public UcpCredentialApplier(DataSource dataSource) {
        // DataSource 可能被 LazyConnectionDataSourceProxy 包装，需 unwrap 到真实 PoolDataSource
        try {
            this.poolDataSource = dataSource.isWrapperFor(PoolDataSource.class)
                    ? dataSource.unwrap(PoolDataSource.class)
                    : (PoolDataSource) dataSource;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to unwrap UCP PoolDataSource", e);
        }
    }

    /**
     * 应用凭据：相同则跳过；否则 reconfigure + 验证。
     *
     * @return true=已切换或无变化；false=验证失败（旧连接仍可服务，等下轮重试）
     */
    public boolean apply(DbCredentials creds) {
        if (creds.isEmpty()) {
            log.warn("Read empty credentials, skipping apply");
            return false;
        }
        if (creds.equals(lastApplied)) {
            log.debug("Credentials unchanged, skip");
            return true;
        }
        try {
            log.info("Refreshing UCP credentials for user: {}", creds.username());
            poolDataSource.reconfigureDataSource(creds.toProps());
            if (!verifyNewCredentials()) {
                // 失败不回退——旧连接仍可服务，等待下一轮重试
                log.error("Credential verification FAILED; old connections may still be served, will retry next cycle");
                return false;
            }
            lastApplied = creds;
            log.info("UCP credentials refreshed & verified. borrowed={}, available={}",
                    poolDataSource.getBorrowedConnectionsCount(),
                    poolDataSource.getAvailableConnectionsCount());
            return true;
        } catch (Exception e) {
            // 异常栈含 Properties（含密码），仅记录消息，避免密码进入日志
            log.error("Failed to refresh UCP credentials: {}", e.toString());
            return false;
        }
    }

    /**
     * reconfigureDataSource 后，池中旧连接被标记过期但不会立即销毁，
     * 紧接着借一条可能分到旧连接（旧密码仍有效）→ 误判。
     * 解法：连续借多条并 isValid，确认至少能以新凭据成功建连。
     */
    private boolean verifyNewCredentials() {
        for (int i = 0; i < VERIFY_BORROWS; i++) {
            try (Connection c = poolDataSource.getConnection()) {
                if (!c.isValid(3)) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("Verify borrow #{} failed: {}", i, e.toString());
                return false;
            }
        }
        return true;
    }
}
