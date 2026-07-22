package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class AccountCredentialCodecRegressionTest {

    public static void main(String[] args) throws Exception {
        rawCredentialRoundTripsWithoutDroppingUnknownFields();
        multiAccountEnvelopeRoundTripsAndNamesTheFile();
        trailingContentIsRejected();
        missingOrWrongTypedRequiredFieldsAreRejected();
        oneBadEntryRejectsTheWholeBatch();
        duplicateIdsAreRejected();
        System.out.println("AccountCredentialCodecRegressionTest OK");
    }

    private static void rawCredentialRoundTripsWithoutDroppingUnknownFields() throws Exception {
        JSONObject credential = credential("account-00000001", "Alice");
        credential.put("future_field", new JSONObject().put("kept", true));
        List<AccountCredentialCodec.Entry> parsed =
                AccountCredentialCodec.parseImport(credential.toString());
        check(parsed.size() == 1, "raw credential count");
        JSONObject retained = new JSONObject(parsed.get(0).credentialJson);
        check(retained.getJSONObject("future_field").getBoolean("kept"), "unknown field retained");
        check("Alice".equals(parsed.get(0).name), "profile name derived");
    }

    private static void multiAccountEnvelopeRoundTripsAndNamesTheFile() throws Exception {
        List<AccountCredentialCodec.Entry> entries = new ArrayList<>();
        JSONObject a = credential("account-00000001", "Alice");
        JSONObject b = credential("account-00000002", "Bob");
        entries.add(AccountCredentialCodec.entryFromCredential("Alice", a, 1));
        entries.add(AccountCredentialCodec.entryFromCredential("Bob", b, 2));
        String exported = AccountCredentialCodec.buildExport(entries);
        List<AccountCredentialCodec.Entry> reparsed = AccountCredentialCodec.parseImport(exported);
        check(reparsed.size() == 2, "envelope count");
        check("account-00000002".equals(reparsed.get(1).id), "second id");
        String fileName = AccountCredentialCodec.suggestFileName(reparsed);
        check(fileName.contains("Alice") && fileName.contains("Bob"), "filename uses account names");
        check(fileName.endsWith(".txt"), "filename extension");
    }

    private static void trailingContentIsRejected() throws Exception {
        expectFailure(credential("account-00000001", "Alice").toString() + " trailing",
                "trailing JSON content");
    }

    private static void missingOrWrongTypedRequiredFieldsAreRejected() throws Exception {
        JSONObject missing = credential("account-00000001", "Alice");
        missing.remove("token");
        expectFailure(missing.toString(), "missing token");

        JSONObject wrong = credential("account-00000001", "Alice");
        wrong.put("need_birthday", "false");
        expectFailure(wrong.toString(), "wrong boolean type");

        JSONObject decimal = credential("account-00000001", "Alice");
        decimal.put("status", 1.5d);
        expectFailure(decimal.toString(), "non-integral status");
    }

    private static void oneBadEntryRejectsTheWholeBatch() throws Exception {
        JSONArray array = new JSONArray();
        array.put(credential("account-00000001", "Alice"));
        JSONObject invalid = credential("account-00000002", "Bob");
        invalid.remove("chat_status");
        array.put(invalid);
        expectFailure(array.toString(), "one bad entry rejects batch");
    }

    private static void duplicateIdsAreRejected() throws Exception {
        JSONArray array = new JSONArray();
        array.put(credential("account-00000001", "Alice"));
        array.put(credential("account-00000001", "Alice copy"));
        expectFailure(array.toString(), "duplicate id");
    }

    private static JSONObject credential(String id, String name) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("token", "token-abcdefghijklmnopqrstuvwxyz-0123456789");
        o.put("email", JSONObject.NULL);
        o.put("mobile_number", JSONObject.NULL);
        o.put("status", 1);
        o.put("chat_status", new JSONObject().put("enabled", true));
        o.put("id_profiles", new JSONArray().put(new JSONObject()
                .put("provider", "WECHAT").put("name", name)));
        o.put("need_birthday", false);
        return o;
    }

    private static void expectFailure(String raw, String label) throws Exception {
        boolean failed = false;
        try {
            AccountCredentialCodec.parseImport(raw);
        } catch (AccountCredentialCodec.FormatException expected) {
            failed = true;
        }
        check(failed, label);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
