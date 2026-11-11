package com.github.moyingshi.osc.tv.api.config;

import com.github.moyingshi.osc.tv.bean.Rule;
import com.github.moyingshi.osc.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

public final class InterfaceAdRuleLearningService {

    private InterfaceAdRuleLearningService() {
    }

    public static void schedule(String sourceName, String sourceUrl, List<String> ads, List<Rule> rules) {
        List<String> adsSnapshot = ads == null ? List.of() : new ArrayList<>(ads);
        List<Rule> rulesSnapshot = rules == null ? List.of() : new ArrayList<>(rules);
        Task.submit(() -> ImportedAdRuleCandidateStore.merge(
                InterfaceAdRuleAnalyzer.analyze(sourceName, sourceUrl, adsSnapshot, rulesSnapshot)));
    }
}
