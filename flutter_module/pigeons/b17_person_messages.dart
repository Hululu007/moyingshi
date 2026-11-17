import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b16_report_messages.dart: generated
    // Dart code lives inside the module; the generated Java code lands
    // in the host app source tree so Gradle compiles it. Paths are
    // relative to the directory `dart run pigeon` runs from (the module
    // root, see .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b17_person_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B17Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B17Messages',
    ),
  ),
)
@HostApi()
abstract class B17HostApi {
  /// B17 feature flag (persisted in Setting, default false = native
  /// TmdbPersonActivity). The flag only gates the single diversion
  /// point inside TmdbPersonActivity.start(); both callers
  /// (TmdbDetailActivity.loadPersonDetail and
  /// WebHomeThemeBridge.openPerson) go through it, and the leanback
  /// detail page is untouched because the diversion also requires
  /// Util.isMobile().
  bool isB17Enabled();
  void setB17Enabled(bool enabled);

  /// Static chrome for the person page (section titles + viewer
  /// labels). Synchronous: Dart renders the page chrome from this
  /// before the first person snapshot arrives. All strings come from
  /// the host; Dart hard-codes no locale strings.
  B17PersonShell getPersonShell();

  /// One-shot snapshot of one person (native
  /// TmdbPersonActivity.loadPerson, in order: TMDB config ready check,
  /// TmdbService.person, personProfile, personPhotos, personWorks /
  /// personCastWorks / personCrewWorks, personScore). The TMDB request
  /// and all parsing are 100% host-side; Dart only renders the
  /// returned snapshot. Runs on a background thread; completes exactly
  /// once on every path; the 35s watchdog completes with an error
  /// snapshot (Dart shows its error/retry state). siteKey/detailMode
  /// are stored as the session for openPersonWork (native openWork
  /// needs them); personId comes from the route query.
  @async
  B17PersonScreen getPerson(int personId, String siteKey, int detailMode);

  /// Work-count label for a filtered list size, e.g. "共 12 部作品"
  /// (detail_person_work_count). The count depends on the Dart-side
  /// filter, so Dart asks the host to format it instead of hard-coding
  /// the locale string.
  String getWorkCountText(int count);

  /// Fire-and-forget: open one work from the list (native
  /// TmdbPersonActivity.openWork: current-source search, then
  /// TmdbDetailActivity or SearchActivity.direct; toasts are
  /// host-side). tmdbId/mediaType identify the work in the host's
  /// snapshot map; title is the fallback search keyword.
  void openPersonWork(int tmdbId, String mediaType, String title);

  /// Fire-and-forget: save one photo via TmdbImageSaver (native
  /// TmdbPersonActivity.saveImage: saving toast, then success/failure
  /// toast — all host-side). photoUrl is the full-size original URL.
  void savePersonPhoto(String photoUrl);

  /// Toggles the host Activity orientation (native
  /// TmdbPersonActivity.togglePhotoOrientation, the photo viewer
  /// rotate button).
  void togglePhotoOrientation();
}

/// Static page chrome. All display strings come from the host.
class B17PersonShell {
  B17PersonShell({
    required this.biographyTitle,
    required this.infoTitle,
    required this.photosTitle,
    required this.worksTitle,
    required this.scoreTitle,
    required this.retryLabel,
    required this.needKeyText,
    required this.saveLabel,
    required this.rotateLabel,
  });

  /// detail_person_biography (biography section title).
  String biographyTitle;

  /// detail_person_info (personal-info section title).
  String infoTitle;

  /// detail_person_photos (photos section title).
  String photosTitle;

  /// detail_person_works (works section title).
  String worksTitle;

  /// detail_person_score_title (score block title).
  String scoreTitle;

  /// error_retry, for the error state.
  String retryLabel;

  /// detail_tmdb_need_key (shown when TMDB is not configured).
  String needKeyText;

  /// detail_image_save (photo viewer save button).
  String saveLabel;

  /// detail_image_rotate (photo viewer rotate button).
  String rotateLabel;
}

/// One person snapshot. All display strings and computed values come
/// from the host (native TmdbPersonActivity.bindPerson / personScore /
/// personBiography / refreshFilterButtons, replicated in the impl);
/// Dart only renders. The profile photo and every list image carry the
/// PicSpec field group (plan §4.7): host-resolved URL + headers +
/// fallback + first-character placeholder.
class B17PersonScreen {
  B17PersonScreen({
    required this.loaded,
    required this.needKey,
    required this.errorText,
    required this.personId,
    required this.name,
    required this.subtitle,
    required this.profilePicUrl,
    required this.profilePicReferer,
    required this.profilePicCookie,
    required this.profilePicUserAgent,
    required this.profilePicExtraHeadersJson,
    required this.profilePicFallbackUrl,
    required this.profilePicPlaceholderText,
    required this.profilePicPlaceholderColor,
    required this.biography,
    required this.scoreVisible,
    required this.scoreValue,
    required this.scoreMeta,
    required this.photos,
    required this.allWorks,
    required this.castWorks,
    required this.crewWorks,
    required this.filters,
  });

