package com.eveningoutpost.dexdrip;

import com.eveningoutpost.dexdrip.BgData;

interface IBgDataCallback {
    void onNewBgData(in BgData data);
}
