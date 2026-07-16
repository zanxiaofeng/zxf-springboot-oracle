package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import oracle.ucp.jdbc.PoolDataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 自定义 DB 健康检查，替代默认 db 指标（默认已用 management.health.db.enabled=false 关闭，
 * 避免与凭据热刷新竞争借连接）。如需排障可 unwrap 暴露 UCP 池统计。
 */
@Component("dynamicDbHealth")
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection c = dataSource.getConnection()) {
            if (c.isValid(2)) {
                Health.Builder up = Health.up().withDetail("pool", dataSource.getClass().getSimpleName());
                if (dataSource.isWrapperFor(PoolDataSource.class)) {
                    PoolDataSource pds = dataSource.unwrap(PoolDataSource.class);
                    up.withDetail("borrowed", pds.getBorrowedConnectionsCount())
                      .withDetail("available", pds.getAvailableConnectionsCount());
                }
                return up.build();
            }
            return Health.down().withDetail("error", "Connection invalid").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
