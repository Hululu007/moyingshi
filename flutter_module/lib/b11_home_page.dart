import 'dart:async' show Timer;
import 'dart:io' show File;
import 'dart:math' as math;
import 'dart:ui' show
    ImageFilter,
    Offset,
    PointerCancelEvent,
    PointerDeviceKind,
    PointerDownEvent,
    PointerMoveEvent,
    PointerUpEvent;

import 'package:flutter/animation.dart'
    show AnimationController, CurvedAnimation, Tween;
import 'package:flutter/foundation.dart' show ValueListenable, ValueNotifier;
import 'package:flutter/gestures.dart'
    show Velocity, VelocityTracker, kTouchSlop;
import 'package:flutter/rendering.dart' show RenderBox, RenderObject;
import 'package:material_ui/material_ui.dart';
import 'package:vector_math/vector_math_64.dart' show Matrix4, Vector3;

import 'pigeon/b11_home_messages.g.dart';
import 'pigeon/b8_category_messages.g.dart';
import 'pigeon/c1_messages.g.dart';

import 'b12_home_block_a.dart';
import 'b13_home_block_b.dart';
import 'widgets/pic_image.dart';
import 'widgets/vod_card.dart';

/// B8 B11: Flutter version of the mobile home shell (HomeActivity +
/// VodFragment top bar / tabs / bottom bar) with the inline video grid
/// for the selected tab (b8-home-grid).
///
/// Scope is the shell plus the tab grid: the top bar (logo / title /
/// search pill / overflow menu), the category tabs, the glass bottom
/// bar, and the grid itself (same list the B8 category screen shows for
/// the tab, same card look, load-more on scroll). The AppBar blocks
/// below the search pill are the B12 widgets (banner + continue
/// watching, flag-gated) and the B13 placeholder (JS widget blocks).
/// All strings, computed values and glass parameters come from the host
/// (B11HostApi.getHomeShell / getTabVideos), so this page renders
/// exactly what the native home shows, in the current locale.
///
/// Cross-screen navigation always goes through the host (plan §4.4):
/// history / keep / settings / category open via host calls, and grid
/// card taps open detail through the B8 host chain
/// (B8HostApi.openCategoryItem, same call the B8 page makes); the
/// native home underneath stays alive while this screen is on top
/// (back lands on the native home — one extra back press to exit).
///
/// Refresh: Dart cannot subscribe to the native EventBus, so this page
/// re-fetches on first load and on every resume (C1/C2 pattern), which
/// covers returning from playback and from other screens. A native
/// dialog produces no lifecycle event, so state changed by a dialog
/// (site switch, sync) is picked up by the explicit refetch after the
/// awaited dialog call, not by resume. The grid reloads page 1 whenever
/// the shell reloads (load epoch) or the selected tab changes.
///
/// Deliberate deviations from native (see docs/b8-b11-home.md and
/// docs/b8-home-glass.md): the glass bar is a layered-filter
/// approximation of the native Kyant backdrop stack (blur + lens
/// magnification + color-control washes + highlight/fringe; no true
/// per-pixel lens or channel-split dispersion — see
/// docs/b8-home-glass-v2.md); the logo is a
/// Material icon, not the ic_logo drawable; the title uses single-line
/// ellipsis instead of marquee; the pinned tab bar paints the host
/// wallpaper color opaque once content scrolls under it, where native
/// keeps it transparent (native offsets the scrolling view below the
/// AppBar, so nothing ever paints under the tab bar — see
/// docs/b8-home-sticky-tabs.md); the sync-dialog-close refresh is still
/// resume-only; video/gif wallpapers show their static frame-0
/// snapshot, without motion.
class B11HomePage extends StatefulWidget {
  const B11HomePage({super.key});

  @override
  State<B11HomePage> createState() => _B11HomePageState();
}

