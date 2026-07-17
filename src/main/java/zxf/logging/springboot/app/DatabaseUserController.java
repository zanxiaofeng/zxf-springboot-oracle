package zxf.logging.springboot.app;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zxf.logging.springboot.cred.CredentialFileSource;

import java.io.IOException;

/**
 * Demo endpoint that simulates Vault rotating the DB user's password:
 * 1. ALTER USER in the database (what Vault would do);
 * 2. write the new password into {@code $DB_CRED_DIR/password} (what ESO/kubelet would do) —
 * the existing watcher pipeline then hot-reloads the pool.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DatabaseUserController {

    private final JdbcTemplate jdbcTemplate;
    private final CredentialFileSource credentialFileSource;

    /** Changes the DB user's password, then updates the password file to trigger credential hot-reload. */
    @GetMapping("/oracle/user/password")
    public String changePassword(@RequestParam String newPassword) throws IOException {
        String username = credentialFileSource.read().username();
        jdbcTemplate.execute("ALTER USER " + username + " IDENTIFIED BY \"" + newPassword + "\"");
        log.info("Password changed for Oracle user {}", username);
        credentialFileSource.writePassword(newPassword);
        log.info("Password file updated, watcher pipeline will hot-reload the pool");
        return "Password changed for Oracle user " + username + ", password file updated";
    }
}
