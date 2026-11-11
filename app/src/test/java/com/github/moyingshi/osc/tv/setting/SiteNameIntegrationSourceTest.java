package com.github.moyingshi.osc.tv.setting;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class SiteNameIntegrationSourceTest {

    @Test
    public void selectiveBackupIncludesSiteNames() throws Exception {
        String backup = read("app/src/main/java/com/github/moyingshi/osc/tv/bean/Backup.java");
        assertTrue(backup.contains("\"site_names\""));
        assertTrue(backup.contains("if (\"site_names\".equals(key)) return options.isConfig() || options.isSettings();"));
    }

    @Test
    public void bothSiteAdaptersDisplayCustomNames() throws Exception {
        assertTrue(read("app/src/mobile/java/com/github/moyingshi/osc/tv/ui/adapter/SiteAdapter.java").contains("getDisplayName()"));
        assertTrue(read("app/src/leanback/java/com/github/moyingshi/osc/tv/ui/adapter/SiteAdapter.java").contains("getDisplayName()"));
    }

    @Test
    public void bothSettingPagesExposeSiteNameManagement() throws Exception {
        assertTrue(read("app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingEnhanceFragment.java").contains("SiteNameDialog"));
        assertTrue(read("app/src/leanback/java/com/github/moyingshi/osc/tv/ui/activity/SettingEnhanceActivity.java").contains("SiteNameDialog"));
    }

    @Test
    public void searchAndCollectionSurfacesDisplayCustomNames() throws Exception {
        assertTrue(read("app/src/mobile/java/com/github/moyingshi/osc/tv/ui/adapter/CollectAdapter.java").contains("getDisplayName()"));
        assertTrue(read("app/src/leanback/java/com/github/moyingshi/osc/tv/ui/adapter/CollectAdapter.java").contains("getDisplayName()"));
        assertTrue(read("app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SearchFragment.java").contains("site.getDisplayName()"));
        assertTrue(read("app/src/leanback/java/com/github/moyingshi/osc/tv/ui/activity/SearchActivity.java").contains("site.getDisplayName()"));
    }

    @Test
    public void sourceManagementDialogsDisplayCustomNamesWithoutChangingRuleMatching() throws Exception {
        String audio = read("app/src/main/java/com/github/moyingshi/osc/tv/ui/dialog/AudioSourceDialog.java");
        String shortDrama = read("app/src/main/java/com/github/moyingshi/osc/tv/ui/dialog/ShortDramaSourceDialog.java");
        String tmdb = read("app/src/main/java/com/github/moyingshi/osc/tv/ui/dialog/TmdbSourceDialog.java");
        assertTrue(audio.contains("site.getDisplayName()"));
        assertTrue(shortDrama.contains("site.getDisplayName()"));
        assertTrue(tmdb.contains("site.getDisplayName()"));
        assertTrue(audio.contains("String name = site.getName()"));
        assertTrue(shortDrama.contains("String name = site.getName()"));
        assertTrue(tmdb.contains("String name = site.getName()"));
    }

    @Test
    public void historyAndKeepsResolveCurrentCustomNames() throws Exception {
        assertTrue(read("app/src/main/java/com/github/moyingshi/osc/tv/bean/History.java").contains("getDisplayName()"));
        assertTrue(read("app/src/main/java/com/github/moyingshi/osc/tv/bean/Keep.java").contains("SiteNameStore.getDisplayName"));
    }
    private static String read(String file) throws Exception {
        Path root = Files.exists(Path.of("app")) ? Path.of("") : Path.of("..");
        return Files.readString(root.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
