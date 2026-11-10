package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Providers.AppSettings;

/**
 * App settings belong to the browser (localStorage). It pushes them here on
 * load and on every change; they are kept in memory only, never on disk.
 */
public class RuntimeSettings {
    private volatile AppSettings settings = AppSettings.defaults();
    private volatile boolean received;

    public AppSettings get() {
        return settings;
    }

    public void set(AppSettings s) {
        settings = s;
        received = true;
    }

    /** Has a browser pushed its settings since the server started? */
    public boolean received() {
        return received;
    }
}
