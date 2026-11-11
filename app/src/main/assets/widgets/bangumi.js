// Bangumi 番组计划 — BieCuiLe 内置组件库（CapyPlayer 组件规范）。
// 数据来自 api.bgm.tv 公开接口，免登录；条目只有资料，点开后在当前资源里搜索片名。
// 墨影视适配：以 BieCuiLe 脚本为底本，仅加 homeModule（首页默认模块），其余逻辑未动。
var BGM_API = 'https://api.bgm.tv';
// 搜索接口一页最多给 20 条
var BGM_PAGE_SIZE = 20;
var BGM_TAGS = ['原创', '漫画改', '轻小说改', '游戏改', '日常', '搞笑', '恋爱', '校园', '奇幻', '战斗', '科幻', '悬疑', '治愈', '百合', '热血', '运动', '音乐', '机战', '异世界', '后宫', '推理', '历史', '恐怖', '美食', '偶像', '剧场版', '国产'];

function bgmOptions(list) {
  return list.map(function (t) { return { title: t, value: t }; });
}

function bgmYears() {
  var now = new Date().getFullYear();
  var out = [{ title: '不限', value: '' }];
  for (var y = now + 1; y >= now - 25; y--) out.push({ title: String(y), value: String(y) });
  return out;
}

var BGM_TYPE_PARAM = {
  name: 'type', title: '类别', type: 'enumeration', value: '2',
  enumOptions: [{ title: '动画', value: '2' }, { title: '三次元（日剧等）', value: '6' }]
};
var BGM_SORT_PARAM = {
  name: 'sort', title: '排序', type: 'enumeration', value: 'heat',
  enumOptions: [{ title: '热度', value: 'heat' }, { title: '排名', value: 'rank' }, { title: '评分', value: 'score' }]
};

var WidgetMetadata = {
  id: 'builtin.bangumi',
  title: 'Bangumi 番组计划',
  description: '每日放送、季度新番、排行榜与标签索引',
  author: 'BieCuiLe',
  site: 'https://bgm.tv',
  version: '1.0.0',
  homeModule: 'bgmCalendar',
  modules: [
    {
      title: '番剧索引',
      description: '按标签、年份、季度筛选',
      functionName: 'bgmBrowse',
      cacheDuration: 3600,
      params: [
        BGM_TYPE_PARAM,
        { name: 'tag', title: '标签', type: 'string', value: '', description: '多个标签用逗号分隔，留空为全部', placeholders: bgmOptions(BGM_TAGS) },
        { name: 'year', title: '年份', type: 'enumeration', value: '', enumOptions: bgmYears() },
        {
          name: 'season', title: '季度', type: 'enumeration', value: '',
          enumOptions: [
            { title: '全年', value: '' },
            { title: '1 月（冬）', value: '1' },
            { title: '4 月（春）', value: '4' },
            { title: '7 月（夏）', value: '7' },
            { title: '10 月（秋）', value: '10' }
          ]
        },
        BGM_SORT_PARAM,
        { name: 'page', title: '页码', type: 'page' }
      ]
    },
    {
      title: '每日放送',
      description: '当季动画按星期的放送表',
      functionName: 'bgmCalendar',
      cacheDuration: 3600,
      params: [
        {
          name: 'weekday', title: '星期', type: 'enumeration', value: 'today',
          enumOptions: [
            { title: '今天', value: 'today' },
            { title: '星期一', value: '1' },
            { title: '星期二', value: '2' },
            { title: '星期三', value: '3' },
            { title: '星期四', value: '4' },
            { title: '星期五', value: '5' },
            { title: '星期六', value: '6' },
            { title: '星期日', value: '7' }
          ]
        }
      ]
    },
    {
      title: '季度新番',
      description: '按开播季度列出，默认本季',
      functionName: 'bgmSeason',
      cacheDuration: 3600,
      params: [
        {
          name: 'which', title: '季度', type: 'enumeration', value: '0',
          enumOptions: [{ title: '本季', value: '0' }, { title: '上一季', value: '-1' }, { title: '下一季', value: '1' }]
        },
        BGM_SORT_PARAM,
        { name: 'page', title: '页码', type: 'page' }
      ]
    },
    {
      title: '排行榜',
      description: 'Bangumi 评分排名',
      functionName: 'bgmRank',
      cacheDuration: 21600,
      params: [BGM_TYPE_PARAM, { name: 'page', title: '页码', type: 'page' }]
    },
    { title: '热门标签', type: 'category', functionName: 'bgmTagCategories', cacheDuration: 86400, params: [] }
  ]
};

function bgmOffset(params) {
  var page = parseInt(params && params.page, 10);
  return ((page > 0 ? page : 1) - 1) * BGM_PAGE_SIZE;
}

function bgmPad(n) {
  return n < 10 ? '0' + n : String(n);
}

