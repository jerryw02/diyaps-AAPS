package com.eveningoutpost.dexdrip; // 请替换为 xDrip 的实际包名

import android.os.Parcel;
import android.os.Parcelable;

public class BgData implements Parcelable {
    public double value; // 血糖值
    public String trend; // 趋势 (e.g., "UP", "DOWN", "FLAT")
    public long timestamp; // 时间戳
    public String source; // 数据来源 (e.g., "Dexcom", "G5")

    public BgData(double value, String trend, long timestamp, String source) {
        this.value = value;
        this.trend = trend;
        this.timestamp = timestamp;
        this.source = source;
    }

    // Parcelable 构造器
    protected BgData(Parcel in) {
        value = in.readDouble();
        trend = in.readString();
        timestamp = in.readLong();
        source = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeDouble(value);
        dest.writeString(trend);
        dest.writeLong(timestamp);
        dest.writeString(source);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BgData> CREATOR = new Creator<BgData>() {
        @Override
        public BgData createFromParcel(Parcel in) {
            return new BgData(in);
        }

        @Override
        public BgData[] newArray(int size) {
            return new BgData[size];
        }
   };
}
