package com.gl.cznnuke;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;

public class NukeMod implements IXposedHookLoadPackage {

    public static final String TAG = "CZNUKE";

    private static final String[] PKG_HINTS = {"chaoszero", "smilegate", "com.tencent.czn"};

    private static final String[] CLS_STOVE = {
            "kr.supercreative.stovesdk.SuperStove",
            "kr.supercreative.stovesdk.Stove",
            "kr.supercreative.ssr.SuperStove",
            "com.smilegate.stovesdk.SuperStove",
            "com.stove.sdk.SuperStove",
    };

    private static final String[] CLS_GS = {
            "kr.supercreative.stovesdk.GameSecrity",
            "kr.supercreative.stovesdk.GameSecurity",
            "kr.supercreative.ssr.GameSecrity",
            "com.smilegate.stovesdk.GameSecrity",
            "com.stove.security.GameSecrity",
    };

    private static final String[] CLS_ENV = {
            "kr.supercreative.ssr.BaseActivity",
            "kr.supercreative.ssr.SuperActivity",
    };

    private static final String[] CLS_LIFECYCLE = {
            "kr.supercreative.ssr.SuperActivity",
            "kr.supercreative.ssr.BaseActivity",
    };

    private static final String[] LIFECYCLE_METHODS = {
            "onResume", "onStart", "onRestart", "onNewIntent", "onCreate",
    };

    private static final String CLS_COCOS = "org.cocos2dx.lib.Cocos2dxActivity";

    private static final String[] GS_KILL = {
            "init", "startsui", "start", "setuserinfo", "querycs",
            "dispatchtouchevent", "resume", "pause",
    };

