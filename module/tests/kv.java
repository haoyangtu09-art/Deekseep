import org.json.JSONObject;

/** Minimal static DeepSeek message with the obfuscated host API used by ResponsePreserver. */
public final class kv {
    private final int id;
    private final String role;
    private final String status;
    private final String quasiStatus;
    private final sl8 row;

    public kv(int id, String role, String status, String quasiStatus, String fragments) {
        this.id = id;
        this.role = role;
        this.status = status;
        this.quasiStatus = quasiStatus;
        this.row = new sl8();
        row.a = id;
        row.b = id > 1 ? Integer.valueOf(id - 1) : null;
        row.c = role;
        row.d = Boolean.FALSE;
        row.e = status;
        row.f = 123.0d;
        row.g = quasiStatus;
        row.h = 7;
        row.i = true;
        row.j = false;
        row.k = "test-extra";
        row.l = fragments;
        row.m = "test-tail";
    }

    public int u() { return id; }
    public String A() { return role; }
    public String D() { return status; }
    public String x() { return quasiStatus; }
    public sl8 O() { return row; }
    public kv a() { return this; }

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("role", role)
                .put("status", status)
                .put("quasi_status", quasiStatus)
                .put("fragments", row.l);
    }

    static kv fromJson(String value) throws Exception {
        JSONObject json = new JSONObject(value);
        return new kv(json.getInt("id"), json.getString("role"),
                json.optString("status", null), json.optString("quasi_status", null),
                json.getString("fragments"));
    }
}
