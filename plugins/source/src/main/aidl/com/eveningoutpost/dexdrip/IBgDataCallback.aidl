package com.eveningoutpost.dexdrip;

import com.eveningoutpost.dexdrip.BgData;

interface IBgDataCallback {
    void onNewBgData(in BgData data);
    void onHeartbeat(long timestamp);  // 新增心跳方法
}
