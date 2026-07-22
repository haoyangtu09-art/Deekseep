import java.util.concurrent.CancellationException;

public final class c74 implements p64 {
    private boolean active;
    private CancellationException failure;

    public c74(boolean active) { this.active = active; }

    @Override public void b(CancellationException error) {
        failure = error == null ? new CancellationException("cancelled") : error;
        active = false;
    }

    @Override public boolean d() { return active; }
    @Override public boolean isCancelled() { return failure != null; }
    @Override public CancellationException u() { return failure; }
    @Override public n02 L(n02 value) { return value; }
    @Override public Object P(Object key) { return null; }
    @Override public Object v(Object operation, Object initial) { return initial; }
    @Override public n02 y(Object key) { return this; }
}
