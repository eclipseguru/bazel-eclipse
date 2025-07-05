package com.salesforce.bazel.sdk.command.shell;

import static java.lang.String.format;
import static java.nio.file.Files.isExecutable;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
public class UnixLoginShellFinderTest {

    @Test
    void detectLoginShell_from_etc_passwd_current_user() throws Exception {
        var username = System.getProperty("user.name");
        assumeTrue(
            (username != null) && !username.isBlank(),
            "user.name system property variable is not set, cannot test login shell for this user");
        var detectLoginShell = new UnixLoginShellFinder().readLoginShellFromEtcPasswd(username);
        assertNotNull(detectLoginShell);
        assertTrue(isExecutable(detectLoginShell), () -> format("not executable: '%s'", detectLoginShell));
    }

    @Test
    void detectLoginShell_from_SHELL_env_when_available() throws Exception {
        assumeTrue(
            System.getenv().containsKey(UnixLoginShellFinder.SHELL_VARIABLE_NAME),
            "SHELL environment variable is not set, cannot test login shell detection from SHELL env");
        var detectLoginShell = new UnixLoginShellFinder().detectLoginShell();
        assertNotNull(detectLoginShell);
        assertTrue(isExecutable(detectLoginShell), () -> format("not executable: '%s'", detectLoginShell));
    }

    @Test
    void readLoginShellFromEtcPasswd_unknonw_users() throws Exception {
        assertThrows(IOException.class, () -> {
            new UnixLoginShellFinder().readLoginShellFromEtcPasswd("foo-bar-" + System.nanoTime());
        });
    }

}
