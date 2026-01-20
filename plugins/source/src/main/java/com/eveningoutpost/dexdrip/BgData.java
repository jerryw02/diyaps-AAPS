package com.eveningoutpost.dexdrip;

import android.os.Parcel;
import android.os.Parcelable;

public class BgData implements Parcelable {
    public long timestamp;
    public double glucose;
    public double glucoseMmol;
    public double trend;
    public double trendMmol;
    public String direction;
    public String noise;
    public double filtered;
    public double unfiltered;
    public String source;
    public int sensorBatteryLevel;
    public int transmitterBatteryLevel;
    public String rawData;

    public BgData() {}

    protected BgData(Parcel in) {
        timestamp = in.readLong();
        glucose = in.readDouble();
        glucoseMmol = in.readDouble();
        trend = in.readDouble();
        trendMmol = in.readDouble();
        direction = in.readString();
        noise = in.readString();
        filtered = in.readDouble();
        unfiltered = in.readDouble();
        source = in.readString();
        sensorBatteryLevel = in.readInt();
        transmitterBatteryLevel = in.readInt();
        rawData = in.readString();
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

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(timestamp);
        dest.writeDouble(glucose);
        dest.writeDouble(glucoseMmol);
        dest.writeDouble(trend);
        dest.writeDouble(trendMmol);
        dest.writeString(direction);
        dest.writeString(noise);
        dest.writeDouble(filtered);
        dest.writeDouble(unfiltered);
        dest.writeString(source);
        dest.writeInt(sensorBatteryLevel);
        dest.writeInt(transmitterBatteryLevel);
        dest.writeString(rawData);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public boolean isValid() {
        return glucose >= 20 && glucose <= 500;
    }

    public long getAgeMinutes() {
        return (System.currentTimeMillis() - timestamp) / (1000 * 60);
    }

    public boolean isNoisy() {
        return noise != null && (noise.toLowerCase().contains("high") || 
                                 noise.toLowerCase().contains("medium"));
    }

    // 在 Getter 方法后面添加 Setter 方法：
    // Getter 方法
    public double getGlucose() { return glucose; }
    public long getTimestamp() { return timestamp; }
    public String getDirection() { return direction; }
    public String getSource() { return source; }
    public String getRawData() { return rawData; }

    // 添加 Setter 方法（为了兼容现有代码）
    public void setGlucose(double glucose) { this.glucose = glucose; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setDirection(String direction) { this.direction = direction; }
    public void setSource(String source) { this.source = source; }
    public void setRawData(String rawData) { this.rawData = rawData; }

    // 为了兼容原有调用，可以添加这些方法
    public double getGlucoseValue() { return glucose; }
    public void setGlucoseValue(double glucoseValue) { this.glucose = glucoseValue; } // 添加这个

    public int getTrend() { return (int)trend; }
    public void setTrend(int trend) { this.trend = trend; } // 添加这个

    public long getSequenceNumber() { 
        try {
            return Long.parseLong(rawData);
        } catch (Exception e) {
            return 0;
        }
    }
    public void setSequenceNumber(long sequenceNumber) { // 添加这个
        this.rawData = String.valueOf(sequenceNumber);
    }

    public boolean isReliable() { return true; } // 兼容方法
    public void setReliable(boolean reliable) { /* 忽略 */ } // 添加这个   
    
    @Override
    public String toString() {
        return String.format("BgData{glucose=%.1f, time=%d, direction=%s, source=%s}", 
            glucose, timestamp, direction, source);
    }
    
}
