// TMDB — BieCuiLe 内置组件库（CapyPlayer 组件规范）。
// 通过宿主的 Widget.tmdb 请求，用的是 设置 › TMDB 里填的 API Key（Key 不经过组件脚本）。
// 热门、正在上映这类固定榜单应用本来就有（自定义页面数据源里的“TMDB”），这里补的是筛选和专题。
// 墨影视适配：以 BieCuiLe 脚本为底本，仅加 homeModule（首页默认模块），其余逻辑未动。
var TMDB_MOVIE_GENRES = [
  [28, '动作'], [12, '冒险'], [16, '动画'], [35, '喜剧'], [80, '犯罪'], [99, '纪录'], [18, '剧情'], [10751, '家庭'],
  [14, '奇幻'], [36, '历史'], [27, '恐怖'], [10402, '音乐'], [9648, '悬疑'], [10749, '爱情'], [878, '科幻'],
  [53, '惊悚'], [10752, '战争'], [37, '西部']
];
var TMDB_TV_GENRES = [
  [10759, '动作冒险'], [16, '动画'], [35, '喜剧'], [80, '犯罪'], [99, '纪录'], [18, '剧情'], [10751, '家庭'],
  [10762, '儿童'], [9648, '悬疑'], [10764, '真人秀'], [10765, '科幻奇幻'], [10766, '肥皂剧'], [10767, '脱口秀'],
  [10768, '战争政治'], [37, '西部']
];
var TMDB_NETWORKS = [
  [213, 'Netflix'], [49, 'HBO'], [2739, 'Disney+'], [2552, 'Apple TV+'], [1024, 'Prime Video'], [453, 'Hulu'],
  [2007, '腾讯视频'], [1330, '爱奇艺'], [1419, '优酷'], [1631, '芒果TV'], [1605, '哔哩哔哩'], [521, 'CCTV-8'],
  [4, 'BBC One'], [614, 'TOKYO MX'], [160, 'TBS'], [98, 'TV Tokyo'], [866, 'tvN'], [1, 'Fuji TV']
];
var TMDB_COMPANIES = [
  [420, '漫威影业'], [3, '皮克斯'], [2, '迪士尼'], [174, '华纳兄弟'], [33, '环球影业'], [4, '派拉蒙'],
  [5, '哥伦比亚'], [25, '二十世纪影业'], [1, '卢卡斯影业'], [9993, 'DC'], [41077, 'A24'], [10342, '吉卜力'],
  [521, '梦工厂动画'], [6704, '照明娱乐'], [923, '传奇影业'], [882, '东宝']
];
var TMDB_COLLECTIONS = [
  [1241, '哈利·波特'], [119, '指环王'], [10, '星球大战'], [86311, '复仇者联盟'], [263, '黑暗骑士'],
  [2344, '黑客帝国'], [9485, '速度与激情'], [87359, '碟中谍'], [645, '007'], [10194, '玩具总动员'],
  [328, '侏罗纪公园'], [295, '加勒比海盗'], [528, '终结者'], [8091, '异形'], [87096, '阿凡达']
];

function tmdbOptions(pairs, any) {
  var out = any ? [{ title: any, value: '' }] : [];
  pairs.forEach(function (p) { out.push({ title: p[1], value: String(p[0]) }); });
  return out;
}

var TMDB_PAGE = { name: 'page', title: '页码', type: 'page' };

