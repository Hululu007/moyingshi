// 豆瓣 — BieCuiLe 内置组件库（CapyPlayer 组件规范）。
// 数据来自豆瓣移动版公开接口，免登录；条目只有资料，点开后在当前资源里搜索片名。
// 墨影视适配：以 BieCuiLe 脚本为底本，仅加 homeModule（首页默认模块），其余逻辑未动。
var DOUBAN_API = 'https://m.douban.com/rexxar/api/v2';
var DOUBAN_HEADERS = {
  Referer: 'https://m.douban.com/',
  'User-Agent':
    'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36'
};
// 豆瓣图片不带来源页和浏览器标识会被拒绝
var DOUBAN_IMAGE_HEADERS = {
  Referer: 'https://movie.douban.com/',
  'User-Agent': DOUBAN_HEADERS['User-Agent']
};
var DOUBAN_PAGE_SIZE = 20;

var DOUBAN_MOVIE_TAGS = ['喜剧', '爱情', '动作', '科幻', '动画', '悬疑', '犯罪', '惊悚', '冒险', '奇幻', '恐怖', '剧情', '战争', '历史', '传记', '家庭', '音乐', '歌舞', '武侠', '灾难', '纪录片'];
var DOUBAN_TV_TAGS = ['国产剧', '美剧', '英剧', '韩剧', '日剧', '港剧', '台剧', '动画', '综艺', '纪录片', '喜剧', '爱情', '悬疑', '犯罪', '古装', '武侠', '奇幻', '科幻', '家庭', '历史'];

function doubanOptions(list) {
  return list.map(function (t) { return { title: t, value: t }; });
}

function doubanCollectionModule(title, collection, description) {
  return {
    title: title,
    description: description || '',
    functionName: 'doubanCollection',
    cacheDuration: 3600,
    params: [
      { name: 'collection', type: 'constant', value: collection },
      { name: 'page', title: '页码', type: 'page' }
    ]
  };
}

