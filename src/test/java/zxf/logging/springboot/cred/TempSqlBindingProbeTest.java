package zxf.logging.springboot.cred;

import oracle.ucp.jdbc.PoolDataSourceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/**
 * 临时探针：验证 Boot Binder 对 UCP SQLForValidateConnection 属性的宽松绑定规则。
 */
class TempSqlBindingProbeTest {

    @Test
    void probeKebabSql() throws Exception {
        PoolDataSourceImpl pool = new PoolDataSourceImpl();
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("test.s-q-l-for-validate-connection", "select * from dual")));
        binder.bind("test", Bindable.ofInstance(pool));
        System.out.println("[PROBE] s-q-l-for-validate-connection -> " + pool.getSQLForValidateConnection());
    }

    @Test
    void probePlainSql() throws Exception {
        PoolDataSourceImpl pool = new PoolDataSourceImpl();
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("test.sql-for-validate-connection", "select * from dual")));
        binder.bind("test", Bindable.ofInstance(pool));
        System.out.println("[PROBE] sql-for-validate-connection -> " + pool.getSQLForValidateConnection());
    }
}
