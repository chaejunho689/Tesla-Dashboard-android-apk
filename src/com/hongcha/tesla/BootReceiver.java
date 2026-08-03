package com.hongcha.tesla;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 부팅 완료 시 상태 감시 서비스 자동 시작 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent it) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(it.getAction())) {
            TeslaWatchService.start(ctx);
        }
    }
}
