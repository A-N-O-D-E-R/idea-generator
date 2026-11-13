package bio.anode.ideagenerator.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;

/** ideagen.auth: none (one local tenant, loopback only) or oauth. */
@Slf4j
public record Mode(boolean oauth) {
    public static Mode of(String auth, String address) {
        return of(auth, address, false);
    }

    /**
     * publishedOnLoopback: the operator states the process is only reachable through a loopback port mapping
     * (docker-compose.yml publishes 127.0.0.1:8300 only), so a container may bind 0.0.0.0 without a login.
     */
    public static Mode of(String auth, String address, boolean publishedOnLoopback) {
        if (auth.equals("oauth")) return new Mode(true);
        if (!auth.equals("none")) throw new IllegalStateException("ideagen.auth must be none or oauth, not " + auth);
        boolean loopback;
        try {
            loopback = address != null && !address.isBlank() && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            loopback = false;
        }
        if (!loopback && publishedOnLoopback) {
            log.warn("ideagen.auth=none on {}: no login. Only safe because the port is published on "
                    + "127.0.0.1 alone (ideagen.none-published-on-loopback=true); never map it to another interface.", address);
        } else if (!loopback) {
            throw new IllegalStateException("ideagen.auth=none has no login: server.address must be loopback (127.0.0.1), not '"
                    + address + "'. Use ideagen.auth=oauth to serve other machines.");
        }
        return new Mode(false);
    }
}
