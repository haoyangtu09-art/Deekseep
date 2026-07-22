/** Host JSON codec singleton stand-in. */
public abstract class x94 {
    public static final Codec a = new Codec();

    public static final class Codec {
        public String c(Object serializer, Object value) throws Exception {
            return ((kv) value).toJson().toString();
        }

        public Object b(Object serializer, String value) throws Exception {
            return kv.fromJson(value);
        }
    }

    private x94() {}
}
