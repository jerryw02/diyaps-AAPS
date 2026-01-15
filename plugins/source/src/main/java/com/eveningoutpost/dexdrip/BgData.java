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
}
