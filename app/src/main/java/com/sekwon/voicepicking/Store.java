package com.sekwon.voicepicking;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Picking data, progress, step order and the words to speak. Shared by screen and service. */
public final class Store {
    private static Store instance;

    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    public static final class Step {
        public final String id, dest, code;
        public final int qty;
        Step(String dest, String code, int qty) {
            this.id = dest + "|" + code; this.dest = dest; this.code = code; this.qty = qty;
        }
    }

    public interface Listener { void changed(); }

    private final Context ctx;
    private final SharedPreferences sp;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private JSONObject wave;
    private JSONObject catalog;
    private final Set<String> done = new LinkedHashSet<>();
    private String cur = null;
    private String mode = "dest";
    private JSONObject settings;
    private String lastHeader = null;
    private String lastEvent = "";
    private boolean sessionOn = false;

    private Store(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("voicepick", Context.MODE_PRIVATE);
        load();
    }

    // ---------- persistence ----------
    private void load() {
        try {
            String w = sp.getString("wave", null), cat = sp.getString("catalog", null);
            if (w == null || cat == null) {
                JSONObject seed = new JSONObject(readAsset("seed.json"));
                wave = seed.getJSONObject("wave");
                catalog = seed.getJSONObject("catalog");
            } else {
                wave = new JSONObject(w);
                catalog = new JSONObject(cat);
            }
            JSONArray d = new JSONArray(sp.getString("done", "[]"));
            for (int i = 0; i < d.length(); i++) done.add(d.getString(i));
            cur = sp.getString("cur", null);
            mode = sp.getString("mode", "dest");
            settings = new JSONObject(sp.getString("settings", "{}"));
        } catch (Exception e) {
            wave = new JSONObject();
            catalog = new JSONObject();
            settings = new JSONObject();
            try { wave.put("code", ""); wave.put("dests", new JSONArray()); } catch (JSONException ignored) { }
        }
        if (!settings.has("rate")) put(settings, "rate", 1.1);
        if (!settings.has("brand")) put(settings, "brand", true);
        if (!settings.has("tail")) put(settings, "tail", false);
        if (!settings.has("dbl")) put(settings, "dbl", "repeat");
        Speaker.get(ctx).setRate((float) settings.optDouble("rate", 1.1));
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void save() {
        sp.edit()
                .putString("wave", wave.toString())
                .putString("catalog", catalog.toString())
                .putString("done", new JSONArray(done).toString())
                .putString("cur", cur)
                .putString("mode", mode)
                .putString("settings", settings.toString())
                .apply();
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (JSONException ignored) { }
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    private void fire() { for (Listener l : listeners) l.changed(); }
    public void nudge() { fire(); }

    // ---------- derived data ----------
    private JSONArray dests() {
        JSONArray a = wave.optJSONArray("dests");
        if (a == null) { a = new JSONArray(); put(wave, "dests", a); }
        return a;
    }

    private static LinkedHashMap<String, Integer> linesOf(JSONObject dest) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        JSONArray pages = dest.optJSONArray("pages");
        if (pages == null) return m;
        for (int p = 0; p < pages.length(); p++) {
            JSONArray items = pages.optJSONObject(p).optJSONArray("items");
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                String code = it.optString("code");
                int q = it.optInt("qty", 0);
                Integer old = m.get(code);
                m.put(code, old == null ? q : old + q);
            }
        }
        return m;
    }

    private List<String> productOrder() {
        LinkedHashMap<String, Integer> first = new LinkedHashMap<>();
        Map<String, Set<String>> succ = new HashMap<>();
        Map<String, Integer> indeg = new HashMap<>();
        int k = 0;
        JSONArray ds = dests();
        for (int d = 0; d < ds.length(); d++) {
            List<String> seq = new ArrayList<>(linesOf(ds.optJSONObject(d)).keySet());
            for (String c : seq) {
                if (!first.containsKey(c)) { first.put(c, k++); indeg.put(c, 0); succ.put(c, new LinkedHashSet<>()); }
            }
            for (int i = 1; i < seq.size(); i++) {
                String a = seq.get(i - 1), b = seq.get(i);
                if (!a.equals(b) && succ.get(a).add(b)) indeg.put(b, indeg.get(b) + 1);
            }
        }
        List<String> out = new ArrayList<>();
        List<String> avail = new ArrayList<>();
        for (String c : first.keySet()) if (indeg.get(c) == 0) avail.add(c);
        while (!avail.isEmpty()) {
            avail.sort((x, y) -> first.get(x) - first.get(y));
            String c = avail.remove(0);
            out.add(c);
            for (String n : succ.get(c)) {
                indeg.put(n, indeg.get(n) - 1);
                if (indeg.get(n) == 0) avail.add(n);
            }
        }
        if (out.size() < first.size()) {
            Set<String> seen = new HashSet<>(out);
            for (String c : first.keySet()) if (!seen.contains(c)) out.add(c);
        }
        return out;
    }

