package com.github.aiyingshi.osc.tv.bean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.github.aiyingshi.osc.tv.db.AppDatabase;
import com.github.aiyingshi.osc.tv.impl.Diffable;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Objects;

/**
 * 喜好“想看”清单：独立 Room 表，不复用 Keep（Keep 是按站分组的收藏，语义不同）。
 * key 拼法仿 Keep：siteKey + "@@@" + vodId + "@@@" + cid。
 */
@Entity
public class Want implements Diffable<Want> {

    @NonNull
    @PrimaryKey
    @SerializedName("key")
    private String key;
    @SerializedName("siteName")
    private String siteName;
    @SerializedName("vodName")
    private String vodName;
    @SerializedName("vodPic")
    private String vodPic;
    @SerializedName("createTime")
    private long createTime;
    @SerializedName("cid")
    private int cid;

    public static String keyFor(String siteKey, String vodId, int cid) {
        String site = siteKey == null ? "" : siteKey;
        String vod = vodId == null ? "" : vodId;
        return site + AppDatabase.SYMBOL + vod + AppDatabase.SYMBOL + cid;
    }

    public static List<Want> findAll() {
        return AppDatabase.get().getWantDao().findAll();
    }

    public static Want find(String key) {
        return AppDatabase.get().getWantDao().find(key);
    }

    public static boolean exist(String key) {
        return AppDatabase.get().getWantDao().exist(key);
    }

    public static void deleteAll() {
        AppDatabase.get().getWantDao().deleteAll();
    }

    public static void delete(String key) {
        AppDatabase.get().getWantDao().delete(key);
    }

    @NonNull
    public String getKey() {
        return key;
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getVodName() {
        return vodName;
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getVodPic() {
        return vodPic;
    }

    public void setVodPic(String vodPic) {
        this.vodPic = vodPic;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public int getCid() {
        return cid;
    }

    public void setCid(int cid) {
        this.cid = cid;
    }

    public String getSiteKey() {
        return getKey().split(AppDatabase.SYMBOL)[0];
    }

    public String getVodId() {
        return getKey().split(AppDatabase.SYMBOL)[1];
    }

    public void save(String siteKey, String vodId, int cid) {
        setKey(keyFor(siteKey, vodId, cid));
        setCid(cid);
        save();
    }

    public void save() {
        AppDatabase.get().getWantDao().insertOrUpdate(this);
    }

    public Want delete() {
        AppDatabase.get().getWantDao().delete(getKey());
        return this;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Want it)) return false;
        return Objects.equals(getKey(), it.getKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKey());
    }

    @Override
    public boolean isSameItem(Want other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Want other) {
        return getVodName().equals(other.getVodName()) && getVodPic().equals(other.getVodPic()) && getCreateTime() == other.getCreateTime();
    }
}
