package cn.lyric.getter.api.data;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Wire-compatible Parcelable matching cn.lyric.getter.api.data.LyricData for "Lyric_Data" broadcasts. */
@SuppressLint("ParcelClassLoader")
public final class LyricData implements Parcelable {
    public static final int TYPE_STOP = 0;
    public static final int TYPE_UPDATE = 1;

    public final int type;
    public final String lyric;
    public final Bundle extra;

    public LyricData(int type, String lyric, Bundle extra) {
        this.type = type == TYPE_STOP ? TYPE_STOP : TYPE_UPDATE;
        this.lyric = lyric == null ? "" : lyric;
        this.extra = extra == null ? new Bundle() : extra;
    }

    private LyricData(Parcel in) {
        int rawType = in.readInt();
        this.type = rawType == TYPE_STOP ? TYPE_STOP : TYPE_UPDATE;
        String rawLyric = in.readString();
        this.lyric = rawLyric == null ? "" : rawLyric;
        Bundle rawExtra = in.readBundle(LyricData.class.getClassLoader());
        this.extra = rawExtra == null ? new Bundle() : rawExtra;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeString(lyric);
        dest.writeBundle(extra);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public String packageName() {
        return extra.getString("packageName", "");
    }

    public static final Creator<LyricData> CREATOR = new Creator<LyricData>() {
        @Override
        public LyricData createFromParcel(Parcel in) {
            return new LyricData(in);
        }

        @Override
        public LyricData[] newArray(int size) {
            return new LyricData[size];
        }
    };
}
