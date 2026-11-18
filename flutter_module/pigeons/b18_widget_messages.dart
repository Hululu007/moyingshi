import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b17_person_messages.dart: generated
    // Dart code lives inside the module; the generated Java code lands
    // in the host app source tree so Gradle compiles it. Paths are
    // relative to the directory `dart run pigeon` runs from (the module
    // root, see .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b18_widget_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B18Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B18Messages',
    ),
  ),
)
@HostApi()
abstract class B18HostApi {
  /// Static chrome for the browse page (dialog buttons, refresh
  /// tooltip, retry label). Synchronous: Dart renders the page chrome
  /// from this before the first browse snapshot arrives. All strings
  /// come from the host; Dart hard-codes no locale strings.
  B18BrowseShell getBrowseShell();

  /// One-shot snapshot of one widget browse page (native
  /// WidgetBrowseActivity.loadWidget + loadPage(1): find the builtin
  /// script, load it in a WidgetHost, read its metadata, select its
  /// home module, then load page 1 cache-first-then-fresh). The JS
  /// execution and all parsing are 100% host-side; Dart only renders
  /// the returned snapshot. Runs on a background thread; completes
  /// exactly once on every path; the 35s watchdog completes with an
  /// error snapshot (Dart shows its error/retry state). When page-1
  /// cache items exist the snapshot completes immediately with them
  /// (fromCache=true) and Dart then calls reloadBrowsePage(false) for
  /// the fresh result, exactly like native applyItems(cache) followed
  /// by the fresh invoke.
  @async
  B18BrowseScreen getBrowse(String widgetId);

  /// Reloads page 1 for the current module + params (native
  /// loadPage(1)). Unlike getBrowse this always waits for the fresh
  /// JS invoke (no cache-first completion); forceRefresh skips the
  /// cache read entirely (native menu refresh). On a fresh failure
  /// with cached page-1 items, completes with those cached items and
  /// an empty errorText (native keeps the list silently); on a fresh
  /// failure with no items, completes with an error snapshot.
  @async
  B18BrowseScreen reloadBrowsePage(bool forceRefresh);

  /// Loads one further page (native loadPage(page), page > 1).
  /// Returns only that page's items (Dart appends them) plus hasMore
  /// and the page number. On failure completes with an error
  /// snapshot; Dart keeps the list and shows the error text.
  @async
  B18BrowseScreen loadMoreBrowsePage(int page);

  /// Switches to another non-category module (native selectModule:
  /// params reset to defaults, then loadPage(1)). Returns the page-1
  /// snapshot with the same cache-first completion rule as getBrowse.
  @async
  B18BrowseScreen selectBrowseModule(String functionName);

  /// Updates one current param value (native mParams.put). Dart
  /// calls reloadBrowsePage(false) afterwards (native param change
  /// also reloads page 1). Fire-and-forget.
  void setBrowseParam(String name, String value);

  /// Fire-and-forget: open one item (native
  /// WidgetBrowseActivity.onItemClick: search the title in the
  /// current site via SearchActivity.start; toasts are host-side).
  void openBrowseItem(String title);

  /// Destroys the session WidgetHost and its executor (native
  /// onDestroy); completes any in-flight load with an error snapshot
  /// so its Result never dangles. Fire-and-forget.
  void disposeBrowse();
}

/// Static page chrome. All display strings come from the host.
class B18BrowseShell {
  B18BrowseShell({
    required this.retryLabel,
    required this.refreshLabel,
    required this.negativeLabel,
    required this.positiveLabel,
  });

  /// error_retry, for the error state retry button.
  String retryLabel;

  /// widget_refresh, for the toolbar refresh action tooltip.
  String refreshLabel;

  /// dialog_negative, for the param dialogs' cancel button.
  String negativeLabel;

  /// dialog_positive, for the string param dialog's confirm button.
  String positiveLabel;
}

/// One browse snapshot. All display strings and computed values come
/// from the host (native WidgetBrowseActivity / WidgetManager,
/// replicated in the impl); Dart only renders. List images carry the
/// PicSpec field group (plan §4.7): host-resolved URL + headers +
/// fallback + first-character placeholder.
class B18BrowseScreen {
  B18BrowseScreen({
    required this.widgetId,
    required this.title,
    required this.loaded,
    required this.errorText,
    required this.emptyText,
    required this.modules,
    required this.params,
    required this.items,
    required this.hasMore,
    required this.page,
    required this.fromCache,
    required this.columnCount,
  });

