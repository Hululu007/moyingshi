// 蜜柑计划 Mikan Project — BieCuiLe 内置组件库（CapyPlayer 组件规范）。
// 每日放送、最近更新、季度新番；条目只有资料，点开后在当前资源里搜索片名。
// 墨影视适配：以 BieCuiLe 脚本为底本，删掉详情页磁力相关代码（墨影视没有磁力引擎，
// 条目点开改为搜片名），加 homeModule（首页默认模块），列表逻辑未动。

var MIKAN_DEFAULT = 'https://mikanani.kas.pub';
var MIKAN_WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '剧场版', 'OVA'];

function mikanSeasonNow() {
  var d = new Date();
  var m = d.getMonth() + 1;
  return { year: String(d.getFullYear()), season: m <= 3 ? '冬' : m <= 6 ? '春' : m <= 9 ? '夏' : '秋' };
}

function mikanYears() {
  var now = new Date().getFullYear();
  var out = [];
  for (var y = now; y >= 2013; y--) out.push({ title: String(y), value: String(y) });
  return out;
}

var MIKAN_WEEKDAY_PARAM = {
  name: 'weekday', title: '放送', type: 'enumeration', value: 'all',
  enumOptions: [{ title: '全部', value: 'all' }].concat(MIKAN_WEEKDAYS.map(function (t, i) {
    return { title: t, value: String(i) };
  }))
};

var WidgetMetadata = {
  id: 'builtin.mikan',
  title: '蜜柑计划',
  description: '每日放送、最近更新、季度新番',
  author: 'BieCuiLe',
  site: 'https://mikanani.kas.pub',
  version: '1.0.0',
  homeModule: 'mikanDaily',
  globalParams: [
    {
      name: 'site', title: '站点地址', type: 'enumeration', value: MIKAN_DEFAULT,
      description: '主站在国内常打不开，默认用镜像',
      enumOptions: [
        { title: 'mikanani.kas.pub（镜像）', value: MIKAN_DEFAULT },
        { title: 'mikanani.me（主站）', value: 'https://mikanani.me' }
      ]
    }
  ],
  search: { title: '搜索番剧', functionName: 'mikanSearch', params: [] },
  modules: [
    {
      title: '季度新番',
      description: '按年份、季度和放送星期查看',
      functionName: 'mikanSeason',
      cacheDuration: 3600,
      params: [
        { name: 'year', title: '年份', type: 'enumeration', value: mikanSeasonNow().year, enumOptions: mikanYears() },
        {
          name: 'season', title: '季度', type: 'enumeration', value: mikanSeasonNow().season,
          enumOptions: [
            { title: '冬季（1 月）', value: '冬' },
            { title: '春季（4 月）', value: '春' },
            { title: '夏季（7 月）', value: '夏' },
            { title: '秋季（10 月）', value: '秋' }
          ]
        },
        MIKAN_WEEKDAY_PARAM
      ]
    },
    {
      title: '每日放送',
      description: '今天（或指定星期）播出的番剧',
      functionName: 'mikanDaily',
      cacheDuration: 1800,
      params: [
        {
          name: 'weekday', title: '星期', type: 'enumeration', value: 'today',
          enumOptions: [{ title: '今天', value: 'today' }].concat(MIKAN_WEEKDAYS.slice(0, 7).map(function (t, i) {
            return { title: t, value: String(i) };
          }))
        }
      ]
    },
    {
      title: '最近更新',
      description: '本季番剧按最近一次发布排序',
      functionName: 'mikanRecent',
      cacheDuration: 900,
      params: [
        {
          name: 'days', title: '范围', type: 'enumeration', value: '3',
          enumOptions: [
            { title: '最近 1 天', value: '1' },
            { title: '最近 3 天', value: '3' },
            { title: '最近一周', value: '7' },
            { title: '全部', value: '0' }
          ]
        }
      ]
    },
    { title: '放送星期', type: 'category', functionName: 'mikanWeekdayCategories', cacheDuration: 86400, params: [] }
  ]
};

var MIKAN_SITE = null;

// 站点地址是组件的全局参数，只会随列表函数的 params 传进来；记下来给后续列表调用用
//（v1 不暴露站点设置，默认用镜像 MIKAN_DEFAULT）
function mikanUse(params) {
  var site = params && params.site ? String(params.site).trim().replace(/\/+$/, '') : '';
  if (!site || site === MIKAN_SITE) return;
  MIKAN_SITE = site;
  try { Widget.storage.set('site', site); } catch (e) {}
}

function mikanBase() {
  if (!MIKAN_SITE) {
    try { MIKAN_SITE = Widget.storage.get('site') || null; } catch (e) {}
  }
  return MIKAN_SITE || MIKAN_DEFAULT;
}