var WidgetMetadata = {
  id: 'builtin.douban',
  title: '豆瓣',
  description: '豆瓣热门、榜单、分类找片与片单',
  author: 'BieCuiLe',
  site: 'https://movie.douban.com',
  version: '1.0.0',
  homeModule: 'doubanHotMovies',
  modules: [
    {
      title: '分类找片',
      description: '按类型、地区等标签筛选，可选排序',
      functionName: 'doubanExplore',
      cacheDuration: 3600,
      params: [
        {
          name: 'kind', title: '范围', type: 'enumeration', value: 'movie',
          enumOptions: [{ title: '电影', value: 'movie' }, { title: '剧集', value: 'tv' }]
        },
        {
          name: 'tags', title: '标签', type: 'string', value: '',
          description: '多个标签用逗号分隔，留空为全部',
          placeholders: doubanOptions(['科幻', '喜剧', '悬疑', '动画', '华语', '欧美', '日本', '韩国', '2020年代', '国产剧', '美剧', '综艺'])
        },
        {
          name: 'sort', title: '排序', type: 'enumeration', value: 'U',
          enumOptions: [
            { title: '近期热度', value: 'U' },
            { title: '综合排序', value: 'T' },
            { title: '首映时间', value: 'R' },
            { title: '高分优先', value: 'S' }
          ]
        },
        { name: 'page', title: '页码', type: 'page' }
      ]
    },
    {
      title: '近期热门电影',
      functionName: 'doubanHotMovies',
      cacheDuration: 3600,
      params: [
        { name: 'category', title: '榜单', type: 'enumeration', value: '热门', enumOptions: doubanOptions(['热门', '最新', '豆瓣高分', '冷门佳片']) },
        { name: 'type', title: '地区', type: 'enumeration', value: '全部', enumOptions: doubanOptions(['全部', '华语', '欧美', '韩国', '日本']) },
        { name: 'page', title: '页码', type: 'page' }
      ]
    },
    {
      title: '近期热门剧集',
      functionName: 'doubanHotTv',
      cacheDuration: 3600,
      params: [
        {
          name: 'type', title: '分类', type: 'enumeration', value: 'tv',
          enumOptions: [
            { title: '综合', value: 'tv' },
            { title: '国产剧', value: 'tv_domestic' },
            { title: '欧美剧', value: 'tv_american' },
            { title: '日剧', value: 'tv_japanese' },
            { title: '韩剧', value: 'tv_korean' },
            { title: '动画', value: 'tv_animation' },
            { title: '纪录片', value: 'tv_documentary' }
          ]
        },
        { name: 'page', title: '页码', type: 'page' }
      ]
    },
    {
      title: '近期热门综艺',
      functionName: 'doubanHotTv',
      cacheDuration: 3600,
      params: [
        {
          name: 'type', title: '分类', type: 'enumeration', value: 'show',
          enumOptions: [
            { title: '综合', value: 'show' },
            { title: '国内', value: 'show_domestic' },
            { title: '国外', value: 'show_foreign' }
          ]
        },
        { name: 'page', title: '页码', type: 'page' }
      ]
    },
    doubanCollectionModule('影院热映', 'movie_showing', '正在上映的电影'),
    doubanCollectionModule('即将上映', 'movie_soon', '院线即将上映'),
    doubanCollectionModule('实时热门电影', 'movie_real_time_hotest'),
    doubanCollectionModule('实时热门剧集', 'tv_real_time_hotest'),
    doubanCollectionModule('一周口碑电影榜', 'movie_weekly_best'),
    doubanCollectionModule('华语口碑剧集榜', 'tv_chinese_best_weekly'),
    doubanCollectionModule('全球口碑剧集榜', 'tv_global_best_weekly'),
    doubanCollectionModule('豆瓣电影 Top250', 'movie_top250'),
    {
      title: '更多榜单',
      description: '选择一个豆瓣榜单',
      functionName: 'doubanCollection',
      cacheDuration: 3600,
      params: [
        {
          name: 'collection', title: '榜单', type: 'enumeration', value: 'movie_hot_gaia',
          enumOptions: [
            { title: '豆瓣热门电影', value: 'movie_hot_gaia' },
            { title: '近期热门剧集', value: 'tv_hot' },
            { title: '近期热门美剧', value: 'tv_american' },
            { title: '近期热门韩剧', value: 'tv_korean' },
            { title: '近期热门日剧', value: 'tv_japanese' },
            { title: '近期热门动画', value: 'tv_animation' },
            { title: '近期热门综艺', value: 'show_hot' },
            { title: '国内口碑综艺榜', value: 'show_chinese_best_weekly' },
            { title: '国外口碑综艺榜', value: 'show_global_best_weekly' },
            { title: '高分经典科幻片', value: 'movie_scifi' },
            { title: '高分经典喜剧片', value: 'movie_comedy' }
          ]
        },
        { name: 'page', title: '页码', type: 'page' }
      ]
    },
    {
      title: '片单（豆列）',
      description: '填豆列编号或网址，如 https://www.douban.com/doulist/240962/',
      functionName: 'doubanDoulist',
      cacheDuration: 3600,
      params: [
        { name: 'doulist', title: '豆列编号或网址', type: 'string', value: '', required: true },
        { name: 'page', title: '页码', type: 'page' }
      ]
    },
    { title: '电影类型', type: 'category', functionName: 'doubanMovieCategories', cacheDuration: 86400, params: [] },
    { title: '剧集类型', type: 'category', functionName: 'doubanTvCategories', cacheDuration: 86400, params: [] }
  ]
};

function doubanStart(params) {
  var page = parseInt(params && params.page, 10);
  return ((page > 0 ? page : 1) - 1) * DOUBAN_PAGE_SIZE;
}

async function doubanGet(path, query) {
  var res = await Widget.http.get(DOUBAN_API + path, { headers: DOUBAN_HEADERS, params: query, timeout: 20 });
  var data = res.data;
  if (!res.ok || !data || typeof data !== 'object') {
    var reason = data && (data.localized_message || data.msg);
    throw new Error('豆瓣接口请求失败' + (reason ? '：' + reason : '（HTTP ' + res.status + '）'));
  }
  return data;
}

