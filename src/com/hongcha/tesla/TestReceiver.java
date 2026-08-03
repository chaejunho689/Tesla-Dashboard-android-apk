package com.hongcha.tesla;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 알림 테스트용: adb broadcast → 서비스에 which 전달 → 해당 알림 발생 */
public class TestReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent it) {
        String which = it.getStringExtra("which");
        if (which == null) return;
        Intent svc = new Intent(ctx, TeslaWatchService.class);
        svc.putExtra("test", which);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
        else ctx.startService(svc);
    }
}
