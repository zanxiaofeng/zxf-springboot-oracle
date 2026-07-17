package zxf.logging.springboot.app;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint that simulates Vault rotating the DB user's password.
 * Local demo flow (replacing the Vault static-role rotation that cannot be simulated locally):
 * 1. GET this endpoint to ALTER USER in the database (what Vault would do);
 * 2. write the new credentials into {@code $DB_CRED_DIR/username} + {@code $DB_CRED_DIR/password}
 * (what ESO/kubelet would do) — the existing watcher pipeline then hot-reloads the pool.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DatabaseUserController {

    private final JdbcTemplate jdbcTemplate;

    /** Changes an Oracle user's password via {@code ALTER USER ... IDENTIFIED BY ...}. */
    @GetMapping("/oracle/user/password")
    public String changePassword(@RequestParam String username, @RequestParam String newPassword) {
        jdbcTemplate.execute("ALTER USER " + username + " IDENTIFIED BY \"" + newPassword + "\"");
        log.info("Password changed for Oracle user {}", username);
        return "Password changed for Oracle user " + username;
    }
}