// 各接口的条目写法不完全一样（榜单、近期热门、分类找片、豆列），统一成组件条目。
function doubanItem(raw) {
  if (!raw || (raw.type !== 'movie' && raw.type !== 'tv')) return null;
  var id = String(raw.target_id || raw.id || '');
  var title = raw.title || '';
  if (!id || !title) return null;
  var pic = raw.pic || {};
  var poster = pic.normal || pic.large || (raw.cover && raw.cover.url) || raw.cover_url || '';
  var subtitle = raw.card_subtitle || raw.subtitle || raw.info || '';
  var year = raw.year || (/^(\d{4})/.exec(subtitle) || [])[1] || '';
  var parts = subtitle.split(' / ');
  var rating = raw.rating && Number(raw.rating.value);
  return {
    id: id,
    type: 'douban',
    doubanId: id,
    title: title,
    mediaType: raw.type === 'tv' ? 'tv' : 'movie',
    posterUrl: poster,
    rating: rating > 0 ? rating : undefined,
    releaseDate: year,
    year: year,
    description: raw.comment || subtitle,
    genreTitle: parts.length >= 3 ? parts[2] : '',
    remark: raw.episodes_info || (raw.rank_value ? 'No.' + raw.rank_value : ''),
    customHeaders: DOUBAN_IMAGE_HEADERS
  };
}

function doubanItems(list) {
  var seen = {};
  var out = [];
  (list || []).forEach(function (raw) {
    var item = doubanItem(raw);
    if (item && !seen[item.id]) {
      seen[item.id] = true;
      out.push(item);
    }
  });
  return out;
}

async function doubanExplore(params) {
  params = params || {};
  var kind = params.kind === 'tv' ? 'tv' : 'movie';
  var tags = String(params.tags || '').split(/[,，\s]+/).filter(Boolean).join(',');
  var data = await doubanGet('/' + kind + '/recommend', {
    refresh: 0,
    start: doubanStart(params),
    count: DOUBAN_PAGE_SIZE,
    selected_categories: '{}',
    uncollect: false,
    tags: tags,
    sort: params.sort || 'U'
  });
  return doubanItems(data.items);
}

async function doubanHotMovies(params) {
  params = params || {};
  var data = await doubanGet('/subject/recent_hot/movie', {
    start: doubanStart(params),
    limit: DOUBAN_PAGE_SIZE,
    category: params.category || '热门',
    type: params.type || '全部'
  });
  return doubanItems(data.items);
}

async function doubanHotTv(params) {
  params = params || {};
  var type = params.type || 'tv';
  var data = await doubanGet('/subject/recent_hot/tv', {
    start: doubanStart(params),
    limit: DOUBAN_PAGE_SIZE,
    category: type.indexOf('show') === 0 ? 'show' : 'tv',
    type: type
  });
  return doubanItems(data.items);
}

async function doubanCollection(params) {
  params = params || {};
  var collection = String(params.collection || 'movie_hot_gaia').replace(/[^A-Za-z0-9_]/g, '');
  var data = await doubanGet('/subject_collection/' + collection + '/items', {
    start: doubanStart(params),
    count: DOUBAN_PAGE_SIZE
  });
  return doubanItems(data.subject_collection_items);
}

async function doubanDoulist(params) {
  params = params || {};
  var id = (/(\d{3,})/.exec(String(params.doulist || '')) || [])[1];
  if (!id) throw new Error('请填写豆列编号或网址');
  var data = await doubanGet('/doulist/' + id + '/items', {
    start: doubanStart(params),
    count: DOUBAN_PAGE_SIZE
  });
  return doubanItems(data.items);
}

function doubanCategories(kind, tags) {
  return tags.map(function (tag) {
    return { id: kind + '_' + tag, name: tag, params: { kind: kind, tags: tag, sort: 'U' } };
  });
}

async function doubanMovieCategories() {
  return doubanCategories('movie', DOUBAN_MOVIE_TAGS);
}

async function doubanTvCategories() {
  return doubanCategories('tv', DOUBAN_TV_TAGS);
}
