// IBgDataCallback.aidl
package com.eveningoutpost.dexdrip; // 请替换为 xDrip 的实际包名

import com.eveningoutpost.dexdrip.BgData; // 导入数据类

oneway interface IBgDataCallback {
    void onNewBgData(in BgData data); // AAPS 接收数据的回调
}