class _B11HomePageState extends State<B11HomePage>
    with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  final B11HostApi _b11Api = B11HostApi();
  final C1HostApi _c1Api = C1HostApi();

  /// B8 b8-home-sticky-tabs: the one scroll controller for the whole
  /// home (CustomScrollView). Shared with the tab grid so its
  /// load-more trigger reads the same scroll position the user sees;
  /// owned here, never disposed by the grid.
  final ScrollController _scrollController = ScrollController();

  B11HomeScreen? _screen;
  String? _error;
  int _selectedTab = 0;

  /// B8 B12: shell load generation, passed to the B12 block widgets so
  /// they reload on resume / site switch but not on tab switches (a
  /// tab switch rebuilds this page without changing the epoch, and the
  /// banner must not reset to page 0).
  int _homeLoadEpoch = 0;

  /// errorToast already shown for the current snapshot (shown once).
  String _shownToast = '';

  /// B8 b8-home-fabs: CustomFabBehavior mirror. _fabTopVisible tracks
  /// the top FAB (shown once the user scrolls down, hidden on scroll
  /// back up); _fabsScrolledAway tracks the browse/link FAB (hidden
  /// while scrolling down, shown on scroll back up). Both reset on
  /// tab switch (native setFabVisible on page change) and on top-tap.
  bool _fabTopVisible = false;
  bool _fabsScrolledAway = false;
  double _lastScrollPixels = 0;

  /// B8 b8-home-fabs: filter generation for the tab grid, bumped when
  /// the native TypeFilterDialog applies a filter, so the grid
  /// reloads page 1 with the host-side overrides without resetting
  /// the B12/B13 blocks (a shell epoch bump would reset the banner).
  int _filterEpoch = 0;

  /// B8 b8-home-fabs: TypeFilterDialog live-selection polling (B19/B20
  /// pattern). The timer runs only while the dialog's @async call is
  /// pending; _appliedTypeFilterVersion is the last state version
  /// applied to the tab/grid (host resets it to 0 on open).
  Timer? _typeFilterPollTimer;
  bool _typeFilterPolling = false;
  int _appliedTypeFilterVersion = 0;

  /// True while the fallback close is already scheduled (one shot per
  /// snapshot, so a hk:// / WebHome site does not close twice).
  bool _fallbackClosing = false;

  /// Grid-area tab swipe (native ViewPager semantics via the Lab
  /// GroupSwipeListener port, see docs/b8-home-swipe-fix.md): a
  /// Listener observes pointer events without entering the gesture
  /// arena, so vertical scrolling, the banner/row horizontal scrolls
  /// and card taps are unaffected by construction. The drag offset
  /// lives in a ValueNotifier, so pointer moves never call setState
  /// or rebuild the grid — only the _SwipeTranslate wrappers rebuild.
  final ValueNotifier<double> _swipeOffset = ValueNotifier<double>(0);
  late final AnimationController _settleController;
  final Tween<double> _swipeTween = Tween<double>(begin: 0.0, end: 0.0);
  late final Animation<double> _settleAnimation;
  int? _swipePointer;
  Offset? _swipeDownPosition;
  VelocityTracker? _swipeTracker;
  double _swipeBaseX = 0;
  bool _swipeHorizontal = false;
  bool _swipeVertical = false;
  int _swipeFlingDelta = 0;

  /// Tab index the swipe offset is synced to: a tab change from any
  /// path other than the swipe settle (chip tap, dialog, snapshot
  /// clamp) drops a stale offset in build(), mirroring native
  /// render() zeroing translationX on content swap (Lab D4).
  int _swipeTab = 0;

  /// Pinned tab bar key: swipe gating measures the bar's screen rect
  /// (drags starting above its bottom edge are header/chips drags,
  /// never grid swipes).
  final GlobalKey _tabBarKey = GlobalKey();

  /// Per-tab scroll positions, keyed by tab identity (typeId +
  /// typeJson, the grid's own reload identity): native kept one
  /// TypeFragment scroll position per pager page. Identity keys
  /// survive resume and tab reorder, and never restore a stale
  /// offset across a site switch.
  final Map<String, double> _tabScrollOffsets = {};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _scrollController.addListener(_onFabScroll);
    _settleController = AnimationController(vsync: this);
    _settleAnimation = _swipeTween.animate(
      CurvedAnimation(
        parent: _settleController,
        curve: Curves.decelerate,
      ),
    );
    _settleAnimation.addListener(() {
      _swipeOffset.value = _settleAnimation.value;
    });
    _load();
  }

  @override
  void dispose() {
    _typeFilterPollTimer?.cancel();
    _settleController.dispose();
    _swipeOffset.dispose();
    _scrollController.removeListener(_onFabScroll);
    WidgetsBinding.instance.removeObserver(this);
    _scrollController.dispose();
    super.dispose();
  }

  /// CustomFabBehavior, on the unified scroll controller: scrolling
  /// down (pixels increase, native dyConsumed > 0) shows the top FAB
  /// and hides browse/link; scrolling back up hides top and re-shows
  /// browse/link. No scroll events fire when the content cannot
  /// scroll, so the FABs stay in their rest state.
  void _onFabScroll() {
    if (!_scrollController.hasClients) return;
    final double pixels = _scrollController.position.pixels;
    final double delta = pixels - _lastScrollPixels;
    _lastScrollPixels = pixels;
    if (delta == 0) return;
    final bool topVisible = delta > 0;
    final bool scrolledAway = delta > 0;
    if (topVisible != _fabTopVisible ||
        scrolledAway != _fabsScrolledAway) {
      setState(() {
        _fabTopVisible = topVisible;
        _fabsScrolledAway = scrolledAway;
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    _fallbackClosing = false;
    try {
      final B11HomeScreen screen = await _b11Api.getHomeShell();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _error = null;
        _homeLoadEpoch++;
        if (_selectedTab >= screen.tabs.length) _selectedTab = 0;
      });
      _maybeShowToast(screen);
      _maybeCloseForFallback(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  void _maybeShowToast(B11HomeScreen screen) {
    if (screen.errorToast.isEmpty || screen.errorToast == _shownToast) {
      return;
    }
    _shownToast = screen.errorToast;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(screen.errorToast)),
    );
  }

  /// hk:// miniapp sites and WebHome-themed sites stay native (plan
  /// §4.4). hk:// closes this screen (the native miniapp page takes
  /// over, and hk wins when a site is both, like native loadHome);
  /// WebHome opens the minimal native fallback shell
  /// (WebHomeShellActivity, delete-native plan §6 R1 / §7 D6) on top
  /// of this screen, and backing out of the shell lands back here.
  /// The host refuses to reopen the shell for a site the user already
  /// backed out of, so the resume reload cannot loop it open.
  void _maybeCloseForFallback(B11HomeScreen screen) {
    if (_fallbackClosing || (!screen.isHkHome && !screen.isWebHome)) {
      return;
    }
    _fallbackClosing = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _openFallback(screen);
    });
  }

  Future<void> _openFallback(B11HomeScreen screen) async {
    if (screen.isHkHome) {
      await _c1Api.closeScreen();
    } else {
      try {
        await _b11Api.openWebHomeShell();
      } catch (_) {
        // The shell failed to open; the home grid stays as-is.
      }
    }
  }

  Future<void> _close() async {
    await _c1Api.closeScreen();
  }

  Future<void> _openSiteDialog() async {
    try {
      final bool changed = await _b11Api.openSiteDialog();
      if (changed && mounted) _load();
    } catch (_) {
      // The dialog failed to open; the home stays as-is.
    }
  }

  Future<void> _reloadConfig() async {
    try {
      await _b11Api.reloadConfig();
      if (mounted) _load();
    } catch (_) {
      // The reload failed; the home stays as-is.
    }
  }

  /// browse FAB: open the native TypeFilterDialog. While its @async
  /// call is pending, poll the live selection (B19/B20 pattern) so
  /// type taps switch the tab and filter picks reload the grid
  /// without closing the panel, like native. The dismiss completion
  /// applies the final state, so a missed poll cannot lose it.
  Future<void> _openTypeFilterDialog() async {
    final B11HomeScreen? screen = _screen;
    if (screen == null || screen.tabs.isEmpty) return;
    _appliedTypeFilterVersion = 0;
    _typeFilterPollTimer?.cancel();
    _typeFilterPollTimer = Timer.periodic(
      const Duration(milliseconds: 300),
      (_) => _pollTypeFilterState(),
    );
    try {
      final B11TypeFilterState state =
          await _b11Api.openTypeFilterDialog(_selectedTab);
      if (!mounted) return;
      if (state.version != _appliedTypeFilterVersion) {
        _applyTypeFilterState(state);
      }
    } catch (_) {
      // The dialog failed to open; the home stays as-is.
    } finally {
      _typeFilterPollTimer?.cancel();
      _typeFilterPollTimer = null;
    }
  }

  /// One live-selection poll: apply the state when the host version
  /// moved since the last applied one. Guarded against overlap (a
  /// slow poll must not interleave with the next tick).
  Future<void> _pollTypeFilterState() async {
    if (_typeFilterPolling) return;
    _typeFilterPolling = true;
    try {
      final B11TypeFilterState state = await _b11Api.getTypeFilterState();
      if (!mounted || !state.dialogOpen) return;
      if (state.version != _appliedTypeFilterVersion) {
        _appliedTypeFilterVersion = state.version;
        _applyTypeFilterState(state);
      }
    } catch (_) {
      // Polling stops on channel error; the dismiss completion is
      // the backstop for the final selection.
      _typeFilterPollTimer?.cancel();
      _typeFilterPollTimer = null;
    } finally {
      _typeFilterPolling = false;
    }
  }

  /// Offset-map key for a tab: the grid's own reload identity, so a
  /// stored position follows the tab across snapshots and never
  /// collides with another site's same-numbered tab.
  String _tabScrollKey(B11HomeTab tab) => '${tab.typeId}\n${tab.typeJson}';

  /// Switches the selected tab, preserving per-tab scroll positions
  /// (native: one TypeFragment scroll position per pager page) and
  /// resetting the FAB flags exactly as a chip tap does (native
  /// setFabVisible on page change: top hides, browse/link re-show).
  /// The single switch path for chip taps, the grid swipe settle and
  /// the type-filter dialog, so all three behave identically apart
  /// from the swipe's slide-in. A switch to the already-selected tab
  /// still resets the FAB flags, as a chip tap does today, but stores
  /// and restores nothing.
  void _switchTab(int index) {
    final B11HomeScreen? screen = _screen;
    if (screen == null || screen.tabs.isEmpty) return;
    int target = index;
    if (target < 0) target = 0;
    if (target > screen.tabs.length - 1) target = screen.tabs.length - 1;
    final int previous = _selectedTab;
    final bool changed = target != previous;
    if (changed &&
        previous >= 0 &&
        previous < screen.tabs.length &&
        _scrollController.hasClients) {
      _tabScrollOffsets[_tabScrollKey(screen.tabs[previous])] =
          _scrollController.position.pixels;
    }
    setState(() {
      _selectedTab = target;
      _fabTopVisible = false;
      _fabsScrolledAway = false;
    });
    if (!changed) return;
    final double? restore =
        _tabScrollOffsets[_tabScrollKey(screen.tabs[target])];
    // No stored offset (first visit to this tab): leave the shared
    // controller alone, exactly as a chip tap does today.
    if (restore == null) return;
    // Restore after the switch rebuild: the grid's page-1 load for
    // the new tab is already in flight (_busy), so its load-more
    // check cannot fire from this jump (see _TabVideoGridState
    // ._onScroll). The jump fires _onFabScroll, so the switch's FAB
    // reset is re-applied in the same callback, before the next
    // frame renders.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scrollController.hasClients) return;
      final double clamped =
          restore.clamp(0.0, _scrollController.position.maxScrollExtent);
      _scrollController.jumpTo(clamped);
      if (_fabTopVisible || _fabsScrolledAway) {
        setState(() {
          _fabTopVisible = false;
          _fabsScrolledAway = false;
        });
      }
    });
  }

  /// Apply a dialog selection: switch to the picked tab and reload
  /// the grid with the host-side filter overrides (filter epoch bump;
  /// the B12/B13 blocks keep their epoch). FAB scroll state resets,
  /// like native setFabVisible on page change.
  void _applyTypeFilterState(B11TypeFilterState state) {
    final B11HomeScreen? screen = _screen;
    if (screen == null || screen.tabs.isEmpty) return;
    _switchTab(state.selectedTabIndex);
    setState(() => _filterEpoch++);
  }

  /// link FAB: open the native LinkDialog (paste-a-link play). The
  /// dialog plays directly, so nothing comes back.
  Future<void> _openLinkDialog() async {
    try {
      await _b11Api.openLinkDialog();
    } catch (_) {
      // The dialog failed to open; the home stays as-is.
    }
  }

  /// top FAB: native onTop — scroll to the top, hide top, re-show
  /// browse/link. The scroll events fired by the animation already
  /// drive the FAB states; the explicit setState mirrors native's
  /// explicit visibility sets after scrollToTop.
  Future<void> _onTopFab() async {
    if (_scrollController.hasClients) {
      await _scrollController.animateTo(
        0,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOut,
      );
    }
    if (!mounted) return;
    setState(() {
      _fabTopVisible = false;
      _fabsScrolledAway = false;
    });
  }

  Future<void> _onMenuItem(B11MenuItem item) async {
    if (item.id == 'refresh') {
      _load();
      return;
    }
    try {
      await _b11Api.openMenuItem(item.id);
    } catch (_) {
      // The host action failed; the home stays as-is.
    }
  }

  void _onBottomBarTap(B11NavItem item) {
    if (item.token == 'vod') return;
    _b11Api.openBottomBarItem(item.token);
  }

  void _onBottomBarLongPress(B11NavItem item) {
    _b11Api.longPressBottomBarItem(item.token);
  }

  // ----------------------------------------------------------------
  // Grid-area tab swipe (native ViewPager + LabActivity
  // GroupSwipeListener semantics, see docs/b8-home-swipe-fix.md)
  // ----------------------------------------------------------------

  /// Whether a tab exists in the swipe direction (+1 next, -1
  /// previous); native LabActivity.hasGroup, indexed directly.
  bool _hasTab(int delta) {
    final B11HomeScreen? screen = _screen;
    if (screen == null) return false;
    final int next = _selectedTab + delta;
    return next >= 0 && next < screen.tabs.length;
  }

  /// Grid-area gating: only drags starting below the pinned tab
  /// bar's bottom edge may switch tabs. The tab bar is pinned, so
  /// its rect is stable once scrolled; at scroll offset 0 its
  /// natural position is measured the same way. A missing or
  /// unlaid-out bar fails safe (no swipe), never misroutes a banner
  /// drag. Drags starting on the banner, the continue-watching row,
  /// the B13 sections or the chips row all start above this edge.
  bool _swipeStartsInGrid(Offset position) {
    final BuildContext? tabBarContext = _tabBarKey.currentContext;
    final RenderObject? renderObject =
        tabBarContext?.findRenderObject();
    final RenderBox? box = renderObject is RenderBox ? renderObject : null;
    if (box == null || !box.hasSize) return false;
    final double barBottom =
        box.localToGlobal(Offset(0, box.size.height)).dy;
    return position.dy >= barBottom;
  }

  void _onSwipePointerDown(PointerDownEvent event) {
    // Track the first pointer only; a second finger never disturbs
    // the drag in progress.
    if (_swipePointer != null) return;
    if (!_swipeStartsInGrid(event.position)) return;
    _swipePointer = event.pointer;
    // A new gesture interrupts a running settle animation and
    // continues from the current offset, without a jump (native
    // onDown: animate().cancel() + swipeDragBaseX).
    _settleController.stop();
    _swipeBaseX = _swipeOffset.value;
    _swipeDownPosition = event.position;
    _swipeHorizontal = false;
    _swipeVertical = false;
    _swipeFlingDelta = 0;
    _swipeTracker = VelocityTracker.withKind(PointerDeviceKind.touch)
      ..addPosition(event.timeStamp, event.position);
  }

  void _onSwipePointerMove(PointerMoveEvent event) {
    final Offset? down = _swipeDownPosition;
    if (event.pointer != _swipePointer || down == null) return;
    _swipeTracker?.addPosition(event.timeStamp, event.position);
    if (_swipeVertical) return;
    final Offset delta = event.position - down;
    if (!_swipeHorizontal) {
      // Direction lock on the first significant move (native
      // onScroll): horizontal wins only if |dx| > slop and
      // |dx| > |dy|; otherwise vertical locks and the drag is left
      // to the scroll view.
      if (delta.dx.abs() > kTouchSlop &&
          delta.dx.abs() > delta.dy.abs()) {
        _swipeHorizontal = true;
      } else if (delta.dy.abs() > kTouchSlop &&
          delta.dy.abs() > delta.dx.abs()) {
        _swipeVertical = true;
        return;
      } else {
        return;
      }
    }
    // Follow the finger; at the ends (no tab in that direction)
    // the 0.35 resistance hints at the end without crossing it.
    // The offset is clamped to one screen width, as native does.
    final double width = MediaQuery.sizeOf(context).width;
    final double raw = _swipeBaseX + delta.dx;
    double tx = raw;
    if ((raw < 0 && !_hasTab(1)) || (raw > 0 && !_hasTab(-1))) {
      tx = _swipeBaseX + (raw - _swipeBaseX) * 0.35;
    }
    _swipeOffset.value = tx.clamp(-width, width);
  }

  void _onSwipePointerUp(PointerUpEvent event) {
    final Offset? down = _swipeDownPosition;
    if (event.pointer != _swipePointer || down == null) return;
    _swipePointer = null;
    _swipeDownPosition = null;
    // Fling (native onFling): recorded, direction from the total
    // displacement sign, settled on release together with the
    // displacement fallback. Requires a fast horizontal-dominant
    // release and at least 50dp of travel.
    final VelocityTracker? tracker = _swipeTracker;
    _swipeTracker = null;
    if (tracker != null) {
      final Velocity velocity = tracker.getVelocity();
      final double vx = velocity.pixelsPerSecond.dx;
      final double vy = velocity.pixelsPerSecond.dy;
      final double dx = event.position.dx - down.dx;
      if (vx.abs() > 500 && vx.abs() > vy.abs() && dx.abs() > 50) {
        _swipeFlingDelta = dx < 0 ? 1 : -1;
      }
    }
    _settleSwipe();
  }

  void _onSwipePointerCancel(PointerCancelEvent event) {
    if (event.pointer != _swipePointer) return;
    _swipePointer = null;
    _swipeDownPosition = null;
    _swipeTracker = null;
    _swipeFlingDelta = 0;
    _bounceSwipeBack();
  }

  /// Release settlement (native settleSwipe): fling first, else the
  /// displacement over max(120dp, 1/4 screen width). Over threshold
  /// the new tab slides in once (~220ms) from the gesture
  /// direction; otherwise it bounces back (~180ms). A switch during
  /// a grid load queues exactly as a chip tap does (the grid's
  /// serial guard: pending reload), so no special handling here.
  void _settleSwipe() {
    int delta = _swipeFlingDelta;
    _swipeFlingDelta = 0;
    if (delta == 0 && _swipeHorizontal) {
      final double tx = _swipeOffset.value;
      final double threshold =
          math.max(120.0, MediaQuery.sizeOf(context).width / 4);
      if (tx < -threshold) {
        delta = 1;
      } else if (tx > threshold) {
        delta = -1;
      }
    }
    if (delta == 0 || !_hasTab(delta)) {
      _bounceSwipeBack();
      return;
    }
    final double width = MediaQuery.sizeOf(context).width;
    final double enterX = delta > 0 ? width : -width;
    _switchTab(_selectedTab + delta);
    _swipeTab = _selectedTab;
    _animateSwipeOffset(
      0,
      const Duration(milliseconds: 220),
      from: enterX,
    );
  }

  void _bounceSwipeBack() {
    _swipeFlingDelta = 0;
    _animateSwipeOffset(0, const Duration(milliseconds: 180));
  }

  void _animateSwipeOffset(
    double target,
    Duration duration, {
    double? from,
  }) {
    _settleController.stop();
    final double start = from ?? _swipeOffset.value;
    _swipeOffset.value = start;
    _swipeTween.begin = start;
    _swipeTween.end = target;
    _settleController.duration = duration;
    _settleController.forward(from: 0);
  }

  @override
  Widget build(BuildContext context) {
    final B11HomeScreen? screen = _screen;
    // A tab change from any path other than the swipe settle (chip
    // tap, dialog, snapshot clamp) drops a stale drag offset,
    // mirroring native render() zeroing translationX on content
    // swap. The settle path syncs _swipeTab before the rebuild, so
    // the slide-in is unaffected. No setState here: only the offset
    // notifier and the settle controller are touched.
    if (_swipeTab != _selectedTab) {
      _swipeTab = _selectedTab;
      _settleController.stop();
      _swipeOffset.value = 0;
    }
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) async {
        if (didPop) return;
        await _close();
      },
      child: Scaffold(
        // Transparent once the shell has loaded so the wallpaper shows
        // through; the loading/error state keeps the default surface.
        backgroundColor: screen == null ? null : Colors.transparent,
        body: screen == null
            ? SafeArea(child: _buildLoadingOrError())
            : _buildHomeStack(screen),
      ),
    );
  }

  /// Home layers (b8-home-glass): the full-screen wallpaper at the
  /// bottom (native: CustomWallView as child 0 of android.R.id.content,
  /// behind all content), the content column inside the safe area, and
  /// the floating glass capsule overlaid at the bottom. The Positioned
  /// capsule only hit-tests on the bar itself, so content beneath the
  /// overlay margins stays tappable.
  Widget _buildHomeStack(B11HomeScreen screen) {
    return Stack(
      children: [
        Positioned.fill(
          child: _WallpaperBackground(screen: screen),
        ),
        SafeArea(child: _buildHome(screen)),
        Positioned(
          left: _glassBarInsetX,
          right: _glassBarInsetX,
          bottom: _glassBarFloat + MediaQuery.paddingOf(context).bottom,
          child: _GlassBottomBar(
            screen: screen,
            onTap: _onBottomBarTap,
            onLongPress: _onBottomBarLongPress,
          ),
        ),
        // B8 b8-home-fabs: the three floating action buttons, anchored
        // like native (fragment_vod.xml: 16dp margin from the pager's
        // end|bottom anchor; the pager ends 56dp above the window
        // bottom — see _pagerBottom). Only one is ever visible; the
        // overlay itself is zero-size when all three are hidden.
        Positioned(
          right: _fabMargin,
          bottom: _pagerBottom +
              _fabMargin +
              MediaQuery.paddingOf(context).bottom,
          child: _buildFabOverlay(screen),
        ),
      ],
    );
  }

  /// The three floating action buttons (b8-home-fabs), mirroring
  /// fragment_vod.xml + VodFragment.setFabVisible + CustomFabBehavior:
  /// hk/WebHome screens show none (hk closes the screen, WebHome opens
  /// the fallback shell on top); with categories the browse (tune) FAB
  /// shows and link hides, without categories it is the other way round;
  /// scrolling down swaps the visible FAB for the top FAB, scrolling
  /// back up swaps it back. The glass background is setting_card_bg
  /// (#BFFFFFFF light / #BF1E1E1E night, no live blur — native
  /// setFabGlass paints the same translucent color). Icons are
  /// Material approximations of the native ic_fab_* drawables (same
  /// as the nav bar icons, see docs/b8-b11-home.md V5). heroTag is
  /// null: the switcher briefly holds two FABs during the scale
  /// transition, and duplicate hero tags would throw.
  Widget _buildFabOverlay(B11HomeScreen screen) {
    if (screen.isHkHome || screen.isWebHome) {
      return const SizedBox.shrink();
    }
    final Color fabBg = screen.darkMode
        ? const Color(0xBF1E1E1E)
        : const Color(0xBFFFFFFF);
    // colorOnSecondaryContainer approximation (native FAB tint).
    final Color fabIcon =
        screen.darkMode ? Colors.white : const Color(0xFF1D1B20);
    Widget? fab;
    if (_fabTopVisible) {
      fab = FloatingActionButton(
        key: const ValueKey('fab-top'),
        heroTag: null,
        backgroundColor: fabBg,
        foregroundColor: fabIcon,
        onPressed: _onTopFab,
        child: const Icon(Icons.arrow_upward),
      );
    } else if (!_fabsScrolledAway) {
      if (screen.tabs.isNotEmpty) {
        fab = FloatingActionButton(
          key: const ValueKey('fab-browse'),
          heroTag: null,
          backgroundColor: fabBg,
          foregroundColor: fabIcon,
          onPressed: _openTypeFilterDialog,
          child: const Icon(Icons.tune),
        );
      } else {
        fab = FloatingActionButton(
          key: const ValueKey('fab-link'),
          heroTag: null,
          backgroundColor: fabBg,
          foregroundColor: fabIcon,
          onPressed: _openLinkDialog,
          child: const Icon(Icons.link),
        );
      }
    }
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 150),
      transitionBuilder: (Widget child, Animation<double> animation) =>
          ScaleTransition(scale: animation, child: child),
      child: fab ?? const SizedBox.shrink(key: ValueKey('fab-none')),
    );
  }

  Widget _buildLoadingOrError() {
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 16),
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: _load,
              ),
            ],
          ),
        ),
      );
    }
    return const Center(child: CircularProgressIndicator());
  }

  Widget _buildHome(B11HomeScreen screen) {
    // B8 b8-home-sticky-tabs: one unified scroll, mirroring the
    // native AppBarLayout (fragment_vod.xml). The toolbar, search
    // pill, banner, continue-watching row and JS sections scroll away
    // (native: layout_scrollFlags="scroll"); the category tab bar
    // pins at the top (native: the AppBar's last child, no scroll
    // flags); the grid keeps scrolling under it. Scrolling back to
    // the top expands the header back into place. The tab bar used
    // to sit in a fixed Column with only the grid scrolling, so the
    // chips could never reach the top and a swipe starting above
    // the grid scrolled nothing at all.
    // B8 b8-home-swipe: the Listener observes pointer events
    // without entering the gesture arena, so vertical scrolling,
    // the banner/row horizontal scrolls and card taps keep their
    // own paths; the swipe handlers gate on the pointer-down
    // position (below the pinned tab bar) and never consume. The
    // glass bar and FABs are siblings above this subtree in the
    // Stack, so their gestures never reach this observer.
    return Listener(
      behavior: HitTestBehavior.translucent,
      onPointerDown: _onSwipePointerDown,
      onPointerMove: _onSwipePointerMove,
      onPointerUp: _onSwipePointerUp,
      onPointerCancel: _onSwipePointerCancel,
      child: CustomScrollView(
        controller: _scrollController,
        slivers: [
          SliverToBoxAdapter(child: _buildTopBar(screen)),
          SliverToBoxAdapter(child: _buildSearchPill(screen)),
          // B8 B12: the banner carousel + continue-watching row. The
          // widgets read the B12 flag themselves: off renders the same
          // icon-only placeholders B11 showed, on renders the real
          // blocks. The epoch reloads them on resume / site switch.
          SliverToBoxAdapter(
              child: B12BannerWidget(epoch: _homeLoadEpoch)),
          SliverToBoxAdapter(
              child: B12ContinueWidget(epoch: _homeLoadEpoch)),
          // B8 B13: the JS widget sections. The widget reads the B13
          // flag itself: off renders the same icon-only placeholder B11
          // showed, on renders the real sections. Same epoch as B12.
          SliverToBoxAdapter(
              child: B13WidgetsWidget(epoch: _homeLoadEpoch)),
          if (screen.tabs.isNotEmpty)
            SliverPersistentHeader(
              pinned: true,
              delegate: _StickyTabBarDelegate(
                builder: () => _buildTabBar(screen),
                epoch: _homeLoadEpoch,
                selectedTab: _selectedTab,
                pinnedBackground: Color(screen.wallpaperColor),
              ),
            ),
          _buildTabContent(screen),
        ],
      ),
    );
  }

  /// Native toolbar: logo + title (AppBar in fragment_vod.xml).
  /// Logo tap opens the read-only history dialog, title tap opens the
  /// site dialog; long-press on either reloads the config.
  Widget _buildTopBar(B11HomeScreen screen) {
    return SizedBox(
      height: 56,
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.local_movies_outlined),
            onPressed: () async {
              try {
                await _b11Api.openLogoDialog();
              } catch (_) {
                // The dialog failed to open; the home stays as-is.
              }
            },
            onLongPress: _reloadConfig,
          ),
          Expanded(
            child: GestureDetector(
              onTap: _openSiteDialog,
              onLongPress: _reloadConfig,
              child: Text(
                screen.title,
                style: const TextStyle(fontSize: 16),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// Native search pill (searchBar in fragment_vod.xml, 40dp): tap to
  /// search, long-press to search the current site, history icon opens
  /// history, overflow opens the ⋮ menu.
  Widget _buildSearchPill(B11HomeScreen screen) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Container(
        height: 40,
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Row(
          children: [
            Expanded(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => _b11Api.openSearch(),
                onLongPress: () => _b11Api.openSearchCurrentSite(),
                child: Row(
                  children: [
                    const SizedBox(width: 12),
                    const Icon(Icons.search, size: 20),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        screen.searchHint,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            IconButton(
              icon: const Icon(Icons.history, size: 20),
              tooltip: screen.historyTooltip,
              onPressed: () => _b11Api.openHistory(),
            ),
            PopupMenuButton<B11MenuItem>(
              icon: const Icon(Icons.more_vert, size: 20),
              tooltip: screen.moreTooltip,
              onSelected: _onMenuItem,
              itemBuilder: (BuildContext context) => screen.menuItems
                  .map(
                    (B11MenuItem item) => PopupMenuItem<B11MenuItem>(
                      value: item,
                      child: Text(item.label),
                    ),
                  )
                  .toList(),
            ),
          ],
        ),
      ),
    );
  }

  /// Category tabs (TypeAdapter chips, native: 14sp text).
  Widget _buildTabBar(B11HomeScreen screen) {
    if (screen.tabs.isEmpty) return const SizedBox.shrink();
    final Color accent = Color(screen.accentColor);
    return SizedBox(
      key: _tabBarKey,
      height: 44,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8),
        itemCount: screen.tabs.length,
        itemBuilder: (BuildContext context, int index) {
          final bool selected = index == _selectedTab;
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
            child: ChoiceChip(
              label: Text(
                screen.tabs[index].typeName,
                style: const TextStyle(fontSize: 14),
              ),
              selected: selected,
              selectedColor: accent,
              labelStyle: TextStyle(
                color: selected
                    ? Colors.white
                    : Theme.of(context).colorScheme.onSurface,
              ),
              onSelected: (_) => _switchTab(index),
            ),
          );
        },
      ),
    );
  }

  /// The tab content as a sliver for the home CustomScrollView
  /// (b8-home-sticky-tabs): the empty states fill the remaining
  /// viewport below the header, the grid renders as slivers.
  Widget _buildTabContent(B11HomeScreen screen) {
    if (screen.emptyTitle.isNotEmpty) {
      return SliverFillRemaining(
        hasScrollBody: false,
        child: _SwipeTranslate(
          offset: _swipeOffset,
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.inbox_outlined, size: 48),
                  const SizedBox(height: 12),
                  Text(
                    screen.emptyTitle,
                    style: const TextStyle(fontSize: 16),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    screen.emptyDescription,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: _load,
                    child: Text(screen.emptyActionLabel),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }
    if (screen.tabs.isEmpty) {
      return SliverFillRemaining(
        hasScrollBody: false,
        child: _SwipeTranslate(
          offset: _swipeOffset,
          child: Center(
            child: Text(
              screen.tabPlaceholderEmptyText,
              textAlign: TextAlign.center,
            ),
          ),
        ),
      );
    }
    // Inline video grid for the selected tab (b8-home-grid): the same
    // list the B8 category screen shows, rendered here. The grid
    // reloads page 1 on tab switch (new tab), on shell reload (new
    // epoch) and on dialog filter picks (new filter epoch); folder
    // cards drill down into the /category screen.
    final B11HomeTab tab = screen.tabs[_selectedTab];
    return _TabVideoGrid(
      tab: tab,
      epoch: _homeLoadEpoch,
      filterEpoch: _filterEpoch,
      emptyText: screen.tabPlaceholderEmptyText,
      bottomPadding: _gridBottomClearance,
      controller: _scrollController,
      swipeOffset: _swipeOffset,
    );
  }
}

/// Applies the home tab-swipe translation (follow-the-finger drag +
/// slide-in, b8-home-swipe) to [child] without rebuilding it on
/// pointer moves: the ValueListenableBuilder's cached child only
/// repaints under a new Transform. The grid is slivers in the shared
/// scroll view, so the translation wraps the tab-content boxes
/// (grid items, empty/error states) instead of the scroll view
/// itself — the pinned tab bar and the header slivers stay fixed,
/// as native (ViewPager content slides, AppBarLayout does not).
class _SwipeTranslate extends StatelessWidget {
  const _SwipeTranslate({
    required this.offset,
    required this.child,
  });

  final ValueListenable<double> offset;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<double>(
      valueListenable: offset,
      builder: (
        BuildContext context,
        double value,
        Widget? child,
      ) {
        return Transform.translate(
          offset: Offset(value, 0),
          child: child,
        );
      },
      child: child,
    );
  }
}

/// Pinned category tab bar (b8-home-sticky-tabs): the Flutter
/// equivalent of the native typeBar, the AppBarLayout's last child
/// with no scroll flags (fragment_vod.xml). Fixed 44dp tall. When
/// content has scrolled underneath (overlapsContent) the bar paints
/// the host wallpaper color opaque: unlike native — where the
/// scrolling view is offset below the AppBar and never paints under
/// the tab bar — sliver content does scroll under a pinned header,
/// and grid cards must not show through the chips. At rest the bar
/// stays transparent so the wallpaper shows through, like native.
class _StickyTabBarDelegate extends SliverPersistentHeaderDelegate {
  const _StickyTabBarDelegate({
    required this.builder,
    required this.epoch,
    required this.selectedTab,
    required this.pinnedBackground,
  });

  /// Builds the tab bar; re-invoked whenever the delegate rebuilds,
  /// so the selection state is always current.
  final Widget Function() builder;

  /// Shell load generation: a new snapshot rebuilds the header.
  final int epoch;
  final int selectedTab;
  final Color pinnedBackground;

  @override
  double get minExtent => 44;

  @override
  double get maxExtent => 44;

  @override
  Widget build(
      BuildContext context, double shrinkOffset, bool overlapsContent) {
    return Container(
      color: overlapsContent ? pinnedBackground : null,
      child: builder(),
    );
  }

  @override
  bool shouldRebuild(_StickyTabBarDelegate oldDelegate) {
    return oldDelegate.epoch != epoch ||
        oldDelegate.selectedTab != selectedTab ||
        oldDelegate.pinnedBackground != pinnedBackground;
  }
}

/// B8 b8-home-glass: the full-screen home wallpaper, painted behind
/// all content (native: CustomWallView, BaseActivity child 0). The
/// image file and fallback color come from the host snapshot, resolved
/// exactly like CustomWallView.load(); a missing or unreadable file
/// degrades to the fallback color, never to a blank background. The
/// wallpaper itself is blurred 3dp when glass is on (native home:
/// ThemeController.applyWallpaperBlur(this, 3)); glass off shows it
/// sharp. Foreground content is never blurred.
class _WallpaperBackground extends StatelessWidget {
  const _WallpaperBackground({required this.screen});

  final B11HomeScreen screen;

  @override
  Widget build(BuildContext context) {
    final Color fallback = Color(screen.wallpaperColor);
    final Widget image = screen.wallpaperPath.isEmpty
        ? Container(color: fallback)
        : Image.file(
            File(screen.wallpaperPath),
            fit: BoxFit.cover,
            errorBuilder: (BuildContext context, Object error,
                StackTrace? stackTrace) {
              return Container(color: fallback);
            },
          );
    if (!screen.glassEnabled) return image;
    return ImageFiltered(
      imageFilter: ImageFilter.blur(sigmaX: 3, sigmaY: 3),
      child: image,
    );
  }
}

/// B8 b8-home-grid: the inline video grid for one home tab. Mirrors the
/// B8 category list (same host data path, same card look, same
/// load-more-on-scroll), without filters or drill-down navigation: a
/// folder card opens the B8 /category screen for the folder, every
/// other card opens detail through the B8 host chain.
///
/// Reload triggers: a new tab (chip switch), or a new shell load epoch
/// (resume / refresh / site switch). Loads are serialized: a reload
/// requested while one is in flight is remembered and runs when the
/// in-flight load settles, and results that arrive for a stale tab or
/// epoch are discarded, so the grid can never show another tab's list.
///
/// B8 b8-home-sticky-tabs: the grid renders as slivers inside the
/// parent page's CustomScrollView (one unified scroll with the pinned
/// tab bar), and its load-more trigger listens to the parent's scroll
/// controller — the same position the user scrolls.
class _TabVideoGrid extends StatefulWidget {
  const _TabVideoGrid({
    required this.tab,
    required this.epoch,
    required this.filterEpoch,
    required this.emptyText,
    required this.bottomPadding,
    required this.controller,
    required this.swipeOffset,
  });

  final B11HomeTab tab;

  /// Shell load generation from the parent page; a change means the
  /// shell refetched (resume / refresh / site switch) and the grid
  /// must reload page 1.
  final int epoch;

  /// B8 b8-home-fabs: filter generation from the parent page; a
  /// change means the native TypeFilterDialog applied a filter and
  /// the grid must reload page 1 with the host-side overrides.
  final int filterEpoch;

  /// Host empty text (widget_empty), shown when the tab has no videos.
  final String emptyText;

  /// Bottom padding reserved inside the scrollable: the native pager
  /// geometry (see _gridBottomClearance), so grid content slides
  /// under the glass bar's top edge while scrolling, as native.
  final double bottomPadding;

  /// The parent CustomScrollView's controller, owned by the page:
  /// the grid listens to it for load-more and never disposes it.
  final ScrollController controller;

  /// B8 b8-home-swipe: the page's drag offset notifier. The grid
  /// only reads it (via _SwipeTranslate wrappers); the page owns
  /// the swipe state and never reassigns the notifier.
  final ValueListenable<double> swipeOffset;

  @override
  State<_TabVideoGrid> createState() => _TabVideoGridState();
}

class _TabVideoGridState extends State<_TabVideoGrid> {
  final B11HostApi _b11Api = B11HostApi();
  final B8HostApi _b8Api = B8HostApi();

  B11TabVideos? _videos;
  List<B11TabVideoItem> _items = [];
  String _siteKey = '';
  String _styleType = 'rect';
  int _columnCount = 1;
  double _imageWidthDp = 0;
  double _imageHeightDp = 0;
  int _nextPage = 1;
  bool _hasMore = false;
  bool _busy = false;
  bool _loadingMore = false;
  bool _pageError = false;
  bool _channelBroken = false;

  /// A reload was requested while a load was in flight; it runs when
  /// the in-flight load settles.
  bool _pendingReload = false;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onScroll);
    _loadPage(1);
  }

  @override
  void didUpdateWidget(_TabVideoGrid oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onScroll);
      widget.controller.addListener(_onScroll);
    }
    if (oldWidget.tab.typeId != widget.tab.typeId ||
        oldWidget.tab.typeJson != widget.tab.typeJson ||
        oldWidget.epoch != widget.epoch ||
        oldWidget.filterEpoch != widget.filterEpoch) {
      _loadPage(1);
    }
  }

  @override
  void dispose() {
    // The controller is owned by the page: only detach the listener.
    widget.controller.removeListener(_onScroll);
    super.dispose();
  }

  void _onScroll() {
    if (!widget.controller.hasClients) return;
    final position = widget.controller.position;
    if (position.maxScrollExtent <= 0) return;
    if (position.pixels >= position.maxScrollExtent - 200) {
      if (_hasMore && !_busy) _loadPage(_nextPage);
    }
  }

  /// Loads one page. Serialized: the host ViewModel serves one
  /// subscription at a time, so a second call while one is in flight is
  /// remembered as a pending reload instead of dropped (a chip switch
  /// during a load must still show the new tab's list).
  Future<void> _loadPage(int page) async {
    if (_busy) {
      _pendingReload = true;
      return;
    }
    final B11HomeTab tab = widget.tab;
    final int epoch = widget.epoch;
    final int filterEpoch = widget.filterEpoch;
    _pendingReload = false;
    setState(() {
      _busy = true;
      _pageError = false;
      _channelBroken = false;
      if (page > 1) _loadingMore = true;
    });
    B11TabVideos? videos;
    try {
      videos = await _b11Api.getTabVideos(tab.typeId, tab.typeJson, page);
    } catch (_) {
      // The channel itself broke; handled below (the host always
      // completes getTabVideos exactly once, including its timeout).
      videos = null;
    }
    if (!mounted) return;
    final bool stale = widget.tab.typeId != tab.typeId ||
        widget.epoch != epoch ||
        widget.filterEpoch != filterEpoch;
    if (!stale && videos != null) {
      final B11TabVideos loaded = videos;
      final bool loadMoreFailed =
          loaded.errorMessage.isNotEmpty && page > 1;
      setState(() {
        _busy = false;
        _loadingMore = false;
        if (!loadMoreFailed) {
          // A load-more failure keeps the list, the style and the
          // pagination state; the user retries by scrolling again.
          // (The error snapshot carries rect/1/0 defaults that must
          // not overwrite the live list's style.)
          _videos = loaded;
          _siteKey = loaded.siteKey;
          _styleType = loaded.styleType;
          _columnCount = loaded.columnCount;
          // Host geometry is in px; Dart lays out in dp.
          _imageWidthDp =
              loaded.cardImageWidthPx / MediaQuery.devicePixelRatioOf(context);
          _imageHeightDp = loaded.cardImageHeightPx /
              MediaQuery.devicePixelRatioOf(context);
          _nextPage = loaded.nextPage;
          _hasMore = loaded.hasMore;
          _pageError = loaded.errorMessage.isNotEmpty;
          if (page == 1) {
            _items = loaded.items;
          } else {
            _items = [..._items, ...loaded.items];
          }
        }
      });
      if (loadMoreFailed) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(loaded.errorMessage)),
        );
      }
    } else {
      setState(() {
        _busy = false;
        _loadingMore = false;
        // A channel break with no snapshot yet shows the empty text
        // with a retry affordance; with a list already on screen the
        // list simply stays (a load-more retry is another scroll).
        if (!stale && videos == null && _videos == null) {
          _channelBroken = true;
        }
      });
    }
    if (stale || _pendingReload) {
      _pendingReload = false;
      _loadPage(1);
    }
  }

  Future<void> _onItemTap(B11TabVideoItem item) async {
    if (item.isFolder) {
      // Folder drill-down, B8 semantics: the /category screen for the
      // folder reuses the parent tab's Class JSON; only the typeId
      // changes. The host builds the route (openTabCategory).
      try {
        await _b11Api.openTabCategory(item.id, widget.tab.typeJson);
      } catch (_) {
        // The host navigation failed; the grid stays as it was.
      }
      return;
    }
    // Same call the B8 page makes, with the same arguments: the host
    // follows the native TypeFragment.onItemClick chain (action card,
    // index-site search, or the detail page). Not a history resume.
    try {
      await _b8Api.openCategoryItem(
        _siteKey,
        item.id,
        item.name,
        // Raw pic: the detail page parses the headers suffixes itself,
        // like native TypeFragment passing item.getPic().
        item.rawPic,
        item.isFolder,
        item.isAction,
        item.action,
        -1,
        '',
        -1,
      );
    } catch (_) {
      // The host navigation failed; the grid stays as it was.
    }
  }

  Future<void> _onItemLongPress(B11TabVideoItem item) async {
    if (item.isFolder || item.isAction) return;
    try {
      await _b8Api.searchCategoryItem(item.name, -1, '', -1);
    } catch (_) {
      // The host navigation failed; the grid stays as it was.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B11TabVideos? videos = _videos;
    if (videos == null) {
      return SliverFillRemaining(
        hasScrollBody: false,
        child: _SwipeTranslate(
          offset: widget.swipeOffset,
          child: _channelBroken
              ? _buildChannelError()
              : const Center(child: CircularProgressIndicator()),
        ),
      );
    }
    if (_pageError && _items.isEmpty) {
      return SliverFillRemaining(
        hasScrollBody: false,
        child: _SwipeTranslate(
          offset: widget.swipeOffset,
          child: _buildHostError(videos),
        ),
      );
    }
    if (_items.isEmpty) {
      return SliverFillRemaining(
        hasScrollBody: false,
        child: _SwipeTranslate(
          offset: widget.swipeOffset,
          child: Center(
            child: Text(
              widget.emptyText,
              textAlign: TextAlign.center,
            ),
          ),
        ),
      );
    }
    // The grid sliver plus the load-more indicator, grouped so the
    // parent CustomScrollView sees one sliver child.
    return SliverMainAxisGroup(
      slivers: [
        _buildList(),
        if (_loadingMore)
          SliverToBoxAdapter(
            child: _SwipeTranslate(
              offset: widget.swipeOffset,
              child: const LinearProgressIndicator(),
            ),
          ),
      ],
    );
  }

  /// Page-load error state: host title + message + retry, mirroring the
  /// B8 category error view. Retry reloads page 1 of this tab.
  Widget _buildHostError(B11TabVideos videos) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              videos.errorTitle,
              style: const TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            if (videos.errorMessage.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                videos.errorMessage,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => _loadPage(1),
              child: Text(videos.retryLabel),
            ),
          ],
        ),
      ),
    );
  }

  /// Channel-break state (no snapshot ever arrived): the host empty
  /// text plus a retry affordance. No host error strings exist on this
  /// path, so the retry is an icon button like the shell's own
  /// loading/error view, and no label is hard-coded.
  Widget _buildChannelError() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              widget.emptyText,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: () => _loadPage(1),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildList() {
    switch (_styleType) {
      case 'list':
        return SliverPadding(
          padding: EdgeInsets.only(bottom: widget.bottomPadding),
          sliver: SliverList(
            delegate: SliverChildBuilderDelegate(
              (BuildContext context, int index) {
                final B11TabVideoItem item = _items[index];
                return _SwipeTranslate(
                  offset: widget.swipeOffset,
                  child: _B11ListRow(
                    key: ValueKey<String>('${item.id}:${item.name}'),
                    item: item,
                    onTap: () => _onItemTap(item),
                    onLongPress: () => _onItemLongPress(item),
                  ),
                );
              },
              childCount: _items.length,
            ),
          ),
        );
      case 'oval':
        return SliverPadding(
          padding: EdgeInsets.fromLTRB(8, 8, 8, 8 + widget.bottomPadding),
          sliver: SliverGrid(
            delegate: SliverChildBuilderDelegate(
              (BuildContext context, int index) {
                final B11TabVideoItem item = _items[index];
                return _SwipeTranslate(
                  offset: widget.swipeOffset,
                  child: _B11OvalCard(
                    key: ValueKey<String>('${item.id}:${item.name}'),
                    item: item,
                    imageWidth: _imageWidthDp,
                    imageHeight: _imageHeightDp,
                    onTap: () => _onItemTap(item),
                    onLongPress: () => _onItemLongPress(item),
                  ),
                );
              },
              childCount: _items.length,
            ),
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: _columnCount,
              crossAxisSpacing: 16,
              mainAxisSpacing: 16,
              // Image + one 14sp name line + a 4dp gap (see _B11OvalCard).
              mainAxisExtent: _imageHeightDp + 24,
            ),
          ),
        );
      case 'rect':
      default:
        return SliverPadding(
          padding: EdgeInsets.only(
              left: 16, right: 16, bottom: 16 + widget.bottomPadding),
          sliver: SliverGrid(
            delegate: SliverChildBuilderDelegate(
              (BuildContext context, int index) {
                final B11TabVideoItem item = _items[index];
                return _SwipeTranslate(
                  offset: widget.swipeOffset,
                  child: VodCard(
                    key: ValueKey<String>('${item.id}:${item.name}'),
                    picUrl: item.picUrl,
                    picReferer: item.picReferer,
                    picCookie: item.picCookie,
                    picUserAgent: item.picUserAgent,
                    picExtraHeadersJson: item.picExtraHeadersJson,
                    fallbackPicUrl: item.fallbackPicUrl,
                    placeholderText: item.placeholderText,
                    placeholderColor: item.placeholderColor,
                    imageHeight: _imageHeightDp,
                    vodName: item.nameVisible ? item.name : '',
                    remarks: item.remarks,
                    remarksVisible: item.remarkVisible,
                    siteName: item.siteName,
                    siteVisible: item.siteVisible,
                    // Grid cards have no playback badge or progress bar.
                    playbackText: null,
                    progress: null,
                    deleteMode: false,
                    onTap: () => _onItemTap(item),
                    onLongPress: () => _onItemLongPress(item),
                  ),
                );
              },
              childCount: _items.length,
            ),
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: _columnCount,
              crossAxisSpacing: 16,
              mainAxisSpacing: 16,
              // The info area is always two lines (see VodCard): the
              // remarks line is transparent when the item hides it.
              mainAxisExtent: VodCard.totalHeight(
                imageHeight: _imageHeightDp,
                hasProgress: false,
                twoLines: true,
              ),
            ),
          ),
        );
    }
  }
}