    public synchronized List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        JSONArray ds = dests();
        if ("product".equals(mode)) {
            List<String> names = new ArrayList<>();
            List<LinkedHashMap<String, Integer>> maps = new ArrayList<>();
            for (int d = 0; d < ds.length(); d++) {
                names.add(ds.optJSONObject(d).optString("name"));
                maps.add(linesOf(ds.optJSONObject(d)));
            }
            for (String code : productOrder()) {
                for (int d = 0; d < names.size(); d++) {
                    Integer q = maps.get(d).get(code);
                    if (q != null && q > 0) steps.add(new Step(names.get(d), code, q));
                }
            }
        } else {
            for (int d = 0; d < ds.length(); d++) {
                JSONObject dest = ds.optJSONObject(d);
                for (Map.Entry<String, Integer> e : linesOf(dest).entrySet()) {
                    if (e.getValue() > 0) steps.add(new Step(dest.optString("name"), e.getKey(), e.getValue()));
                }
            }
        }
        return steps;
    }

    private void ensureCur(List<Step> steps) {
        if (cur != null && !done.contains(cur)) {
            for (Step s : steps) if (s.id.equals(cur)) return;
        }
        cur = null;
        for (Step s : steps) if (!done.contains(s.id)) { cur = s.id; return; }
    }

    private static int indexOf(List<Step> steps, String id) {
        if (id == null) return -1;
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).id.equals(id)) return i;
        return -1;
    }

    public synchronized Step current() {
        List<Step> steps = steps();
        ensureCur(steps);
        int i = indexOf(steps, cur);
        return i < 0 ? null : steps.get(i);
    }

    public JSONObject info(String code) {
        JSONObject o = catalog.optJSONObject(code);
        if (o == null) {
            o = new JSONObject();
            put(o, "name", "상품코드 " + code);
        }
        return o;
    }

    // ---------- speech ----------
    static String shortDest(String n) {
        String s = n.replaceFirst("^BGF로지스", "").replaceFirst("^씨제이", "").replaceFirst("로지텍$", "");
        return s.isEmpty() ? n : s;
    }

    private String speakName(String name) {
        String s = name;
        if (!settings.optBoolean("brand", true)) s = s.replaceFirst("^[^)]*\\)", "");
        s = s.replaceFirst("(?i)^CJ\\)", "씨제이 ").replaceFirst("(?i)^PBICK\\)", "피빅 ").replace(")", " ")
                .replaceAll("(\\d+)\\s*[gG](?![a-zA-Z])", "$1그램").replaceAll("([가-힣])N$", "$1");
        return s.trim();
    }

    static Long dayDiff(String date) {
        if (date == null || date.isEmpty()) return null;
        try {
            return ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(date));
        } catch (Exception e) {
            return null;
        }
    }

    private String buildAnnouncement(boolean force) {
        List<Step> steps = steps();
        ensureCur(steps);
        int i = indexOf(steps, cur);
        if (i < 0) {
            int tot = 0;
            for (Step s : steps) tot += s.qty;
            lastHeader = null;
            return "피킹이 모두 끝났습니다. 총 " + tot + "박스.";
        }
        Step s = steps.get(i);
        JSONObject it = info(s.code);
        Long dd = dayDiff(it.optString("date"));
        String warn = (dd != null && dd <= 7) ? " 소비기한 확인." : "";
        String tail = "";
        if (settings.optBoolean("tail", false) && s.code.length() >= 4) {
            String t = s.code.substring(s.code.length() - 4);
            tail = " 코드 끝 " + t.charAt(0) + " " + t.charAt(1) + " " + t.charAt(2) + " " + t.charAt(3) + ".";
        }
        String name = speakName(it.optString("name"));
        if ("product".equals(mode)) {
            String key = "p:" + s.code;
            if (force || !key.equals(lastHeader)) {
                int rest = 0;
                for (Step x : steps) if (x.code.equals(s.code) && !done.contains(x.id)) rest += x.qty;
                lastHeader = key;
                return name + ", 총 " + rest + "박스." + tail + warn + " " + shortDest(s.dest) + ", " + s.qty + "박스.";
            }
            return shortDest(s.dest) + ", " + s.qty + "박스.";
        }
        String key = "d:" + s.dest;
        String head = (force || !key.equals(lastHeader)) ? "배송지 " + shortDest(s.dest) + ". " : "";
        lastHeader = key;
        return head + name + ", " + s.qty + "박스." + tail + warn;
    }

    private void announce(boolean force) { announce(force, null); }

    private void announce(boolean force, String prefix) {
        String text;
        synchronized (this) { text = buildAnnouncement(force); }
        Speaker.get(ctx).say(prefix == null ? text : prefix + " " + text);
    }

    private String notice = "";
    private long noticeAt = 0;

    /** When the step just finished closes its group (a destination or a product), the words to say. */
    private String[] groupDone(List<Step> steps, Step s) {
        boolean prod = "product".equals(mode);
        Set<String> left = new HashSet<>();
        for (Step x : steps) {
            String key = prod ? x.code : x.dest;
            boolean same = prod ? key.equals(s.code) : key.equals(s.dest);
            if (same && !done.contains(x.id)) return null;
            if (!done.contains(x.id)) left.add(key);
        }
        int n = left.size();
        String name = info(s.code).optString("name");
        String speech = prod ? speakName(name) + " 피킹 완료." : shortDest(s.dest) + " 배송지 완료.";
        String screen = "✓ " + (prod ? name + " 피킹 완료" : s.dest + " 배송지 완료");
        if (n > 0) {
            speech += prod ? " 남은 상품 " + n + "개." : " 남은 배송지 " + n + "곳.";
            screen += prod ? " · 남은 상품 " + n + "개" : " · 남은 배송지 " + n + "곳";
        }
        return new String[]{speech, screen};
    }

    // ---------- actions ----------
    public void next() {
        String prefix = null;
        synchronized (this) {
            List<Step> steps = steps();
            ensureCur(steps);
            int i = indexOf(steps, cur);
            if (i >= 0) {
                done.add(steps.get(i).id);
                String[] g = groupDone(steps, steps.get(i));
                if (g != null) { prefix = g[0]; notice = g[1]; noticeAt = System.currentTimeMillis(); }
                int j = -1;
                for (int k = i + 1; k < steps.size(); k++) if (!done.contains(steps.get(k).id)) { j = k; break; }
                if (j < 0) for (int k = 0; k < steps.size(); k++) if (!done.contains(steps.get(k).id)) { j = k; break; }
                cur = j < 0 ? null : steps.get(j).id;
                save();
            }
        }
        announce(false, prefix);
        fire();
    }

    public void prev() {
        boolean moved = false;
        synchronized (this) {
            List<Step> steps = steps();
            int i = indexOf(steps, cur);
            if (i < 0) i = steps.size();
            if (i > 0) {
                Step p = steps.get(i - 1);
                done.remove(p.id);
                cur = p.id;
                save();
                moved = true;
            }
        }
        announce(true);
        if (moved) fire();
    }

    public void repeat() {
        synchronized (this) { lastHeader = null; }
        announce(true);
    }

    public void jump(String id) {
        synchronized (this) { done.remove(id); cur = id; save(); }
        announce(true);
        fire();
    }

    public void setMode(String m, boolean speakNow) {
        synchronized (this) {
            if (m.equals(mode)) return;
            mode = m; cur = null; lastHeader = null; save();
        }
        if (speakNow) announce(true);
        fire();
    }

    public void startAnnounce() { synchronized (this) { lastHeader = null; } announce(true); }

    public synchronized void setEvent(String e) { lastEvent = e; }
    public synchronized void setSession(boolean on) { sessionOn = on; }
    public synchronized boolean session() { return sessionOn; }
    public synchronized String dbl() { return settings.optString("dbl", "repeat"); }

    public void setQty(int di, int pi, int ii, int q) {
        synchronized (this) {
            try {
                JSONObject it = dests().getJSONObject(di).getJSONArray("pages").getJSONObject(pi).getJSONArray("items").getJSONObject(ii);
                it.put("qty", Math.max(0, q));
                save();
            } catch (JSONException ignored) { }
        }
        fire();
    }

    public void moveDest(int i, int dir) {
        synchronized (this) {
            JSONArray a = dests();
            int j = i + dir;
            if (i < 0 || j < 0 || i >= a.length() || j >= a.length()) return;
            try {
                Object x = a.get(i);
                a.put(i, a.get(j));
                a.put(j, x);
            } catch (JSONException ignored) { }
            save();
        }
        fire();
    }

    public void setSetting(String k, String v) {
        synchronized (this) {
            switch (k) {
                case "rate":
                    double r = Double.parseDouble(v);
                    put(settings, "rate", r);
                    Speaker.get(ctx).setRate((float) r);
                    break;
                case "brand":
                case "tail":
                    put(settings, k, Boolean.parseBoolean(v));
                    break;
                case "dbl":
                    put(settings, k, v);
                    break;
                default:
                    return;
            }
            save();
        }
        fire();
    }

    public void resetDone() {
        synchronized (this) { done.clear(); cur = null; lastHeader = null; save(); }
        fire();
    }

    public void clearWave() {
        synchronized (this) {
            wave = new JSONObject();
            put(wave, "code", "");
            put(wave, "dests", new JSONArray());
            catalog = new JSONObject();
            done.clear(); cur = null; lastHeader = null; save();
        }
        fire();
    }

    private static String pageSig(JSONArray items) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            b.append(it.optString("code")).append(':').append(it.optInt("qty")).append(',');
        }
        return b.toString();
    }

    /** Takes the data copied from the claude.ai page. Returns a message for the screen. */
    public String importData(String raw, boolean replace) {
        String msg;
        synchronized (this) {
            try {
                int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
                if (a < 0 || b <= a) return "ERR:붙여넣은 내용이 데이터 형식이 아닙니다.";
                JSONObject d = new JSONObject(raw.substring(a, b + 1));
                JSONObject nw = d.optJSONObject("wave");
                JSONObject nc = d.optJSONObject("catalog");
                if (nw == null || nc == null || nw.optJSONArray("dests") == null) return "ERR:보이스 피킹 데이터가 아닙니다.";
                if (replace) {
                    wave = new JSONObject();
                    put(wave, "code", nw.optString("code", ""));
                    put(wave, "dests", new JSONArray());
                    catalog = new JSONObject();
                    done.clear(); cur = null;
                }
                java.util.Iterator<String> keys = nc.keys();
                while (keys.hasNext()) { String k = keys.next(); catalog.put(k, nc.get(k)); }
                int pages = 0;
                JSONArray nds = nw.getJSONArray("dests");
                for (int i = 0; i < nds.length(); i++) {
                    JSONObject nd = nds.getJSONObject(i);
                    String name = nd.optString("name");
                    JSONObject target = null;
                    JSONArray ds = dests();
                    for (int j = 0; j < ds.length(); j++) if (ds.getJSONObject(j).optString("name").equals(name)) target = ds.getJSONObject(j);
                    if (target == null) {
                        target = new JSONObject();
                        target.put("name", name);
                        target.put("pages", new JSONArray());
                        ds.put(target);
                    }
                    JSONArray tp = target.getJSONArray("pages");
                    Set<String> sigs = new HashSet<>();
                    for (int j = 0; j < tp.length(); j++) sigs.add(pageSig(tp.getJSONObject(j).optJSONArray("items")));
                    JSONArray np = nd.optJSONArray("pages");
                    if (np == null) continue;
                    for (int j = 0; j < np.length(); j++) {
                        JSONObject p = np.getJSONObject(j);
                        JSONArray items = p.optJSONArray("items");
                        if (items == null || sigs.contains(pageSig(items))) continue;
                        JSONObject copy = new JSONObject();
                        copy.put("plt", p.opt("plt"));
                        JSONArray ci = new JSONArray();
                        for (int x = 0; x < items.length(); x++) {
                            JSONObject it = items.getJSONObject(x);
                            JSONObject c = new JSONObject();
                            c.put("code", it.optString("code"));
                            c.put("qty", it.optInt("qty", 0));
                            ci.put(c);
                        }
                        copy.put("items", ci);
                        tp.put(copy);
                        pages++;
                    }
                }
                if (wave.optString("code").isEmpty()) put(wave, "code", nw.optString("code", ""));
                lastHeader = null;
                save();
                msg = pages + "페이지를 넣었습니다. 수량을 한 번 훑어보세요.";
            } catch (Exception e) {
                return "ERR:데이터를 읽지 못했습니다. 복사 버튼으로 받은 내용을 그대로 붙여넣어 주세요.";
            }
        }
        fire();
        return msg;
    }

    // ---------- state for the screen ----------
    public synchronized String stateJson() {
        JSONObject o = new JSONObject();
        try {
            List<Step> steps = steps();
            ensureCur(steps);
            JSONArray sa = new JSONArray();
            for (Step s : steps) {
                JSONObject x = new JSONObject();
                x.put("id", s.id); x.put("dest", s.dest); x.put("code", s.code); x.put("qty", s.qty);
                sa.put(x);
            }
            o.put("steps", sa);
            o.put("done", new JSONArray(done));
            o.put("cur", cur == null ? JSONObject.NULL : cur);
            o.put("mode", mode);
            o.put("wave", wave);
            o.put("catalog", catalog);
            o.put("settings", settings);
            o.put("event", lastEvent);
            o.put("notice", notice);
            o.put("noticeAge", System.currentTimeMillis() - noticeAt);
            o.put("session", sessionOn);
            String p = Speaker.get(ctx).problem();
            o.put("speechProblem", p == null ? "" : p);
        } catch (JSONException ignored) { }
        return o.toString();
    }
}
