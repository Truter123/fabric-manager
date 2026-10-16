package dev.kamil.fabric.ssh;

/** A host added in the app (not from ~/.ssh/config); user and identity are null when not given. */
public record AppHost(long id, String name, String hostName, String user, int port, String identity) {

    /** Display text such as user@hostname:port. */
    public String detail() {
        return (user == null ? "" : user + "@") + hostName + (port == 22 ? "" : ":" + port);
    }
}