function mikanUnescape(s) {
  return String(s || '')
    .replace(/&#x([0-9a-fA-F]+);/g, function (_, h) { return String.fromCodePoint(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCodePoint(parseInt(d, 10)); })
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&');
}

function mikanText(html) {
  return mikanUnescape(String(html || '').replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')).trim();
}

async function mikanGet(path, params) {
  var res = await Widget.http.get(mikanBase() + path, {
    params: params,
    timeout: 25,
    headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36' }
  });
  if (!res.ok) throw new Error('蜜柑计划请求失败（HTTP ' + res.status + '），可以在组件设置里换一个站点地址');
  return String(res.data || '');
}

function mikanImage(path) {
  if (!path) return '';
  path = mikanUnescape(path);
  if (/^https?:/.test(path)) return path;
  // 封面统一要竖版的尺寸
  return mikanBase() + path.split('?')[0] + '?width=400&height=560&format=webp';
}

function mikanItem(id, title, image, extra) {
  var item = {
    id: 'mikan_' + id,
    type: 'link',
    link: 'mikan:' + id,
    mikanId: String(id),
    title: title,
    mediaType: 'tv',
    posterUrl: mikanImage(image)
  };
  for (var k in (extra || {})) item[k] = extra[k];
  return item;
}

// 首页 / 季度页：每个 data-dayofweek 一块，里面是番剧卡片。返回 [{day, items}]
function mikanCoverFlow(html) {
  var blocks = [];
  var parts = html.split(/<div class="sk-bangumi" data-dayofweek="/);
  for (var i = 1; i < parts.length; i++) {
    var part = parts[i];
    var day = parseInt(part, 10);
    var items = [];
    var re = /data-src="([^"]*)"[^>]*data-bangumiid="(\d+)"[\s\S]*?(?:<div class="date-text">([^<]*)<\/div>[\s\S]*?)?class="an-text" title="([^"]*)"/g;
    var m;
    while ((m = re.exec(part))) {
      var date = (m[3] || '').replace('更新', '').trim();
      items.push(mikanItem(m[2], mikanUnescape(m[4]), m[1], {
        updated: date,
        remark: [MIKAN_WEEKDAYS[day] || '', date ? date.slice(5) + ' 更新' : ''].filter(Boolean).join(' · '),
        releaseDate: date.replace(/\//g, '-')
      }));
    }
    blocks.push({ day: day, items: items });
  }
  return blocks;
}

async function mikanSeason(params) {
  params = params || {};
  mikanUse(params);
  var now = mikanSeasonNow();
  var html = await mikanGet('/Home/BangumiCoverFlowByDayOfWeek', {
    year: params.year || now.year,
    seasonStr: params.season || now.season
  });
  var blocks = mikanCoverFlow(html);
  var wanted = params.weekday && params.weekday !== 'all' ? parseInt(params.weekday, 10) : null;
  var out = [];
  blocks.forEach(function (b) {
    if (wanted === null || b.day === wanted) out = out.concat(b.items);
  });
  return out;
}

async function mikanDaily(params) {
  params = params || {};
  mikanUse(params);
  var day = params.weekday && params.weekday !== 'today' ? parseInt(params.weekday, 10) : new Date().getDay();
  var blocks = mikanCoverFlow(await mikanGet('/', null));
  var out = [];
  blocks.forEach(function (b) { if (b.day === day) out = out.concat(b.items); });
  // 最近更新过的排前面
  return out.sort(function (a, b) { return String(b.updated).localeCompare(String(a.updated)); });
}

async function mikanRecent(params) {
  params = params || {};
  mikanUse(params);
  var days = parseInt(params.days, 10) || 0;
  var blocks = mikanCoverFlow(await mikanGet('/', null));
  var seen = {};
  var out = [];
  blocks.forEach(function (b) {
    b.items.forEach(function (it) {
      if (seen[it.id]) return;
      seen[it.id] = true;
      out.push(it);
    });
  });
  if (days > 0) {
    var since = Date.now() - days * 86400000;
    out = out.filter(function (it) {
      var t = Date.parse(String(it.updated).replace(/\//g, '-'));
      return isNaN(t) || t >= since - 86400000;
    });
  }
  return out.sort(function (a, b) { return String(b.updated).localeCompare(String(a.updated)); });
}

async function mikanWeekdayCategories() {
  return MIKAN_WEEKDAYS.map(function (name, i) {
    var now = mikanSeasonNow();
    return { id: 'day_' + i, name: name, params: { year: now.year, season: now.season, weekday: String(i) } };
  });
}

async function mikanSearch(params) {
  mikanUse(params);
  var keyword = String((params && params.keyword) || '').trim();
  if (!keyword) return [];
  return mikanSearchRaw(keyword);
}

async function mikanSearchRaw(keyword) {
  var html = await mikanGet('/Home/Search', { searchstr: keyword });
  var start = html.indexOf('an-ul');
  if (start < 0) return [];
  var end = html.indexOf('</ul>', start);
  var list = html.substring(start, end < 0 ? html.length : end);
  var out = [];
  var re = /href="\/Home\/Bangumi\/(\d+)"[\s\S]*?data-src="([^"]*)"[\s\S]*?class="an-text" title="([^"]*)"/g;
  var m;
  while ((m = re.exec(list))) out.push(mikanItem(m[1], mikanUnescape(m[3]), m[2]));
  return out;
}