// 某年从 month 月开始的一个季度：[起, 止)
function bgmQuarter(year, month) {
  var endYear = month >= 10 ? year + 1 : year;
  var endMonth = month >= 10 ? 1 : month + 3;
  return ['>=' + year + '-' + bgmPad(month) + '-01', '<' + endYear + '-' + bgmPad(endMonth) + '-01'];
}

function bgmItem(raw) {
  if (!raw || !raw.id) return null;
  var title = raw.name_cn || raw.name || '';
  if (!title) return null;
  var images = raw.images || {};
  var poster = String(images.large || images.common || raw.image || '').replace(/^http:/, 'https:');
  var score = Number((raw.rating && raw.rating.score) || raw.score || 0);
  var rank = (raw.rating && raw.rating.rank) || raw.rank || 0;
  var date = raw.date || raw.air_date || '';
  var tags = (raw.tags || []).slice(0, 3).map(function (t) { return t.name; });
  var movie = raw.platform === '剧场版' || tags.indexOf('剧场版') >= 0;
  var remark = [];
  if (raw.eps) remark.push(raw.eps + ' 话');
  else if (raw.platform && raw.platform !== 'TV') remark.push(raw.platform);
  if (rank > 0) remark.push('#' + rank);
  return {
    id: String(raw.id),
    type: 'bangumi',
    bangumiId: String(raw.id),
    title: title,
    originalTitle: raw.name || '',
    mediaType: movie ? 'movie' : 'tv',
    posterUrl: poster,
    rating: score > 0 ? score : undefined,
    releaseDate: date,
    description: String(raw.summary || '').replace(/\r/g, '').replace(/\n{2,}/g, '\n').trim(),
    genreTitle: tags.join(' '),
    remark: remark.join(' · ')
  };
}

function bgmItems(list) {
  var out = [];
  (list || []).forEach(function (raw) {
    var item = bgmItem(raw);
    if (item) out.push(item);
  });
  return out;
}

async function bgmSearch(filter, sort, params) {
  filter.nsfw = false;
  var res = await Widget.http.post(
    BGM_API + '/v0/search/subjects',
    { keyword: '', sort: sort || 'heat', filter: filter },
    { params: { limit: BGM_PAGE_SIZE, offset: bgmOffset(params) }, timeout: 25 }
  );
  var data = res.data;
  if (!res.ok || !data || typeof data !== 'object') {
    throw new Error('Bangumi 接口请求失败' + (data && data.description ? '：' + data.description : '（HTTP ' + res.status + '）'));
  }
  return bgmItems(data.data);
}

async function bgmBrowse(params) {
  params = params || {};
  var filter = { type: [parseInt(params.type, 10) || 2] };
  var tags = String(params.tag || '').split(/[,，\s]+/).filter(Boolean);
  if (tags.length) filter.tag = tags;
  var year = parseInt(params.year, 10);
  var month = parseInt(params.season, 10);
  if (year > 1900 && month >= 1 && month <= 12) {
    filter.air_date = bgmQuarter(year, month);
  } else if (year > 1900) {
    filter.air_date = ['>=' + year + '-01-01', '<' + (year + 1) + '-01-01'];
  }
  var sort = params.sort || 'heat';
  if (sort === 'rank') filter.rank = ['>0'];
  return bgmSearch(filter, sort, params);
}

async function bgmSeason(params) {
  params = params || {};
  var now = new Date();
  var index = now.getFullYear() * 4 + Math.floor(now.getMonth() / 3) + (parseInt(params.which, 10) || 0);
  var range = bgmQuarter(Math.floor(index / 4), (index % 4) * 3 + 1);
  return bgmSearch({ type: [2], air_date: range }, params.sort || 'heat', params);
}

async function bgmRank(params) {
  params = params || {};
  return bgmSearch({ type: [parseInt(params.type, 10) || 2], rank: ['>0'] }, 'rank', params);
}

async function bgmCalendar(params) {
  params = params || {};
  var day = parseInt(params.weekday, 10);
  // Bangumi 的星期：1 = 周一 … 7 = 周日
  if (!(day >= 1 && day <= 7)) day = new Date().getDay() || 7;
  var res = await Widget.http.get(BGM_API + '/calendar', { timeout: 25 });
  if (!res.ok || !Array.isArray(res.data)) throw new Error('Bangumi 放送表请求失败（HTTP ' + res.status + '）');
  var items = [];
  res.data.forEach(function (entry) {
    if (entry && entry.weekday && entry.weekday.id === day) items = entry.items || [];
  });
  // 在看的人多的排前面
  items = items.slice().sort(function (a, b) {
    return ((b.collection && b.collection.doing) || 0) - ((a.collection && a.collection.doing) || 0);
  });
  return bgmItems(items);
}

async function bgmTagCategories() {
  return BGM_TAGS.map(function (tag) {
    return { id: 'tag_' + tag, name: tag, params: { type: '2', tag: tag, sort: 'heat' } };
  });
}
