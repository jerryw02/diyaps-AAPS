package com.eveningoutpost.dexdrip;

import com.eveningoutpost.dexdrip.BgData;
import com.eveningoutpost.dexdrip.IBgDataCallback;

interface IBgDataService {
    /**
     * 获取最新的血糖数据
     */
    BgData getLatestBgData();  // 确保这行存在
    
    /**
     * 注册回调监听血糖变化
     */
    void registerCallback(IBgDataCallback callback);
    
    /**
     * 注销回调
     */
    void unregisterCallback(IBgDataCallback callback);
}