var WidgetMetadata = {
  id: 'builtin.tmdb',
  title: 'TMDB',
  description: '按类型、地区、年份筛选，播出平台、出品公司、系列电影与趋势',
  author: 'BieCuiLe',
  site: 'https://www.themoviedb.org',
  version: '1.0.0',
  homeModule: 'tmdbTrending',
  modules: [
    {
      title: '探索发现',
      description: '按类型、语言、年份筛选并排序',
      functionName: 'tmdbDiscover',
      cacheDuration: 3600,
      params: [
        {
          name: 'media_type', title: '范围', type: 'enumeration', value: 'movie',
          enumOptions: [{ title: '电影', value: 'movie' }, { title: '剧集', value: 'tv' }]
        },
        {
          name: 'movie_genre', title: '电影类型', type: 'enumeration', value: '',
          enumOptions: tmdbOptions(TMDB_MOVIE_GENRES, '全部'),
          belongTo: { paramName: 'media_type', value: ['movie'] }
        },
        {
          name: 'tv_genre', title: '剧集类型', type: 'enumeration', value: '',
          enumOptions: tmdbOptions(TMDB_TV_GENRES, '全部'),
          belongTo: { paramName: 'media_type', value: ['tv'] }
        },
        {
          name: 'original_language', title: '原始语言', type: 'enumeration', value: '',
          enumOptions: [
            { title: '不限', value: '' },
            { title: '华语', value: 'zh' },
            { title: '英语', value: 'en' },
            { title: '日语', value: 'ja' },
            { title: '韩语', value: 'ko' },
            { title: '法语', value: 'fr' },
            { title: '西班牙语', value: 'es' },
            { title: '泰语', value: 'th' }
          ]
        },
        { name: 'year', title: '年份', type: 'string', value: '', description: '如 2026，留空不限', placeholders: tmdbYears() },
        {
          name: 'sort_by', title: '排序', type: 'enumeration', value: 'popularity.desc',
          enumOptions: [
            { title: '最热门', value: 'popularity.desc' },
            { title: '评分最高', value: 'vote_average.desc' },
            { title: '最新上映', value: 'date.desc' },
            { title: '评价人数最多', value: 'vote_count.desc' }
          ]
        },
        TMDB_PAGE
      ]
    },
    {
      title: '趋势',
      description: '今日或本周最受关注的电影和剧集',
      functionName: 'tmdbTrending',
      cacheDuration: 3600,
      params: [
        {
          name: 'media_type', title: '范围', type: 'enumeration', value: 'all',
          enumOptions: [{ title: '全部', value: 'all' }, { title: '电影', value: 'movie' }, { title: '剧集', value: 'tv' }]
        },
        {
          name: 'time_window', title: '时间', type: 'enumeration', value: 'week',
          enumOptions: [{ title: '本周', value: 'week' }, { title: '今日', value: 'day' }]
        },
        TMDB_PAGE
      ]
    },
    {
      title: '播出平台',
      description: 'Netflix、HBO、腾讯视频等平台的剧集',
      functionName: 'tmdbNetwork',
      cacheDuration: 3600,
      params: [
        { name: 'with_networks', title: '平台', type: 'enumeration', value: '213', enumOptions: tmdbOptions(TMDB_NETWORKS) },
        {
          name: 'sort_by', title: '排序', type: 'enumeration', value: 'popularity.desc',
          enumOptions: [
            { title: '最热门', value: 'popularity.desc' },
            { title: '最新开播', value: 'date.desc' },
            { title: '评分最高', value: 'vote_average.desc' }
          ]
        },
        TMDB_PAGE
      ]
    },
    {
      title: '出品公司',
      description: '漫威、皮克斯、吉卜力等公司的电影',
      functionName: 'tmdbCompany',
      cacheDuration: 3600,
      params: [
        { name: 'with_companies', title: '公司', type: 'enumeration', value: '420', enumOptions: tmdbOptions(TMDB_COMPANIES) },
        {
          name: 'sort_by', title: '排序', type: 'enumeration', value: 'popularity.desc',
          enumOptions: [
            { title: '最热门', value: 'popularity.desc' },
            { title: '最新上映', value: 'date.desc' },
            { title: '评分最高', value: 'vote_average.desc' }
          ]
        },
        TMDB_PAGE
      ]
    },
    {
      title: '系列电影',
      description: '同一系列的全部电影，按上映顺序',
      functionName: 'tmdbCollection',
      cacheDuration: 86400,
      params: [
        {
          name: 'collection', title: '系列', type: 'string', value: '1241',
          description: 'TMDB 系列（collection）编号',
          placeholders: tmdbOptions(TMDB_COLLECTIONS)
        }
      ]
    },
    { title: '电影类型', type: 'category', functionName: 'tmdbMovieCategories', cacheDuration: 86400, params: [] },
    { title: '剧集类型', type: 'category', functionName: 'tmdbTvCategories', cacheDuration: 86400, params: [] },
    { title: '播出平台分类', type: 'category', functionName: 'tmdbNetworkCategories', cacheDuration: 86400, params: [] }
  ]
};

function tmdbYears() {
  var now = new Date().getFullYear();
  var out = [];
  for (var y = now; y > now - 12; y--) out.push({ title: String(y), value: String(y) });
  return out;
}

function tmdbPage(params) {
  var page = parseInt(params && params.page, 10);
  return page > 0 ? Math.min(page, 500) : 1;
}

function tmdbToday() {
  var d = new Date();
  var two = function (n) { return n < 10 ? '0' + n : String(n); };
  return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate());
}

async function tmdbGet(path, query) {
  try {
    return await Widget.tmdb.get(path, { params: query || {} });
  } catch (e) {
    var message = String((e && e.message) || e);
    if (/HTTP 401/.test(message)) throw new Error('TMDB API Key 无效或没有填写，请到 设置 › TMDB 检查');
    throw new Error('TMDB 请求失败：' + message);
  }
}

