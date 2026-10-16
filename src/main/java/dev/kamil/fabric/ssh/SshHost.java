package dev.kamil.fabric.ssh;

/** One concrete alias from an ssh config; the optional fields are null when not given. */
public record SshHost(String alias, String hostName, String user, Integer port) {

    /** Display text such as user@hostname:port, with the missing parts left out. */
    public String detail() {
        var sb = new StringBuilder();
        if (user != null) {
            sb.append(user).append('@');
        }
        if (hostName != null) {
            sb.append(hostName);
        }
        if (port != null) {
            sb.append(':').append(port);
        }
        return sb.toString();
    }
}