  /// True when the TMDB detail request returned data (native
  /// loadPerson found a non-empty detail object). False with
  /// needKey=true means TMDB is not configured; false with a
  /// non-empty errorText means the request failed or returned nothing.
  /// Dart keeps showing the route-param initial person info until a
  /// loaded snapshot arrives.
  bool loaded;

  /// True when TmdbConfig is not ready (native loadPerson early
  /// return with the detail_tmdb_need_key toast).
  bool needKey;

  /// Whole-snapshot error text (load timeout: error_play_timeout;
  /// empty detail: detail_tmdb_empty). Empty means no error.
  String errorText;

  /// Echo of the requested person id.
  int personId;

  /// Person name (TmdbPerson.getName()).
  String name;

  /// Personal-info line: department · birthday[-deathday] ·
  /// birthplace · aliases, joined with " · " (native personProfile).
  String subtitle;

  /// Profile photo, host-resolved via ImgUtil.getUrl (PicSpec group).
  String profilePicUrl;
  String profilePicReferer;
  String profilePicCookie;
  String profilePicUserAgent;
  String profilePicExtraHeadersJson;
  String profilePicFallbackUrl;
  String profilePicPlaceholderText;
  int profilePicPlaceholderColor;

  /// Biography text (native personBiography; empty biography shows
  /// detail_person_empty).
  String biography;

  /// Score block (native personScore + bindPerson score section).
  /// Visible only when the person has combined credits.
  bool scoreVisible;

  /// detail_person_score_value, e.g. "72".
  String scoreValue;

  /// detail_person_score_meta (focus, cast/director/crew counts,
  /// work average vote), pre-built multi-line string.
  String scoreMeta;

  /// Photos, up to 24, deduped (native personPhotos).
  List<B17PhotoItem> photos;

  /// All works, date-desc, limit 30 (native personWorks).
  List<B17WorkItem> allWorks;

  /// Cast works, limit 60 (native personCastWorks).
  List<B17WorkItem> castWorks;

  /// Crew works, limit 60 (native personCrewWorks).
  List<B17WorkItem> crewWorks;

  /// Filter chips in native order: "all" first when it has works,
  /// then cast/crew/director/movie/tv by count desc, only count > 0
  /// (native refreshFilterButtons). Dart filters locally from the
  /// three work lists using the same rules.
  List<B17FilterItem> filters;
}

/// One photo (native personPhotos entry). thumb fields are the PicSpec
/// group; fullPicUrl is the original-size URL for the viewer (native
/// highResTmdbImage), sharing the same headers.
class B17PhotoItem {
  B17PhotoItem({
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
    required this.fullPicUrl,
  });

  String picUrl;
  String picReferer;
  String picCookie;
  String picUserAgent;
  String picExtraHeadersJson;
  String fallbackPicUrl;
  String placeholderText;
  int placeholderColor;
  String fullPicUrl;
}

/// One work (native TmdbItem from personWorks / personCastWorks /
/// personCrewWorks). subtitle is built by TmdbService.buildSubtitle
/// ("电影/剧集 · 日期 · 评分 ..."); credit is the role/job string
/// ("饰 ..." for cast, job/department for crew); rating is the vote
/// value. Poster fields are the PicSpec group.
class B17WorkItem {
  B17WorkItem({
    required this.tmdbId,
    required this.mediaType,
    required this.title,
    required this.subtitle,
    required this.overview,
    required this.credit,
    required this.rating,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
  });

  int tmdbId;
  String mediaType;
  String title;
  String subtitle;
  String overview;
  String credit;
  double rating;
  String picUrl;
  String picReferer;
  String picCookie;
  String picUserAgent;
  String picExtraHeadersJson;
  String fallbackPicUrl;
  String placeholderText;
  int placeholderColor;
}

/// One filter chip (native refreshFilterButtons).
class B17FilterItem {
  B17FilterItem({
    required this.key,
    required this.label,
    required this.count,
  });

  /// "all" / "cast" / "crew" / "director" / "movie" / "tv".
  String key;

  /// detail_filter_* label.
  String label;

  /// filteredWorks(key).size() in the host.
  int count;
}