function tmdbItem(raw, mediaType) {
  if (!raw || !raw.id) return null;
  var type = raw.media_type || mediaType;
  if (type !== 'movie' && type !== 'tv') return null;
  var title = raw.title || raw.name || '';
  if (!title) return null;
  var rating = Number(raw.vote_average || 0);
  return {
    id: String(raw.id),
    type: 'tmdb',
    tmdbId: raw.id,
    title: title,
    originalTitle: raw.original_title || raw.original_name || '',
    mediaType: type,
    posterPath: raw.poster_path || '',
    backdropPath: raw.backdrop_path || '',
    releaseDate: raw.release_date || raw.first_air_date || '',
    rating: rating > 0 ? Math.round(rating * 10) / 10 : undefined,
    description: raw.overview || ''
  };
}

function tmdbItems(list, mediaType) {
  var out = [];
  (list || []).forEach(function (raw) {
    var item = tmdbItem(raw, mediaType);
    if (item) out.push(item);
  });
  return out;
}

// 排序项里的 date.desc 在电影和剧集接口里名字不同；按评分排要有最低评价人数，否则全是只有一两票的冷门片。
function tmdbSort(query, sort, tv) {
  sort = sort || 'popularity.desc';
  if (sort === 'date.desc') {
    query.sort_by = tv ? 'first_air_date.desc' : 'primary_release_date.desc';
    query[tv ? 'first_air_date.lte' : 'primary_release_date.lte'] = tmdbToday();
  } else {
    query.sort_by = sort;
  }
  if (sort === 'vote_average.desc') query['vote_count.gte'] = tv ? 100 : 300;
  return query;
}

async function tmdbDiscover(params) {
  params = params || {};
  var tv = params.media_type === 'tv';
  var query = tmdbSort({ page: tmdbPage(params), include_adult: false }, params.sort_by, tv);
  // 从分类点进来时带的是 with_genres
  var genre = params.with_genres || (tv ? params.tv_genre : params.movie_genre);
  if (genre) query.with_genres = genre;
  if (params.original_language) query.with_original_language = params.original_language;
  if (params.with_networks && tv) query.with_networks = params.with_networks;
  var year = parseInt(params.year, 10);
  if (year > 1880) query[tv ? 'first_air_date_year' : 'primary_release_year'] = year;
  var data = await tmdbGet('/discover/' + (tv ? 'tv' : 'movie'), query);
  return tmdbItems(data.results, tv ? 'tv' : 'movie');
}

async function tmdbTrending(params) {
  params = params || {};
  var type = ['movie', 'tv'].indexOf(params.media_type) >= 0 ? params.media_type : 'all';
  var timeWindow = params.time_window === 'day' ? 'day' : 'week';
  var data = await tmdbGet('/trending/' + type + '/' + timeWindow, { page: tmdbPage(params) });
  return tmdbItems(data.results, type === 'all' ? '' : type);
}

async function tmdbNetwork(params) {
  params = params || {};
  var query = tmdbSort({ page: tmdbPage(params), with_networks: params.with_networks || '213' }, params.sort_by, true);
  var data = await tmdbGet('/discover/tv', query);
  return tmdbItems(data.results, 'tv');
}

async function tmdbCompany(params) {
  params = params || {};
  var query = tmdbSort({ page: tmdbPage(params), with_companies: params.with_companies || '420' }, params.sort_by, false);
  var data = await tmdbGet('/discover/movie', query);
  return tmdbItems(data.results, 'movie');
}

async function tmdbCollection(params) {
  var id = (/(\d+)/.exec(String((params && params.collection) || '')) || [])[1];
  if (!id) throw new Error('请填写 TMDB 系列编号');
  var data = await tmdbGet('/collection/' + id);
  var parts = (data.parts || []).slice().sort(function (a, b) {
    return String(a.release_date || '9999').localeCompare(String(b.release_date || '9999'));
  });
  return tmdbItems(parts, 'movie');
}

function tmdbCategories(mediaType, genres) {
  return genres.map(function (g) {
    return { id: mediaType + '_' + g[0], name: g[1], params: { media_type: mediaType, with_genres: String(g[0]) } };
  });
}

async function tmdbMovieCategories() {
  return tmdbCategories('movie', TMDB_MOVIE_GENRES);
}

async function tmdbTvCategories() {
  return tmdbCategories('tv', TMDB_TV_GENRES);
}

async function tmdbNetworkCategories() {
  return TMDB_NETWORKS.map(function (n) {
    return { id: 'network_' + n[0], name: n[1], params: { media_type: 'tv', with_networks: String(n[0]) } };
  });
}
