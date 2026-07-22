/** Minimal DeepSeek host-session stand-in for the native deletion bridge regression. */
public final class tp {
    public final String a;
    public double c;
    private final boolean pinned;

    public tp(String sid) {
        this(sid, 0d, false);
    }

    public tp(String sid, double updatedAt, boolean pinned) {
        this.a = sid;
        this.c = updatedAt;
        this.pinned = pinned;
    }

    public boolean h() {
        return pinned;
    }
}