    private static final List<Class<?>> sGuard = new ArrayList<>();
    private static final java.util.Set<String> sHooked = new java.util.HashSet<>();
    private static boolean sInstalled = false;
    private static volatile boolean sWatchdog = false;
    private static volatile String sPkg = "";
    private static volatile String sVer = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!matchPkg(lp.packageName)) {
            return;
        }
        if (sInstalled) {
            return;
        }
        sInstalled = true;

        sPkg = lp.packageName == null ? "" : lp.packageName;

        log("ATTACH proc=" + lp.processName + " pkg=" + sPkg + " ver=" + sVer
                + " api=" + android.os.Build.VERSION.SDK_INT + " pid=" + android.os.Process.myPid());

        ClassLoader cl = lp.classLoader;

        boolean env = hookEnvGate(cl);
        boolean stove = hookStoveGate(cl);
        boolean gs = hookGameSecrity(cl);
        boolean life = hookLifecycle(cl);

        log("layers: env=" + env + " stove=" + stove + " gs=" + gs + " lifecycle=" + life
                + " guarded=" + sGuard.size());

        if (!stove && !gs) {
            log("no sdk class matched -> fallback to native env seam");
            hookCocosEnv(cl);
        }

        startWatchdog();
    }

    static boolean matchPkg(String pkg) {
        if (pkg == null) {
            return false;
        }
        String p = pkg.toLowerCase();
        for (String h : PKG_HINTS) {
            if (p.contains(h)) {
                return true;
            }
        }
        return false;
    }

    private boolean hookEnvGate(ClassLoader cl) {
        int n = 0;
        for (String cn : CLS_ENV) {
            Class<?> c = load(cn, cl);
            if (c == null) {
                continue;
            }
            n += hookDeclared(c, "packageenvironment", false, new EnvGate());
        }
        return n > 0;
    }

    public static class EnvGate extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (p.args == null || p.args.length == 0 || p.args[0] == null) {
                    return;
                }
                String key = String.valueOf(p.args[0]);
                if (isRhcoreSwitchKey(key)) {
                    p.setResult("false");
                    log("env gate: " + key + " -> false");
                } else if (key.toLowerCase().contains("rhcore")) {
                    log("env observed: " + key + " = " + (p.args.length > 1 ? p.args[1] : "?"));
                }
            } catch (Throwable t) {
                log("E EnvGate " + t);
            }
        }
    }

    static boolean isRhcoreSwitchKey(String key) {
        String k = key.toLowerCase();
        if (!k.contains("rhcore")) {
            return false;
        }
        return k.contains("enable") || k.contains("enabled") || k.contains("switch")
                || k.contains("flag") || k.contains(".use") || k.endsWith(".on");
    }

    private boolean hookStoveGate(ClassLoader cl) {
        for (String cn : CLS_STOVE) {
            Class<?> c = load(cn, cl);
            if (c == null) {
                continue;
            }
            log("found stove class: " + cn);
            addGuard(c);
            hookDeclared(c, "oncreate", false, new StoveOnCreate());
            hookDeclared(c, "rhcore", false, new GateClose());
            return true;
        }
        return false;
    }

    public static class StoveOnCreate extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                int n = killRhcoreFields(p.thisObject.getClass());
                log("SuperStove.onCreate -> cleared " + n + " rhcore field(s)");
            } catch (Throwable t) {
                log("E StoveOnCreate " + t);
            }
        }
    }

    public static class GateClose extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                int n = killRhcoreFields(p.thisObject.getClass());
                log("gate method hit -> cleared " + n + " rhcore field(s)");
            } catch (Throwable t) {
                log("E GateClose " + t);
            }
        }
    }

    private boolean hookLifecycle(ClassLoader cl) {
        int n = 0;
        for (String cn : CLS_LIFECYCLE) {
            Class<?> c = load(cn, cl);
            if (c == null) {
                continue;
            }
            for (String name : LIFECYCLE_METHODS) {
                n += hookDeclared(c, name, true, new LifeGuard(name));
            }
        }
        if (n > 0) {
            log("lifecycle guards installed: " + n);
        }
        return n > 0;
    }

    public static class LifeGuard extends XC_MethodHook {
        private final String name;

        public LifeGuard(String n) {
            this.name = n;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                int n = enforceAll();
                if (n > 0) {
                    log("lifecycle " + name + " -> re-asserted " + n + " field(s)");
                }
            } catch (Throwable t) {
                log("E LifeGuard " + t);
            }
        }
    }

    private void startWatchdog() {
        if (sWatchdog) {
            return;
        }
        sWatchdog = true;
        Thread t = new Thread(new Watchdog(), "cznuke-wd");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable t3) {
            log("E startWatchdog " + t3);
        }
    }

    public static class Watchdog implements Runnable {
        @Override
        public void run() {
            int tick = 0;
            while (true) {
                try {
                    Thread.sleep(500);
                    tick++;
                    if (sGuard.isEmpty()) {
                        continue;
                    }
                    int n = enforceAll();
                    if (n > 0) {
                        log("RE-ENABLE detected at runtime -> re-asserted " + n + " field(s)");
                    }
                    if (tick % 120 == 0 && mapped("rhcore")) {
                        log("ALARM librhcore mapped at runtime");
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t2) {
                    log("E watchdog " + t2);
                }
            }
        }
    }

    static int enforceAll() {
        int n = 0;
        for (Class<?> c : sGuard) {
            n += killRhcoreFields(c);
        }
        return n;
    }

    static void addGuard(Class<?> c) {
        if (!sGuard.contains(c)) {
            sGuard.add(c);
        }
    }

    static boolean mapped(String needle) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains(needle)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    static int killRhcoreFields(Class<?> c) {
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fs;
            try {
                fs = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fs) {
                if (f.getType() != boolean.class && f.getType() != Boolean.class) {
                    continue;
                }
                if (!f.getName().toLowerCase().contains("rhcore")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.getBoolean(null)) {
                        f.setBoolean(null, false);
                        n++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }

    private boolean hookGameSecrity(ClassLoader cl) {
        for (String cn : CLS_GS) {
            Class<?> c = load(cn, cl);
            if (c == null) {
                continue;
            }
            log("found gs class: " + cn);
            addGuard(c);
            LinkedHashSet<String> gcs = new LinkedHashSet<>();
            LinkedHashSet<String> kill = new LinkedHashSet<>();
            for (Method m : allMethods(c)) {
                String lname = m.getName().toLowerCase();
                if (lname.equals("gcs")) {
                    gcs.add(m.getName());
                    continue;
                }
                for (String k : GS_KILL) {
                    if (lname.equals(k)) {
                        kill.add(m.getName());
                        break;
                    }
                }
            }
            int n = 0;
            for (String nm : gcs) {
                n += hookDeclared(c, nm, true, new GcsFake());
            }
            for (String nm : kill) {
                n += hookDeclared(c, nm, true, new VoidNoop(nm));
            }
            log("gs hooks installed: " + n);
            return n > 0;
        }
        return false;
    }

    public static class GcsFake extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                JSONArray a = new JSONArray();
                a.put(sPkg);
                a.put(ver());
                a.put(userInfo());
                a.put("");
                p.setResult(a);
                log("GCS() -> fake " + a);
            } catch (Throwable t) {
                log("E GcsFake " + t);
            }
        }
    }

    public static class VoidNoop extends XC_MethodHook {
        private final String name;

        public VoidNoop(String n) {
            this.name = n;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            log(name + "() suppressed");
            p.setResult(defaultOf(p.method));
        }
    }

    static Object defaultOf(Member m) {
        if (!(m instanceof Method)) {
            return null;
        }
        Class<?> r = ((Method) m).getReturnType();
        if (r == boolean.class) {
            return Boolean.FALSE;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == short.class) {
            return (short) 0;
        }
        if (r == byte.class) {
            return (byte) 0;
        }
        if (r == char.class) {
            return (char) 0;
        }
        if (r == float.class) {
            return 0f;
        }
        if (r == double.class) {
            return 0d;
        }
        return null;
    }

    private void hookCocosEnv(ClassLoader cl) {
        Class<?> c = load(CLS_COCOS, cl);
        if (c == null) {
            log("E fallback: cocos class not found");
            return;
        }
        try {
            XposedBridge.hookAllMethods(c, "getenv", new EnvGate());
            log("hook Cocos2dxActivity.getenv (fallback)");
        } catch (Throwable t) {
            log("E fallback getenv " + t);
        }
    }

    static int hookDeclared(Class<?> root, String nameOrNeedle, boolean exact, XC_MethodHook cb) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int n = 0;
        for (Method m : allMethods(root)) {
            String mn = m.getName();
            boolean hit = exact ? mn.equals(nameOrNeedle) : mn.toLowerCase().contains(nameOrNeedle);
            if (!hit) {
                continue;
            }
            Class<?> dc = m.getDeclaringClass();
            String dcn = dc.getName();
            if (!appClass(dcn)) {
                continue;
            }
            String key = dcn + "#" + mn;
            if (!seen.add(key)) {
                continue;
            }
            if (!sHooked.add(key)) {
                continue;
            }
            try {
                XposedBridge.hookAllMethods(dc, mn, cb);
                log("hook " + key);
                n++;
            } catch (Throwable t) {
                log("E hook " + key + " " + t);
            }
        }
        return n;
    }

    static boolean appClass(String n) {
        return n.startsWith("kr.supercreative.") || n.startsWith("com.smilegate.")
                || n.startsWith("com.stove.") || n.startsWith("com.tencent.czn");
    }

    static Class<?> load(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static List<Method> allMethods(Class<?> c) {
        ArrayList<Method> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] ms;
            try {
                ms = k.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method m : ms) {
                out.add(m);
            }
        }
        return out;
    }

    static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String appVersion() {
        try {
            Context c = appContext();
            if (c == null) {
                return "";
            }
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    static String ver() {
        String v = appVersion();
        return v.isEmpty() ? sVer : v;
    }

    static String userInfo() {
        try {
            Context c = appContext();
            if (c == null) {
                return "";
            }
            String v = c.getSharedPreferences("rhcore", 0).getString("userInfo", "");
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    static void log(String s) {
        Log.i(TAG, s);
    }
}
