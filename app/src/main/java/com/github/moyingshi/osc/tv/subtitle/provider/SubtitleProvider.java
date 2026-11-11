package com.github.moyingshi.osc.tv.subtitle.provider;

import com.github.moyingshi.osc.tv.subtitle.model.SubtitleAsset;
import com.github.moyingshi.osc.tv.subtitle.model.SubtitleCandidate;
import com.github.moyingshi.osc.tv.subtitle.model.SubtitleContext;
import com.github.moyingshi.osc.tv.subtitle.model.SubtitleQuery;

import java.util.List;

public interface SubtitleProvider {

    String getName();

    default String getKey() {
        return getName();
    }

    boolean isEnabled();

    default boolean isQueryIndependent() {
        return false;
    }

    List<SubtitleCandidate> search(SubtitleQuery query, SubtitleContext context) throws Exception;

    SubtitleAsset resolve(SubtitleCandidate candidate, SubtitleContext context) throws Exception;
}
