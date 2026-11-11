package com.fongmi.android.tv.widget;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/**
 * 组件条目（模块函数返回的数组里的一项，CapyPlayer 规范）。
 * 未知字段由 Gson 忽略；点开条目时只用 title 去当前资源搜索。
 */
public class WidgetItem {

    @SerializedName("id")
    private String id;
    @SerializedName("type")
    private String type;
    @SerializedName("title")
    private String title;
    @SerializedName("originalTitle")
    private String originalTitle;
    @SerializedName("mediaType")
    private String mediaType;
    @SerializedName("posterUrl")
    private String posterUrl;
    @SerializedName("posterPath")
    private String posterPath;
    @SerializedName("backdropPath")
    private String backdropPath;
    @SerializedName("rating")
    private Double rating;
    @SerializedName("releaseDate")
    private String releaseDate;
    @SerializedName("description")
    private String description;
    @SerializedName("genreTitle")
    private String genreTitle;
    @SerializedName("remark")
    private String remark;
    @SerializedName("customHeaders")
    private Map<String, String> customHeaders;
    @SerializedName("link")
    private String link;

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getOriginalTitle() {
        return originalTitle;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public String getPosterPath() {
        return posterPath;
    }

    public String getBackdropPath() {
        return backdropPath;
    }

    public Double getRating() {
        return rating;
    }

    public String getReleaseDate() {
        return releaseDate;
    }

    public String getDescription() {
        return description;
    }

    public String getGenreTitle() {
        return genreTitle;
    }

    public String getRemark() {
        return remark;
    }

    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }

    public String getLink() {
        return link;
    }

    public boolean isLink() {
        return "link".equals(type);
    }

    /** 海报地址：优先 posterUrl（绝对地址），否则 posterPath（TMDB 相对路径，由调用方拼完整）。 */
    public String getPoster() {
        if (!TextUtils.isEmpty(posterUrl)) return posterUrl;
        return posterPath;
    }

    /** 角标：优先 remark，否则用评分/年份拼一个。 */
    public String getDisplayRemark() {
        if (!TextUtils.isEmpty(remark)) return remark;
        StringBuilder sb = new StringBuilder();
        if (rating != null && rating > 0) sb.append(String.format(java.util.Locale.US, "%.1f", rating));
        if (!TextUtils.isEmpty(releaseDate) && releaseDate.length() >= 4) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(releaseDate.substring(0, 4));
        }
        return sb.toString();
    }
}
