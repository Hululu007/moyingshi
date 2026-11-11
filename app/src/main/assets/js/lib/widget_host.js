// moyingshi Widget 宿主 shim（CapyPlayer 规范）。
// 以 BieCuiLe 的 widget_host.js 为底本改写：去掉 WebView 的 window/Native 桥，
// 底层走 Java 桥 __widget*（同步调用，跑在 JS 单线程执行器上；超时由桥控制）。
// 与 BieCuiLe 的差异：
// - 没有 window：QuickJS 里没有 window，全局对象直接用 globalThis。
// - 没有 zlibMode/base64Data：QuickJS 没有 atob/Blob/DecompressionStream，内置组件不用它们。
// - 没有 __load/__invoke：Java 侧直接用 Async.run 调模块函数、直接读 WidgetMetadata。
// - storage 前缀在 Java 桥里按组件 id 固定（widget_<id>_），shim 不用传前缀。
// - console 不在这里重写：WidgetHost 已挂 quickjs 的 Console（进 Logcat）。
(function () {
  function parseBody(text) {
    if (typeof text !== 'string') return text;
    var t = text.replace(/^\uFEFF/, '');
    var c = t.trim().charAt(0);
    if (c === '{' || c === '[') {
      try { return JSON.parse(t); } catch (e) {}
    }
    return text;
  }

  function query(params) {
    if (!params) return '';
    return Object.keys(params).filter(function (k) {
      return params[k] !== undefined && params[k] !== null;
    }).map(function (k) {
      return encodeURIComponent(k) + '=' + encodeURIComponent(String(params[k]));
    }).join('&');
  }

  function withQuery(url, params) {
    var qs = query(params);
    if (!qs) return String(url);
    return String(url) + (String(url).indexOf('?') >= 0 ? '&' : '?') + qs;
  }

  function plainHeaders(h) {
    var out = {};
    if (!h) return out;
    if (typeof h.forEach === 'function' && !Array.isArray(h)) {
      h.forEach(function (v, k) { out[k] = String(v); });
      return out;
    }
    Object.keys(h).forEach(function (k) {
      if (h[k] !== undefined && h[k] !== null) out[k] = String(h[k]);
    });
    return out;
  }

  function headerOf(headers, name) {
    var lower = name.toLowerCase();
    for (var k in headers) if (k.toLowerCase() === lower) return headers[k];
    return undefined;
  }

  // 超时单位：毫秒；小值按秒算（与 BieCuiLe 一致）。
  function timeoutMs(t) {
    var n = Number(t || 0);
    if (!n) return 0;
    return n < 1000 ? n * 1000 : n;
  }

  async function request(opts) {
    opts = opts || {};
    var method = String(opts.method || 'GET').toUpperCase();
    var headers = plainHeaders(opts.headers);
    var body = opts.data !== undefined ? opts.data : opts.body;
    if (body !== undefined && body !== null && typeof body !== 'string') {
      var type = String(headerOf(headers, 'content-type') || '');
      if (type.indexOf('x-www-form-urlencoded') >= 0) body = query(body);
      else {
        body = JSON.stringify(body);
        if (!type) headers['Content-Type'] = 'application/json';
      }
    }
    var raw = __widgetHttp(
      method,
      withQuery(opts.url, opts.params),
      JSON.stringify(headers),
      body === undefined || body === null ? null : String(body),
      timeoutMs(opts.timeout),
      opts.allow_redirects !== false
    );
    var r = JSON.parse(raw);
    var data = parseBody(r.body);
    return {
      ok: r.status >= 200 && r.status < 300,
      status: r.status,
      statusCode: r.status,
      data: data,
      headers: r.headers || {}
    };
  }

  var dom = {
    parse: function (html) {
      return __widgetDomParse(String(html || ''));
    },
    select: function (target, selector) {
      var id = typeof target === 'string' ? target : (target && target.__node) || '';
      return JSON.parse(__widgetDomSelect(id, String(selector)));
    },
    selectFirst: function (target, selector) {
      var id = typeof target === 'string' ? target : (target && target.__node) || '';
      return JSON.parse(__widgetDomSelectFirst(id, String(selector)));
    },
    text: function (node) {
      if (node && typeof node.text === 'string') return node.text;
      var id = typeof node === 'string' ? node : (node && node.__node) || '';
      return __widgetDomText(id);
    },
    html: function (node) {
      if (node && typeof node.html === 'string') return node.html;
      var id = typeof node === 'string' ? node : (node && node.__node) || '';
      return __widgetDomHtml(id);
    },
    attr: function (node, name) {
      if (node && node.attributes && node.attributes[name] !== undefined) return node.attributes[name];
      var id = typeof node === 'string' ? node : (node && node.__node) || '';
      return __widgetDomAttr(id, String(name || ''));
    },
    remove: function (id) {
      __widgetDomRemove(String(id || ''));
    }
  };

  globalThis.Widget = {
    http: {
      get: function (url, opts) {
        opts = opts || {};
        return request({ method: 'GET', url: url, headers: opts.headers, params: opts.params, timeout: opts.timeout });
      },
      post: function (url, body, opts) {
        opts = opts || {};
        return request({ method: 'POST', url: url, data: body, headers: opts.headers, params: opts.params, timeout: opts.timeout });
      },
      put: function (url, body, opts) {
        opts = opts || {};
        return request({ method: 'PUT', url: url, data: body, headers: opts.headers, params: opts.params, timeout: opts.timeout });
      },
      delete: function (url, opts) {
        opts = opts || {};
        return request({ method: 'DELETE', url: url, headers: opts.headers, params: opts.params, timeout: opts.timeout });
      },
      request: function (opts) { return request(opts); }
    },
    tmdb: {
      get: async function (path, opts) {
        opts = opts || {};
        var r = JSON.parse(__widgetTmdb(String(path || ''), JSON.stringify(opts.params || {})));
        if (!r.ok) throw new Error(r.error || 'TMDB 请求失败');
        return parseBody(r.body);
      }
    },
    storage: {
      get: function (key, def) {
        var v = __widgetStorageGet(String(key));
        if (v === null || v === undefined) return def === undefined ? null : def;
        try { return JSON.parse(v); } catch (e) { return v; }
      },
      getAsync: function (key, def) { return Promise.resolve(this.get(key, def)); },
      set: function (key, value) {
        __widgetStorageSet(String(key), JSON.stringify(value === undefined ? null : value));
      },
      setAsync: function (key, value) { this.set(key, value); return Promise.resolve(); },
      remove: function (key) { __widgetStorageRemove(String(key)); },
      removeAsync: function (key) { this.remove(key); return Promise.resolve(); },
      clear: function () { __widgetStorageClear(); }
    },
    dom: dom
  };

  globalThis.fetch = async function (input, init) {
    init = init || {};
    var url = typeof input === 'string' ? input : (input && input.url) || String(input);
    var r = await request({ method: init.method || 'GET', url: url, headers: init.headers, data: init.body });
    var text = typeof r.data === 'string' ? r.data : JSON.stringify(r.data);
    return {
      ok: r.ok,
      status: r.status,
      statusText: '',
      url: url,
      headers: {
        get: function (name) {
          var v = headerOf(r.headers, name);
          return Array.isArray(v) ? v.join(', ') : (v === undefined ? null : v);
        }
      },
      json: function () { return Promise.resolve(typeof r.data === 'string' ? JSON.parse(r.data) : r.data); },
      text: function () { return Promise.resolve(text); }
    };
  };
})();
