package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import oracle.ucp.jdbc.PoolDataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 自定义 DB 健康检查，替代默认 db 指标（默认已用 management.health.db.enabled=false 关闭，
 * 避免与凭据热刷新竞争借连接）。如需排障可 unwrap 暴露 UCP 池统计。
 */
@Component("dynamicDbHealth")
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {
    /** 借连验证的超时秒数 */
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            return probe(connection);
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }

    /** 探测：借连无效直接 DOWN，有效则补充池明细 */
    private Health probe(Connection connection) throws SQLException {
        if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
            return Health.down().withDetail("error", "Connection invalid").build();
        }
        return buildUpHealth();
    }

    /** UP 明细：可选暴露 UCP 统计便于排障 */
    private Health buildUpHealth() throws SQLException {
        Health.Builder upBuilder = Health.up().withDetail("pool", dataSource.getClass().getSimpleName());
        if (!dataSource.isWrapperFor(PoolDataSource.class)) {
            return upBuilder.build();
        }
        PoolDataSource poolDataSource = dataSource.unwrap(PoolDataSource.class);
        return upBuilder
                .withDetail("borrowed", poolDataSource.getBorrowedConnectionsCount())
                .withDetail("available", poolDataSource.getAvailableConnectionsCount())
                .build();
    }
}
