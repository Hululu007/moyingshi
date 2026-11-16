package com.fongmi.quickjs.bean;
/** 移植自星辰 quickjs/src/main/java/com/fongmi/quickjs/bean/Cache.java（32 行）：原文照搬；包名同上保留。Parser 的缓存依赖。详见 docs/miniapp-xingchen-3a-support.md。 */

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class Cache {

    private String pdfhHtml;
    private String pdfaHtml;
    private Document pdfhDoc;
    private Document pdfaDoc;

    public Document getPdfh(String html) {
        updatePdfh(html);
        return pdfhDoc;
    }

    public Document getPdfa(String html) {
        updatePdfa(html);
        return pdfaDoc;
    }

    private void updatePdfh(String html) {
        if (html == null || html.equals(pdfhHtml)) return;
        pdfhDoc = Jsoup.parse(pdfhHtml = html);
    }

    private void updatePdfa(String html) {
        if (html == null || html.equals(pdfaHtml)) return;
        pdfaDoc = Jsoup.parse(pdfaHtml = html);
    }
}
