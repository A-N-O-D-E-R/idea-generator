package bio.anode.ideagenerator;

/** An error with an HTTP status and a stable code; the API answers {"error": message, "code": code}. */
public class AppError extends RuntimeException {
    public final int status;
    public final String code;

    public AppError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** e?.code for any error the engine may see. */
    static String codeOf(Throwable e, String fallback) {
        if (e instanceof AppError a) return a.code;
        if (e instanceof Providers.ProviderError p) return p.code;
        return fallback;
    }

    /** e?.message ?? String(e) */
    static String messageOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
