package com.hongcha.tesla;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 백그라운드 상태 감시 서비스.
 * - 2분마다 브릿지의 /api/state를 GET (캐시라도 OK)
 * - 상태 변화 감지 → 알림 발생
 *
 * 1단계 구현: 신규 소프트웨어 알림
 * (다음 단계에서 확장 예정: 나우바 라이브 알림, 트렁크/공조/충전 완료 등)
 */
public class TeslaWatchService extends Service {

    private static final String TAG = "TeslaWatch";

    // 알림 채널
    static final String CH_ONGOING   = "tesla_ongoing";      // 상태 칩(라이브)
    static final String CH_KEEPALIVE = "tesla_keepalive";    // 포어그라운드 유지용(최소)
    static final String CH_EVENT     = "tesla_event";        // 이벤트성 알림
    static final int NID_ONGOING = 1;   // 포어그라운드 keepalive
    static final int NID_LIVE    = 2;   // 상태 칩(승격 대상)
    static final int NID_SW      = 100;
    static final int NID_TRUNK   = 101;
    static final int NID_FRUNK   = 102;
    static final int NID_CHG_DONE = 103;

    // 상태 저장 (중복 알림 방지)
    private static final String PREFS = "tesla_state";
    private SharedPreferences prefs;

    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService pendingScheduler;
    private String base = "", key = "";

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        base = getString(R.string.bridge_base);
        key  = getString(R.string.bridge_key);
        createChannels();
        // 라이브(승격) 알림 = 포어그라운드 알림 (colorized는 fg에서만 적용됨)
        startForeground(NID_ONGOING, buildIdleNotification());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // 자체 스케줄링: 주행 중엔 30초, 아니면 120초
        scheduler.schedule(new Runnable() {
            @Override public void run() {
                pollOnce();
                long delay = (curLive == LIVE_DRIVE) ? 30 : 120;
                if (scheduler != null && !scheduler.isShutdown())
                    scheduler.schedule(this, delay, TimeUnit.SECONDS);
            }
        }, 3, TimeUnit.SECONDS);