  /// Echo of the requested widget id.
  String widgetId;

  /// Toolbar title (native WidgetMetadata.getTitle()).
  String title;

  /// True when the widget script loaded and page 1 returned items
  /// (or an empty list with no error). False with a non-empty
  /// errorText means the widget failed to load or page 1 failed with
  /// no items to show.
  bool loaded;

  /// Whole-snapshot error text (widget_load_failed % message, or
  /// error_play_timeout on watchdog). Empty means no error. When
  /// items are non-empty, Dart keeps the list and shows this text
  /// only as a transient message.
  String errorText;

  /// Empty-state text to show when items are empty and there is no
  /// error: widget_no_module when the widget has no home module,
  /// widget_empty when a module returns zero items. Empty otherwise.
  String emptyText;

  /// Non-category modules in metadata order (native setModuleChips),
  /// with the current one flagged.
  List<B18ModuleItem> modules;

  /// Currently visible params for the current module (native
  /// WidgetManager.visibleParams), with host-computed display values
  /// and enum options.
  List<B18ParamItem> params;

  /// Page items (native WidgetGridAdapter list). For page-1 calls
  /// this is the whole list; for loadMoreBrowsePage only the new
  /// page's items (Dart appends).
  List<B18BrowseItem> items;

  /// True when another page may exist (native applyItems: the page
  /// returned items and the module has a page param).
  bool hasMore;

  /// Page number these items belong to (1 for page-1 calls).
  int page;

  /// True when these items came from WidgetCache (native page-1
  /// cache post). Dart refetches fresh via reloadBrowsePage(false).
  bool fromCache;

  /// Grid columns (native WidgetBrowseActivity.initView: wide
  /// screens use Product.getColumn, otherwise 3).
  int columnCount;
}

/// One non-category module chip (native setModuleChips).
class B18ModuleItem {
  B18ModuleItem({
    required this.functionName,
    required this.title,
    required this.selected,
  });

  /// WidgetModule.getFunctionName(), the invoke key.
  String functionName;

  /// WidgetModule.getTitle().
  String title;

  /// True for the current module.
  bool selected;
}

/// One visible param chip (native setParamChips /
/// WidgetManager.visibleParams). page and constant params never
/// appear here; belongTo-hidden params never appear here.
class B18ParamItem {
  B18ParamItem({
    required this.name,
    required this.type,
    required this.title,
    required this.value,
    required this.displayValue,
    required this.hint,
    required this.options,
  });

  /// Param "name".
  String name;

  /// Param "type": "enumeration" or "string" (page/constant are
  /// filtered out by visibleParams).
  String type;

  /// Param "title" (or "name" when the title is empty), the chip
  /// label prefix.
  String title;

  /// Current raw value (native mParams entry). Dart needs it to
  /// pre-select the enum dialog option and to pre-fill the string
  /// dialog input; displayValue alone is truncated for strings.
  String value;

  /// Host-computed chip value text (native paramDisplayValue):
  /// enumeration shows the selected option title, string shows the
  /// raw value truncated to 8 chars + "…" (or the "全部" label when
  /// empty).
  String displayValue;

  /// Param "description", used as the string dialog's input hint.
  String hint;

  /// Enum options (only for type "enumeration" with enumOptions);
  /// empty for string params.
  List<B18ParamOption> options;
}

/// One enumeration option (native showEnumDialog).
class B18ParamOption {
  B18ParamOption({
    required this.title,
    required this.value,
  });

  /// Option "title".
  String title;

  /// Option "value".
  String value;
}

/// One grid item (native WidgetGridAdapter binding). Poster fields
/// are the PicSpec group.
class B18BrowseItem {
  B18BrowseItem({
    required this.title,
    required this.remarks,
    required this.remarksVisible,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
  });

  /// WidgetItem.getTitle().
  String title;

  /// WidgetItem.getDisplayRemark() (explicit remark, else
  /// "评分 · 年份").
  String remarks;

  /// False when the remark is empty (native sets the remark view
  /// GONE).
  bool remarksVisible;

  /// Poster, host-resolved via WidgetManager.posterUrlWithHeaders +
  /// ImgUtil.getUrl (PicSpec group).
  String picUrl;
  String picReferer;
  String picCookie;
  String picUserAgent;
  String picExtraHeadersJson;
  String fallbackPicUrl;
  String placeholderText;
  int placeholderColor;
}
