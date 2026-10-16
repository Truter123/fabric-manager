package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolSshHostTest {

    @Test
    void plainHostOmitsDefaultsAndPutsDashDashFirst() {
        var tool = Tool.ssh("box", "example.com", null, 22, null);
        assertEquals("ssh -- 'example.com'", tool.command());
        assertEquals("ssh box", tool.name());
        assertTrue(tool.isSsh());
    }

    @Test
    void userPortAndIdentity() {
        assertEquals("ssh -p '2200' -i '/home/me/.ssh/id' -- 'me@h'",
                Tool.ssh("b", "h", "me", 2200, "/home/me/.ssh/id").command());
        assertEquals("ssh -- 'me@h'", Tool.ssh("b", "h", "me", 22, " ").command());
        assertEquals("ssh -- 'h'", Tool.ssh("b", "h", "", 22, null).command());
    }

    @Test
    void valuesWithSpacesAndQuotesAreQuoted() {
        assertEquals("ssh -i '/a b/it'\\''s' -- 'h'", Tool.ssh("b", "h", null, 22, "/a b/it's").command());
        assertEquals("ssh -- 'o'\\''x@h; rm -rf ~'", Tool.ssh("b", "h; rm -rf ~", "o'x", 22, null).command());
    }

    @Test
    void leadingDashValuesCannotBecomeOptions() {
        assertEquals("ssh -- '-oProxyCommand=x'", Tool.ssh("b", "-oProxyCommand=x", null, 22, null).command());
        var cmd = Tool.ssh("b", "-oProxyCommand=x", null, 22, "-F/etc/passwd").command();
        assertEquals("ssh -i '-F/etc/passwd' -- '-oProxyCommand=x'", cmd);
    }
}