/// List-style row, mirroring adapter_vod_list.xml: 40dp thumb + name
/// 14sp + remark 12sp, 8dp margins, 12dp padding. Deliberately
/// duplicated from the B8 page's row (B11 item type): each batch keeps
/// its own copy so batches stay independently revertible.
class _B11ListRow extends StatelessWidget {
  const _B11ListRow({
    super.key,
    required this.item,
    required this.onTap,
    required this.onLongPress,
  });

  final B11TabVideoItem item;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              SizedBox(
                width: 40,
                height: 40,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: PicImage(
                    picUrl: item.picUrl,
                    picReferer: item.picReferer,
                    picCookie: item.picCookie,
                    picUserAgent: item.picUserAgent,
                    picExtraHeadersJson: item.picExtraHeadersJson,
                    fallbackPicUrl: item.fallbackPicUrl,
                    placeholderText: item.placeholderText,
                    placeholderColor: item.placeholderColor,
                    height: 40,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      item.nameVisible ? item.name : '',
                      style: const TextStyle(fontSize: 14),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (item.remarkVisible) ...[
                      const SizedBox(height: 2),
                      Text(
                        item.remarks,
                        style: const TextStyle(fontSize: 12),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Oval-style grid cell, mirroring adapter_vod_oval.xml: circular image
/// (host spec size) + name 14sp below. Deliberately duplicated from the
/// B8 page's cell (B11 item type): each batch keeps its own copy so
/// batches stay independently revertible.
class _B11OvalCard extends StatelessWidget {
  const _B11OvalCard({
    super.key,
    required this.item,
    required this.imageWidth,
    required this.imageHeight,
    required this.onTap,
    required this.onLongPress,
  });

  final B11TabVideoItem item;
  final double imageWidth;
  final double imageHeight;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      onLongPress: onLongPress,
      borderRadius: BorderRadius.circular(8),
      child: Column(
        children: [
          SizedBox(
            width: imageWidth,
            height: imageHeight,
            child: ClipOval(
              child: PicImage(
                picUrl: item.picUrl,
                picReferer: item.picReferer,
                picCookie: item.picCookie,
                picUserAgent: item.picUserAgent,
                picExtraHeadersJson: item.picExtraHeadersJson,
                fallbackPicUrl: item.fallbackPicUrl,
                placeholderText: item.placeholderText,
                placeholderColor: item.placeholderColor,
                height: imageHeight,
              ),
            ),
          ),
          const SizedBox(height: 4),
          Text(
            item.nameVisible ? item.name : '',
            style: const TextStyle(fontSize: 14),
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ],
      ),
    );
  }
}

// Native glass slot geometry (GlassNavBar.kt, activity_home.xml):
// the floating capsule is 64dp tall, floats 16dp above the bottom of
// its 88dp slot, with 24dp side insets — the bar's top edge is 80dp
// above the window bottom. The pager ends 56dp above the window
// bottom (activity_home.xml: the fragment container is layout_above
// the 56dp BottomNavigationView), so the bar's top 24dp overlaps the
// pager. The grid reserves the pager end plus the native 8dp grid
// bottom padding (fragment_type.xml, clipToPadding=false): at
// maximum scroll the last row's bottom 16dp sits under the bar, and
// rows slide under the bar's top edge while scrolling, as native
// (b8-glass-geometry; the old 88dp clearance starved the backdrop).
const double _glassBarHeight = 64;
const double _glassBarFloat = 16;
const double _glassBarInsetX = 24;
const double _pagerBottom = 56;
const double _nativeGridPaddingBottom = 8;
const double _gridBottomClearance = _pagerBottom + _nativeGridPaddingBottom;

/// Native FAB margin (fragment_vod.xml: all three FABs use a 16dp
/// margin from the pager's end|bottom anchor).
const double _fabMargin = 16;

/// The Dart copy of the native glass bottom bar (HomeActivity +
/// GlassNavBar): a floating capsule (64dp tall, 24dp side insets, 16dp
/// float — the native 88dp glass slot), the same items in the same
/// order, the same selection pill, the same glass parameters from the
/// host snapshot. A horizontal drag on the bar slides the single
/// selection pill with the finger and switches tabs on release
/// (native DampedDragAnimation, simplified — see
/// docs/b8-glass-geometry-fix.md); taps and the live long-press are
/// unchanged. Glass mode layers a BackdropFilter blur +
/// lens-magnification matrix over the wallpaper and scrolled content,
/// with native-aligned color-control washes, a soft-light saturation
/// lift, a top highlight and a dispersion fringe (see
/// docs/b8-home-glass-v2.md); plain mode (glass off) keeps the
/// capsule shape but renders opaque (see docs/b8-home-glass.md).
/// Icons are Material approximations of the native ic_nav_* drawables
/// (see docs/b8-b11-home.md V5).
class _GlassBottomBar extends StatefulWidget {
  const _GlassBottomBar({
    required this.screen,
    required this.onTap,
    required this.onLongPress,
  });

  final B11HomeScreen screen;
  final ValueChanged<B11NavItem> onTap;
  final ValueChanged<B11NavItem> onLongPress;

  @override
  State<_GlassBottomBar> createState() => _GlassBottomBarState();
}

class _GlassBottomBarState extends State<_GlassBottomBar> {
  /// Pill position in tab-index units (fractional while dragging or
  /// animating). A TweenAnimationBuilder animates toward this target
  /// in ~220ms; during a drag the duration is zero so the pill
  /// follows the finger directly.
  double _pillTarget = 0;

  /// Last animated pill position, cached by the pill builder (plain
  /// field write, no setState): the drag-start position when a slide
  /// is interrupted mid-flight.
  double _pillCurrent = 0;

  bool _dragging = false;
  double _dragStartX = 0;
  double _dragStartIndex = 0;

  @override
  void initState() {
    super.initState();
    final int selected = _selectedIndex(_visibleItems(widget.screen));
    _pillTarget = (selected >= 0 ? selected : 0).toDouble();
    _pillCurrent = _pillTarget;
  }

  @override
  void didUpdateWidget(_GlassBottomBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_dragging) return;
    final int selected = _selectedIndex(_visibleItems(widget.screen));
    if (selected >= 0 && selected.toDouble() != _pillTarget) {
      // Plain assignment: build() runs right after didUpdateWidget,
      // and the new target animates the pill to the selected tab.
      // This also heals a drag-settle the host did not confirm: the
      // pill slides back to the selection the snapshot reports.
      _pillTarget = selected.toDouble();
    }
  }

  List<B11NavItem> _visibleItems(B11HomeScreen screen) =>
      screen.navItems.where((B11NavItem item) => item.visible).toList();

  int _selectedIndex(List<B11NavItem> items) => items.indexWhere(
        (B11NavItem item) => item.token == widget.screen.selectedNavToken,
      );

  IconData _iconFor(String token) {
    switch (token) {
      case 'live':
        return Icons.live_tv_outlined;
      case 'drama':
        return Icons.movie_outlined;
      case 'following':
        return Icons.subscriptions_outlined;
      case 'local_source':
        return Icons.folder_open_outlined;
      case 'setting':
        return Icons.settings_outlined;
      case 'vod':
      default:
        return Icons.home_outlined;
    }
  }

  @override
  Widget build(BuildContext context) {
    final B11HomeScreen screen = widget.screen;
    final List<B11NavItem> items = _visibleItems(screen);
    if (items.isEmpty) return const SizedBox.shrink();
    final Color accent = Color(screen.accentColor);
    final Color surface = screen.darkMode
        ? const Color(0xFF1E1E20)
        : const Color(0xFFF4F4F6);
    final Color unselected = screen.darkMode
        ? Colors.white.withAlpha(184)
        : Colors.black.withAlpha(158);
    final BorderRadius capsule = BorderRadius.circular(_glassBarHeight / 2);
    final Widget rowContent = Row(
        children: items.map((B11NavItem item) {
          final bool selected = item.token == screen.selectedNavToken;
          final Color color = selected ? accent : unselected;
          final Widget label = Text(
            item.label,
            style: TextStyle(fontSize: 11, color: color),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          );
          final Widget icon = Stack(
            clipBehavior: Clip.none,
            children: [
              Icon(_iconFor(item.token), size: 22, color: color),
              if (item.badgeCount > 0)
                Positioned(
                  right: -6,
                  top: -4,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 1,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.red,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      item.badgeCount > 99
                          ? '99+'
                          : item.badgeCount.toString(),
                      style: const TextStyle(
                        fontSize: 9,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ),
            ],
          );
          final Widget content = Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              icon,
              const SizedBox(height: 2),
              label,
            ],
          );
          // No per-item pill background: the shared sliding pill
          // overlay below draws the selection background, so taps
          // and bar drags share one pill.
          return Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => widget.onTap(item),
              onLongPress: () => widget.onLongPress(item),
              child: Center(child: content),
            ),
          );
        }).toList(),
    );
    // Sliding selection pill + bar drag (b8-glass-geometry): one pill
    // overlay shared by taps and drags, positioned in tab-index units
    // so it can follow the finger continuously and animate to the
    // selected tab. The drag recognizer lives on the bar overlay only
    // (self-contained: bar drags never reach the grid, grid drags
    // never reach the bar); the gesture arena disambiguates it from
    // the item tap/long-press recognizers — a press without movement
    // past slop still taps, a hold still long-presses, movement past
    // slop hands the gesture to the drag (native inspectDragGestures
    // semantics). The pill is clipped to the capsule so end-overshoot
    // never pokes outside the bar in the opaque path.
    final Widget barContent = LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        if (!constraints.maxWidth.isFinite) return rowContent;
        final double cellWidth = constraints.maxWidth / items.length;
        final double pillWidth = (cellWidth - 10).clamp(40.0, 60.0);
        const double pillHeight = 48;
        final int selected = _selectedIndex(items);
        final bool showPill = _dragging || selected >= 0;
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onHorizontalDragStart: (DragStartDetails details) {
            setState(() {
              _dragging = true;
              _dragStartX = details.globalPosition.dx;
              _dragStartIndex = _pillCurrent;
              _pillTarget = _pillCurrent;
            });
          },
          onHorizontalDragUpdate: (DragUpdateDetails details) {
            final double raw = _dragStartIndex +
                (details.globalPosition.dx - _dragStartX) / cellWidth;
            final double maxIndex = (items.length - 1).toDouble();
            final double damped = raw < 0
                ? raw * 0.3
                : (raw > maxIndex
                    ? maxIndex + (raw - maxIndex) * 0.3
                    : raw);
            setState(() => _pillTarget = damped);
          },
          onHorizontalDragEnd: (DragEndDetails details) {
            final int target =
                _pillTarget.round().clamp(0, items.length - 1);
            final bool changed = target != selected;
            setState(() {
              _dragging = false;
              _pillTarget = target.toDouble();
            });
            if (changed) widget.onTap(items[target]);
          },
          onHorizontalDragCancel: () {
            setState(() {
              _dragging = false;
              _pillTarget = (selected >= 0 ? selected : 0).toDouble();
            });
          },
          child: ClipRRect(
            borderRadius: capsule,
            child: Stack(
              children: [
                if (showPill)
                  TweenAnimationBuilder<double>(
                    tween: Tween<double>(end: _pillTarget),
                    duration: _dragging
                        ? Duration.zero
                        : const Duration(milliseconds: 220),
                    curve: Curves.easeOutCubic,
                    child: IgnorePointer(
                      child: Container(
                        decoration: BoxDecoration(
                          color: accent.withAlpha(38),
                          borderRadius: BorderRadius.circular(16),
                        ),
                      ),
                    ),
                    builder: (BuildContext context, double pillIndex,
                        Widget? child) {
                      // Cache only (no setState): the drag-start
                      // position when a slide is interrupted.
                      _pillCurrent = pillIndex;
                      return Positioned(
                        left:
                            (pillIndex + 0.5) * cellWidth - pillWidth / 2,
                        top: (_glassBarHeight - pillHeight) / 2,
                        width: pillWidth,
                        height: pillHeight,
                        child: child!,
                      );
                    },
                  ),
                rowContent,
              ],
            ),
          ),
        );
      },
    );
    final double blur = screen.glassBlurDp.toDouble();
    if (!screen.glassEnabled || blur <= 0) {
      // Glass off: opaque capsule, unchanged (b8-home-glass D3).
      return Container(
        height: _glassBarHeight,
        decoration: BoxDecoration(color: surface, borderRadius: capsule),
        child: barContent,
      );
    }
    // Liquid-glass parameters, aligned to the native formulas in
    // LiquidGlassConfig.kt (translucency t in 0..1; see
    // docs/b8-home-glass-v2.md). At the defaults (t = 0.5) the
    // brightness/contrast terms are neutral and saturation is 1.5.
    final double t = screen.glassTranslucency / 100;
    final double tintAlpha = (0.4 * (2 - 2 * t)).clamp(0.05, 0.8);
    final double brightness = (0.5 - t).clamp(0.0, 0.5) * 0.12;
    final double contrast = 1 - (0.5 - t).clamp(0.0, 0.5) * 0.25;
    final double saturation = 1.5 - (t - 0.5).clamp(0.0, 0.5);
    // Saturation lift: a second blurred backdrop composited with
    // soft-light over the first (see the Stack below). Strength
    // follows the native saturation term; zero when saturation is 1.
    final double liftAlpha = ((saturation - 1) * 0.3).clamp(0.0, 0.25);
    // Lens approximation: a uniform magnification about the bar
    // center. Native refracts up to distortionDp at the rim; the
    // uniform scale keeps the sliding misalignment cue — content
    // inside the bar no longer lines up with content outside it.
    final double lensScale =
        1 + (screen.glassDistortionDp / 600).clamp(0.0, 0.08);
    final bool useLens = screen.glassDistortionDp > 0 && lensScale > 1.001;
    return Container(
      // Drop shadow outside the clip (native Shadow.Default).
      decoration: BoxDecoration(
        borderRadius: capsule,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(screen.darkMode ? 51 : 26),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: capsule,
        child: LayoutBuilder(
          builder: (BuildContext context, BoxConstraints constraints) {
            ImageFilter filter =
                ImageFilter.blur(sigmaX: blur, sigmaY: blur);
            if (useLens && constraints.maxWidth.isFinite) {
              final double cx = constraints.maxWidth / 2;
              const double cy = _glassBarHeight / 2;
              final Matrix4 lens = Matrix4.identity()
                ..translateByVector3(Vector3(cx, cy, 0.0))
                ..scaleByVector3(Vector3.all(lensScale))
                ..translateByVector3(Vector3(-cx, -cy, 0.0));
              filter = ImageFilter.compose(
                outer: filter,
                inner: ImageFilter.matrix(
                  lens.storage,
                  filterQuality: FilterQuality.medium,
                ),
              );
            }
            return BackdropFilter(
              filter: filter,
              child: SizedBox(
                height: _glassBarHeight,
                child: Stack(
                  children: [
                    Positioned.fill(
                      child: Container(
                        color: surface.withAlpha((tintAlpha * 255).round()),
                      ),
                    ),
                    if (liftAlpha > 0.01)
                      Positioned.fill(
                        child: Opacity(
                          opacity: liftAlpha,
                          child: BackdropFilter(
                            filter: ImageFilter.blur(
                              sigmaX: blur,
                              sigmaY: blur,
                            ),
                            blendMode: BlendMode.softLight,
                            child: Container(color: Colors.transparent),
                          ),
                        ),
                      ),
                    if (brightness > 0.001)
                      Positioned.fill(
                        child: Container(
                          color: Colors.white
                              .withAlpha((brightness * 255).round()),
                        ),
                      ),
                    if (contrast < 0.999)
                      Positioned.fill(
                        child: Container(
                          color: const Color(0xFF808080).withAlpha(
                            ((1 - contrast) * 0.5 * 255).round(),
                          ),
                        ),
                      ),
                    Positioned.fill(child: _buildHighlight()),
                    Positioned.fill(child: barContent),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  /// Top-edge highlight + dispersion fringe (native Highlight.Default
  /// approximation). The gradient is a static rim: white fading down
  /// from the top edge, with a faint cyan top / magenta bottom split
  /// when dispersion is on. Painted above the tint, below the icons.
  Widget _buildHighlight() {
    final bool dispersion = widget.screen.glassDispersion;
    final double topAlpha = widget.screen.darkMode ? 0.14 : 0.22;
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(_glassBarHeight / 2),
        border: Border.all(
          color: Colors.white.withAlpha(26),
          width: 1,
        ),
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: dispersion
              ? [
                  const Color(0xFFE8FFFF).withAlpha(41),
                  Colors.white.withAlpha((topAlpha * 255).round()),
                  Colors.transparent,
                  const Color(0xFFFFE8F0).withAlpha(18),
                ]
              : [
                  Colors.white.withAlpha((topAlpha * 255).round()),
                  Colors.transparent,
                ],
          stops: dispersion
              ? const [0.0, 0.18, 0.45, 1.0]
              : const [0.0, 0.45],
        ),
      ),
    );
  }
}