        // 원격 알림 테스트: 10초마다 큐 확인 (별도 스레드)
        pendingScheduler = Executors.newSingleThreadScheduledExecutor();
        pendingScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { checkPending(); }
        }, 5, 10, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("test")) fireTest(intent.getStringExtra("test"));
        return START_STICKY;
    }

    /** 테스트: 샘플 데이터로 각 알림 발생 */
    private void fireTest(String w) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || w == null) return;
        long t = System.currentTimeMillis();
        switch (w) {
            case "sw":     notifyEvent(NID_SW, "신규 소프트웨어", "2024.44.25 다운로드 가능", android.R.drawable.stat_sys_download); break;
            case "trunk":  notifyEvent(NID_TRUNK, "트렁크 열림", "트렁크가 열려 있습니다", R.drawable.ic_stat_trunk); break;
            case "frunk":  notifyEvent(NID_FRUNK, "프렁크 열림", "프렁크가 열려 있습니다", R.drawable.ic_stat_frunk); break;
            case "chgdone":notifyEvent(NID_CHG_DONE, "충전 완료 되었습니다",
                                "걸린 시간 3시간 15분 · 32.4kWh · 9,169원", R.drawable.ic_stat_charge); break;
            case "drive":  nm.notify(NID_ONGOING, buildLive("테슬라", "운전 중",
                                R.drawable.ic_stat_drive, t, false, "운전 중")); break;
            case "charge": nm.notify(NID_ONGOING, buildLive("테슬라 충전", "완충까지",
                                R.drawable.ic_stat_charge, t + 45 * 60000L, true, "충전 중")); break;
            case "hvac":   nm.notify(NID_ONGOING, buildLive("테슬라 공조", "에어컨 가동 중",
                                R.drawable.ic_stat_snow, 0, false, "에어컨 가동")); break;
            case "hvacdone":nm.notify(NID_ONGOING, buildLive("테슬라 공조", "에어컨 완료",
                                R.drawable.ic_stat_snow, 0, false, "에어컨 완료")); break;
            case "sentry": nm.notify(NID_ONGOING, buildLive("테슬라", "센트리모드 켜짐",
                                R.drawable.ic_stat_sentry, 0, false, "센트리")); break;
            case "park":   nm.notify(NID_ONGOING, buildIdleNotification()); break;
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        if (pendingScheduler != null) pendingScheduler.shutdownNow();
        super.onDestroy();
    }

    /** 브릿지 알림 테스트 큐 확인 → 해당 알림 발생 */
    private void checkPending() {
        try {
            String body = httpGet(base + "/api/notify_pending?key=" + key);
            JSONObject j = new JSONObject(body);
            org.json.JSONArray arr = j.optJSONArray("which");
            if (arr != null) for (int i = 0; i < arr.length(); i++) fireTest(arr.optString(i));
        } catch (Exception e) { /* 무시 */ }
    }

    // ── 알림 채널 ──
    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ongoing = new NotificationChannel(
                    CH_ONGOING, "차량 상태(상시)", NotificationManager.IMPORTANCE_LOW);
            ongoing.setDescription("나우바 등 상시 표시용");
            ongoing.setShowBadge(false);

            NotificationChannel keepalive = new NotificationChannel(
                    CH_KEEPALIVE, "백그라운드 유지", NotificationManager.IMPORTANCE_MIN);
            keepalive.setDescription("서비스 유지용(최소 표시)");
            keepalive.setShowBadge(false);

            NotificationChannel event = new NotificationChannel(
                    CH_EVENT, "차량 이벤트", NotificationManager.IMPORTANCE_DEFAULT);
            event.setDescription("소프트웨어 업데이트, 트렁크 열림, 충전 완료 등");

            nm.createNotificationChannel(ongoing);
            nm.createNotificationChannel(keepalive);
            nm.createNotificationChannel(event);
        }
    }

    /** 포어그라운드 서비스 유지용 최소 알림 (승격 안 함) */
    private Notification buildKeepalive() {
        return new Notification.Builder(this, CH_KEEPALIVE)
                .setSmallIcon(R.drawable.ic_stat_park)
                .setContentTitle("테슬라")
                .setContentText("백그라운드 상태 감시")
                .setOngoing(true)
                .build();
    }

    private PendingIntent openAppIntent() {
        Intent it = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 주차 상태도 상태 칩으로 (승격형) */
    private Notification buildIdleNotification() {
        return buildLive("테슬라", "주차 됨", R.drawable.ic_stat_park, 0, false, "주차");
    }

    /** 라이브 업데이트(상태 칩) 알림 — Android 16(API36) setShortCriticalText + chronometer
     *  chip: 상태바 칩에 표시할 짧은 텍스트(시간·정보). chronoBase>0이면 타이머. */
    private Notification buildLive(String title, String text, int icon,
                                   long chronoBase, boolean countDown, String chip) {
        Notification.Builder b = new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .setColor(Color.parseColor("#e82127"))
                .setColorized(true);
        if (chronoBase > 0) {
            b.setUsesChronometer(true).setWhen(chronoBase).setShowWhen(true);
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(countDown);
        }
        // Android 16(API36) 라이브 업데이트: 승격 요청 + 짧은 상태 텍스트 + 진행형 스타일
        if (Build.VERSION.SDK_INT >= 36) {
            if (chip != null) b.setShortCriticalText(chip);
            applyProgressStyle(b);
            // 핵심: 승격 요청 (EXTRA_REQUEST_PROMOTED_ONGOING)
            android.os.Bundle ex = new android.os.Bundle();
            ex.putBoolean("android.requestPromotedOngoing", true);
            b.addExtras(ex);
            Notification n0 = b.build();
            boolean p = false;
            try { p = n0.hasPromotableCharacteristics(); } catch (Exception ignore) {}
            b.setContentText(text + "  [승격:" + (p ? "O" : "X") + "]");
        }
        return b.build();
    }

    /** ProgressStyle 적용 (API36+ 전용, 별도 메서드로 격리) */
    private void applyProgressStyle(Notification.Builder b) {
        Notification.ProgressStyle ps = new Notification.ProgressStyle();
        Notification.ProgressStyle.Segment seg = new Notification.ProgressStyle.Segment(100);
        seg.setColor(Color.parseColor("#e82127"));
        ps.addProgressSegment(seg);
        ps.setProgress(50);
        b.setStyle(ps);
    }

    // 상태 카테고리
    private static final int LIVE_IDLE = 0, LIVE_SENTRY = 1, LIVE_HVAC = 2, LIVE_CHARGE = 3, LIVE_DRIVE = 4;
    private int curLive = -1;
    private long chronoBase = 0;   // 진행 중 상태의 시작 시각(elapsedRealtime)

    // ── 폴링 ──
    private void pollOnce() {
        try {
            String body = httpGet(base + "/api/state?key=" + key);
            JSONObject j = new JSONObject(body);
            JSONObject resp = j.optJSONObject("response");
            if (resp == null) return;
            JSONObject vs = resp.optJSONObject("vehicle_state");
            JSONObject cs = resp.optJSONObject("charge_state");
            JSONObject cl = resp.optJSONObject("climate_state");
            JSONObject ds = resp.optJSONObject("drive_state");

            if (vs != null) {
                handleSoftwareUpdate(vs);
                handleTrunkFrunk(vs);
            }
            handleChargeComplete(cs, j);
            updateLiveNotification(cs, cl, ds, vs);
        } catch (Exception e) {
            Log.d(TAG, "poll error: " + e.getMessage());
        }
    }

    private void updateLiveNotification(JSONObject cs, JSONObject cl, JSONObject ds, JSONObject vs) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 주행 판정: shift_state = D/R/N 이거나 속도>0 이거나 파워!=0
        boolean driving = false;
        if (ds != null) {
            String shift = ds.optString("shift_state", "");
            int speed = ds.optInt("speed", -1);
            int power = ds.optInt("power", 0);
            if (shift.equals("D") || shift.equals("R") || shift.equals("N")) driving = true;
            else if (speed > 0) driving = true;
            else if (Math.abs(power) > 1) driving = true;   // 회생/추진 중
        }

        // 충전 중?
        boolean charging = cs != null && "Charging".equals(cs.optString("charging_state", ""));
        int minLeft = cs != null ? cs.optInt("minutes_to_full_charge", 0) : 0;

        // 공조 상태
        boolean climateOn = cl != null && cl.optBoolean("is_climate_on", false);
        double inside = cl != null ? cl.optDouble("inside_temp", -999) : -999;
        double target = cl != null ? cl.optDouble("driver_temp_setting", -999) : -999;
        boolean climateReached = climateOn && inside > -900 && target > -900
                && Math.abs(inside - target) <= 1.0;

        // 센트리
        boolean sentry = vs != null && vs.optBoolean("sentry_mode", false);

        Notification n;
        int nextLive;

        // 우선순위: 주행 > 충전 > (주차중) 공조 > 센트리 > 대기
        if (driving) {
            nextLive = LIVE_DRIVE;
            if (curLive != LIVE_DRIVE) chronoBase = System.currentTimeMillis();
            n = buildLive("테슬라", "운전 중", R.drawable.ic_stat_drive,
                    chronoBase, false, "운전 중");
        } else if (charging) {
            nextLive = LIVE_CHARGE;
            long base = 0;
            String text;
            if (minLeft > 0) {
                base = System.currentTimeMillis() + minLeft * 60_000L;
                text = "완충까지";
            } else {
                text = "충전 중";
            }
            n = buildLive("테슬라 충전", text, R.drawable.ic_stat_charge,
                    base, true, "충전 중");
        } else if (climateOn) {
            nextLive = LIVE_HVAC;
            String text = climateReached ? "에어컨 완료" : "에어컨 가동 중";
            n = buildLive("테슬라 공조", text, R.drawable.ic_stat_snow,
                    0, false, text);
        } else if (sentry) {
            nextLive = LIVE_SENTRY;
            n = buildLive("테슬라", "센트리모드 켜짐", R.drawable.ic_stat_sentry, 0, false, "센트리");
        } else {
            nextLive = LIVE_IDLE;
            n = buildIdleNotification();
        }

        curLive = nextLive;
        nm.notify(NID_ONGOING, n);
    }

    private void handleSoftwareUpdate(JSONObject vs) {
        JSONObject su = vs.optJSONObject("software_update");
        if (su == null) return;
        String status = su.optString("status", "");
        String version = su.optString("version", "");
        // available/scheduled/downloading/installing 등 → 사용자에게 알림
        boolean avail = !status.isEmpty() && !"unavailable".equals(status);
        String lastKey = "sw:" + status + ":" + version;
        if (avail && !lastKey.equals(prefs.getString("last_sw", ""))) {
            notifyEvent(NID_SW, "신규 소프트웨어",
                    version.isEmpty() ? "다운로드 가능" : (version + " 다운로드 가능"),
                    android.R.drawable.stat_sys_download);
            prefs.edit().putString("last_sw", lastKey).apply();
        } else if (!avail) {
            prefs.edit().remove("last_sw").apply();
        }
    }

    /** 트렁크/프렁크 열림 감지 (열리면 알림, 닫히면 자동 취소) */
    private void handleTrunkFrunk(JSONObject vs) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        boolean trunkOpen = vs.optInt("rt", 0) != 0;
        boolean frunkOpen = vs.optInt("ft", 0) != 0;
        boolean lastTrunk = prefs.getBoolean("trunk_open", false);
        boolean lastFrunk = prefs.getBoolean("frunk_open", false);
        if (trunkOpen != lastTrunk) {
            if (trunkOpen) notifyEvent(NID_TRUNK, "트렁크 열림", "트렁크가 열려 있습니다", R.drawable.ic_stat_trunk);
            else nm.cancel(NID_TRUNK);
            prefs.edit().putBoolean("trunk_open", trunkOpen).apply();
        }
        if (frunkOpen != lastFrunk) {
            if (frunkOpen) notifyEvent(NID_FRUNK, "프렁크 열림", "프렁크가 열려 있습니다", R.drawable.ic_stat_frunk);
            else nm.cancel(NID_FRUNK);
            prefs.edit().putBoolean("frunk_open", frunkOpen).apply();
        }
    }

    /** 충전 완료 감지 (Charging → Complete 전환 시 걸린 시간·요금 알림) */
    private void handleChargeComplete(JSONObject cs, JSONObject fullJson) {
        if (cs == null) return;
        String state = cs.optString("charging_state", "");
        String last = prefs.getString("chg_state", "");
        long now = System.currentTimeMillis();

        // Charging으로 진입한 순간 시작 시각 기록
        if ("Charging".equals(state) && !"Charging".equals(last)) {
            prefs.edit().putLong("chg_start", now).apply();
        }
        // Charging → Complete 전환
        if ("Complete".equals(state) && "Charging".equals(last)) {
            long start = prefs.getLong("chg_start", 0);
            long ms = start > 0 ? (now - start) : 0;
            String dur;
            if (ms > 0) {
                long h = ms / 3_600_000L, m = (ms / 60_000L) % 60;
                dur = h > 0 ? (h + "시간 " + m + "분") : (m + "분");
            } else {
                int mtf = cs.optInt("minutes_to_full_charge", 0);
                dur = mtf > 0 ? (mtf + "분") : "-";
            }
            String kwh = "";
            if (cs.has("charge_energy_added"))
                kwh = String.format("%.1fkWh", cs.optDouble("charge_energy_added", 0));
            String cost = "";
            JSONObject cc = fullJson.optJSONObject("charge_cost");
            if (cc != null && cc.has("session_won"))
                cost = String.format("%,d원", cc.optInt("session_won", 0));
            String body = "걸린 시간 " + dur
                    + (kwh.isEmpty() ? "" : " · " + kwh)
                    + (cost.isEmpty() ? "" : " · " + cost);
            notifyEvent(NID_CHG_DONE, "충전 완료 되었습니다", body, R.drawable.ic_stat_charge);
        }
        prefs.edit().putString("chg_state", state).apply();
    }

    private void notifyEvent(int id, String title, String text, int icon) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent it = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH_EVENT)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(Color.parseColor("#e82127"))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(id, n);
    }

    // ── HTTP ──
    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        InputStream is = c.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        is.close();
        return out.toString("UTF-8");
    }

    // ── 외부에서 서비스 시작 ──
    public static void start(Context ctx) {
        Intent it = new Intent(ctx, TeslaWatchService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(it);
        else ctx.startService(it);
    }
}
