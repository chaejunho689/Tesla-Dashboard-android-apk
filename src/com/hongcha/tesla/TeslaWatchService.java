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
    static final int NID_ONGOING = 1;   // 포어그라운드 keepalive (승격 안 함)
    static final int NID_LIVE    = 2;   // 상태 칩(승격 대상) — 상태 없으면 cancel
    static final int NID_SW      = 100;
    static final int NID_CHG_DONE = 103;
    static final int NID_DRIVE_END = 104;

    // 상태 저장 (중복 알림 방지)
    private static final String PREFS = "tesla_state";
    private SharedPreferences prefs;

    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService pendingScheduler;
    private ScheduledExecutorService chipScheduler;
    private String base = "", key = "";

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        base = getString(R.string.bridge_base);
        key  = getString(R.string.bridge_key);
        createChannels();
        // 포어그라운드 유지용 최소 알림. 상태 칩은 별도 알림(NID_LIVE)으로 분리한다.
        // (분리 이유: 상태가 없어질 때 칩 알림을 cancel 해야 나중에 다시 확실히 재등록된다)
        startForeground(NID_ONGOING, buildKeepalive());
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

        // 칩의 HH:MM 갱신용 — 네트워크 없이 로컬 시각만으로 1분마다 다시 그린다.
        chipScheduler = Executors.newSingleThreadScheduledExecutor();
        chipScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { refreshChip(); }
        }, 60, 60, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("test")) fireTest(intent.getStringExtra("test"));
        return START_STICKY;
    }

    /** 테스트: 샘플 데이터로 각 알림/칩 발생 (adb broadcast → TestReceiver) */
    private void fireTest(String w) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || w == null) return;
        long t = System.currentTimeMillis();
        switch (w) {
            case "sw":      notifyEvent(NID_SW, "신규 소프트웨어", "2024.44.25 다운로드 가능",
                                android.R.drawable.stat_sys_download); break;
            case "chgdone": notifyEvent(NID_CHG_DONE, "충전 완료 되었습니다",
                                "걸린 시간 3시간 15분 · 32.4kWh · 9,169원", R.drawable.ic_stat_charge); break;
            case "driveend":notifyEvent(NID_DRIVE_END, "주행 종료", "주행 시간 42분",
                                R.drawable.ic_stat_park); break;
            // 상태 칩 — 실제 상태 진입과 같은 경로(applyLive)를 타게 한다
            case "drive":   driveStart = t - 170 * 60_000L; applyLive(LIVE_DRIVE); break;   // 02:50 경과
            case "charge":  chargeEta  = t + 45 * 60_000L;  applyLive(LIVE_CHARGE); break;  // 00:45 남음
            case "hvac":    hvacStart  = t - 50 * 60_000L;
                            hvacText   = "에어컨 가동 중";  applyLive(LIVE_HVAC); break;     // 00:50 경과
            case "trunk":   applyLive(LIVE_TRUNK); break;
            case "frunk":   applyLive(LIVE_FRUNK); break;
            case "sentry":  applyLive(LIVE_SENTRY); break;
            case "off":     applyLive(LIVE_NONE); break;
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        if (pendingScheduler != null) pendingScheduler.shutdownNow();
        if (chipScheduler != null) chipScheduler.shutdownNow();
        // 칩 알림은 포어그라운드 알림이 아니라 서비스가 죽어도 남는다 → 직접 정리
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NID_LIVE);
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

    /** 라이브 업데이트(나우바) 알림 — Android 16(API36) 승격 요청 + 짧은 상태 텍스트
     *  chip: 상태바 칩에 표시할 짧은 텍스트(시간·정보). chronoBase>0이면 타이머.
     *
     *  ※ 승격(FLAG_PROMOTED_ONGOING) 필수 조건 — 하나라도 어기면 나우바에 안 뜬다:
     *    - setOngoing(true), contentTitle 있음, 커스텀 RemoteViews 없음, 그룹 요약 아님
     *    - 채널 importance != IMPORTANCE_MIN
     *    - extras에 "android.requestPromotedOngoing" = true
     *    - 매니페스트에 POST_PROMOTED_NOTIFICATIONS
     *    - setColorized(true) 를 쓰면 안 됨  ← 예전 구현이 여기서 걸려 승격이 거부됐음 */
    private Notification buildLive(String title, String text, int icon,
                                   long chronoBase, boolean countDown, String chip) {
        Notification.Builder b = new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .setColor(Color.parseColor("#e82127"));
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
            Notification n = b.build();
            try { Log.d(TAG, "promotable=" + n.hasPromotableCharacteristics() + " (" + text + ")"); }
            catch (Throwable ignore) {}
            return n;
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

    // 상태 칩 카테고리. 숫자가 클수록 우선순위가 높다(동시 발생 시 하나만 표시).
    private static final int LIVE_NONE  = 0,
                             LIVE_SENTRY = 1,
                             LIVE_HVAC   = 2,
                             LIVE_FRUNK  = 3,
                             LIVE_TRUNK  = 4,
                             LIVE_CHARGE = 5,
                             LIVE_DRIVE  = 6;

    // 폴링 스레드와 칩 갱신 스레드가 함께 읽으므로 volatile
    private volatile int curLive = LIVE_NONE;
    private volatile long driveStart = 0;   // 주행 시작 시각(ms)
    private volatile long hvacStart  = 0;   // 공조 가동 시작 시각(ms)
    private volatile long chargeEta  = 0;   // 완충 예상 시각(ms). 0이면 미상
    private volatile String hvacText = "에어컨 가동 중";
    private boolean wasDriving = false;

    /** 경과/남은 밀리초 → "HH:MM" (칩에 들어갈 짧은 형식) */
    private static String hhmm(long ms) {
        if (ms < 0) ms = 0;
        long totalMin = ms / 60_000L;
        long h = totalMin / 60, m = totalMin % 60;
        if (h > 99) { h = 99; m = 59; }
        return String.format(java.util.Locale.US, "%02d:%02d", h, m);
    }

    /** 시간이 들어가는 칩(운전·충전·에어컨)을 1분마다 다시 그린다. 네트워크 호출 없음. */
    private void refreshChip() {
        int s = curLive;
        if (s != LIVE_DRIVE && s != LIVE_CHARGE && s != LIVE_HVAC) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = buildForState(s);
        if (n != null) nm.notify(NID_LIVE, n);
    }

    /** 상태별 라이브 알림 생성. 칩 텍스트는 7자 이내로 유지할 것(초과 시 잘린다). */
    private Notification buildForState(int state) {
        long now = System.currentTimeMillis();
        switch (state) {
            case LIVE_DRIVE:
                return buildLive("테슬라", "운전 중", R.drawable.ic_stat_drive,
                        driveStart, false, hhmm(now - driveStart));
            case LIVE_CHARGE: {
                boolean eta = chargeEta > now;
                return buildLive("테슬라 충전",
                        eta ? "완충까지 " + hhmm(chargeEta - now) : "충전 중",
                        R.drawable.ic_stat_charge,
                        eta ? chargeEta : 0, true,
                        eta ? hhmm(chargeEta - now) : "충전중");
            }
            case LIVE_TRUNK:
                return buildLive("테슬라", "트렁크 열림", R.drawable.ic_stat_trunk, 0, false, "트렁크");
            case LIVE_FRUNK:
                return buildLive("테슬라", "프렁크 열림", R.drawable.ic_stat_frunk, 0, false, "프렁크");
            case LIVE_HVAC:
                return buildLive("테슬라 공조", hvacText, R.drawable.ic_stat_snow,
                        hvacStart, false, hhmm(now - hvacStart));
            case LIVE_SENTRY:
                return buildLive("테슬라", "센트리 모드", R.drawable.ic_stat_sentry, 0, false, "감시중");
            default:
                return null;
        }
    }

    /** 상태 칩 반영. LIVE_NONE이면 칩 알림 자체를 없앤다. */
    private void applyLive(int state) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (state == LIVE_NONE) {
            if (curLive != LIVE_NONE) nm.cancel(NID_LIVE);
            curLive = LIVE_NONE;
            return;
        }
        Notification n = buildForState(state);
        if (n == null) return;
        curLive = state;
        nm.notify(NID_LIVE, n);
    }

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

            if (vs != null) handleSoftwareUpdate(vs);
            handleChargeComplete(cs, j);
            updateLiveNotification(cs, cl, ds, vs);
        } catch (Exception e) {
            Log.d(TAG, "poll error: " + e.getMessage());
        }
    }

    private void updateLiveNotification(JSONObject cs, JSONObject cl, JSONObject ds, JSONObject vs) {
        long now = System.currentTimeMillis();

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

        boolean charging = cs != null && "Charging".equals(cs.optString("charging_state", ""));
        int minLeft = cs != null ? cs.optInt("minutes_to_full_charge", 0) : 0;

        boolean climateOn = cl != null && cl.optBoolean("is_climate_on", false);
        double inside = cl != null ? cl.optDouble("inside_temp", -999) : -999;
        double target = cl != null ? cl.optDouble("driver_temp_setting", -999) : -999;
        boolean climateReached = climateOn && inside > -900 && target > -900
                && Math.abs(inside - target) <= 1.0;

        boolean trunkOpen = vs != null && vs.optInt("rt", 0) != 0;
        boolean frunkOpen = vs != null && vs.optInt("ft", 0) != 0;
        boolean sentry    = vs != null && vs.optBoolean("sentry_mode", false);

        // 시작 시각 갱신 (상태에 새로 진입한 순간만)
        if (driving && !wasDriving) driveStart = now;
        if (climateOn) { if (hvacStart == 0) hvacStart = now; } else hvacStart = 0;
        chargeEta = (charging && minLeft > 0) ? now + minLeft * 60_000L : 0;
        hvacText  = climateReached ? "에어컨 완료" : "에어컨 가동 중";

        // 주행 종료 → 칩은 내려가고 일반 알림 1회
        if (wasDriving && !driving) {
            long ms = driveStart > 0 ? (now - driveStart) : 0;
            notifyEvent(NID_DRIVE_END, "주행 종료",
                    ms > 0 ? ("주행 시간 " + durKo(ms)) : "주행이 종료되었습니다",
                    R.drawable.ic_stat_park);
        }
        wasDriving = driving;

        // 우선순위: 운전 > 충전 > 트렁크 > 프렁크 > 에어컨 > 감시. 해당 없으면 칩 없음.
        int next = LIVE_NONE;
        if (driving)         next = LIVE_DRIVE;
        else if (charging)   next = LIVE_CHARGE;
        else if (trunkOpen)  next = LIVE_TRUNK;
        else if (frunkOpen)  next = LIVE_FRUNK;
        else if (climateOn)  next = LIVE_HVAC;
        else if (sentry)     next = LIVE_SENTRY;

        applyLive(next);
    }

    /** 사람이 읽는 소요시간 ("1시간 5분" / "5분") */
    private static String durKo(long ms) {
        long m = ms / 60_000L, h = m / 60;
        m %= 60;
        return h > 0 ? (h + "시간 " + m + "분") : (m + "분");
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
