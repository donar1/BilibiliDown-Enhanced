package nicelee.bilibili.upall;

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import nicelee.bilibili.API;
import nicelee.bilibili.util.HttpCookies;
import nicelee.bilibili.util.HttpHeaders;
import nicelee.bilibili.util.HttpRequestUtil;
import nicelee.bilibili.util.Logger;

/**
 * 投稿列表接口封装：x/space/wbi/arc/search。
 *
 * 说明：现有 URL4UPAllParser 已在调用同一接口，本类只是把它抽成"可分页、可复用、可单测"的
 * 纯 API 层，避免为了拿"播放量/分P数/页码"去反解 VideoInfo。
 */
public class UpSpaceApi {

    public static final String ORDER_PUBDATE = "pubdate";
    public static final String ORDER_CLICK = "click";
    public static final String ORDER_STOW = "stow";

    private static final String API_HOST = "api.bilibili.com";
    private static final String ARC_SEARCH = "https://api.bilibili.com/x/space/wbi/arc/search";

    private final HttpRequestUtil util;

    public UpSpaceApi(HttpRequestUtil util) {
        this.util = util == null ? new HttpRequestUtil() : util;
    }

    /**
     * 拉取一页投稿。
     *
     * @param mid            UP 主 UID
     * @param pageNum        页码，从 1 开始
     * @param pageSize       每页条数，接口上限 50
     * @param order          pubdate / click / stow
     * @param keyword        标题关键词，可为空
     */
    public UpPageResult fetchPage(long mid, int pageNum, int pageSize, String order, String keyword) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mid", String.valueOf(mid));
        params.put("ps", String.valueOf(Math.max(1, Math.min(pageSize, 50))));
        params.put("tid", "0");
        params.put("special_type", "");
        params.put("pn", String.valueOf(Math.max(1, pageNum)));
        params.put("keyword", keyword == null ? "" : keyword);
        params.put("order", order == null || order.isEmpty() ? ORDER_PUBDATE : order);
        params.put("platform", "web");
        params.put("web_location", "1550101");

        // 风控辅助参数：优先复用现有实现；未初始化 UA/指纹的调用路径（单测、插件）下会抛异常，
        // 此时省略。注意它必须一起参与 w_rid 签名，否则服务端会判 412/-352。
        String dm = safeDmParams();
        String query = WbiSigner.signQuery(params, dm);
        String url = ARC_SEARCH + "?" + query;

        String content = util.getContent(url, buildHeaders(mid), buildCookies());
        return parsePage(content, pageNum);
    }

    private UpPageResult parsePage(String content, int requestPageNum) {
        if (content == null || content.trim().isEmpty()) {
            throw new UpApiException(-1, "空响应（可能被风控拦截）");
        }
        JSONObject root = new JSONObject(content);
        int code = root.optInt("code", -1);
        if (code != 0) {
            throw new UpApiException(code, root.optString("message", ""));
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new UpApiException(-1, "响应缺少 data 字段");
        }
        JSONObject page = data.optJSONObject("page");
        int total = page == null ? 0 : page.optInt("count", 0);
        int ps = page == null ? 0 : page.optInt("ps", 0);

        List<UpVideoItem> items = new ArrayList<>();
        JSONObject list = data.optJSONObject("list");
        if (list != null) {
            JSONArray vlist = list.optJSONArray("vlist");
            if (vlist != null) {
                for (int i = 0; i < vlist.length(); i++) {
                    JSONObject raw = vlist.optJSONObject(i);
                    if (raw == null) {
                        continue;
                    }
                    UpVideoItem item = UpVideoItem.fromJson(raw);
                    item.setListPage(requestPageNum);
                    items.add(item);
                }
            }
        }
        Logger.printf("[upall] mid 第 %d 页，返回 %d 条，总计 %d 条\n", requestPageNum, items.size(), total);
        return new UpPageResult(requestPageNum, ps, total, items);
    }

    /**
     * 风控辅助参数 dm_img_*。
     * <p>
     * 【实测结论 2026-11-06】默认关闭。原因：脱离真实浏览器渲染环境时，
     * {@link API#genDmImgParams()} 生成的 dm_cover_img_str 会被截断成非法 base64
     * （长度不足、无补位），拼进 query 后服务端直接返回
     * {@code code=-403 访问权限不足}。同一签名器、同一 Cookie 下去掉该参数即返回 code=0。
     * <p>
     * 需要时可通过 app.config 打开：{@code bilibili.up.fetch.useDmParams = true}
     */
    private String safeDmParams() {
        if (!"true".equalsIgnoreCase(readSetting("bilibili.up.fetch.useDmParams", "false"))) {
            return "";
        }
        try {
            String dm = API.genDmImgParams();
            if (dm == null || dm.trim().isEmpty()) {
                return "";
            }
            // 完整性校验：dm_cover_img_str 必须是 4 的倍数长度且仅含 base64 字符，否则丢弃
            int i = dm.indexOf("dm_cover_img_str=");
            if (i >= 0) {
                String v = dm.substring(i + "dm_cover_img_str=".length());
                int amp = v.indexOf('&');
                if (amp >= 0) {
                    v = v.substring(0, amp);
                }
                if (v.length() % 4 != 0 || !v.matches("[A-Za-z0-9+/=]+")) {
                    Logger.println("[upall] dm_cover_img_str 不完整(len=" + v.length() + ")，已丢弃 dm 参数");
                    return "";
                }
            }
            return dm;
        } catch (Throwable t) {
            Logger.println("[upall] dm_img 参数不可用，已省略: " + t.getMessage());
            return "";
        }
    }

    private static String readSetting(String key, String def) {
        try {
            String v = nicelee.ui.Global.settings.get(key);
            return v == null || v.trim().isEmpty() ? def : v.trim();
        } catch (Throwable t) {
            return def;
        }
    }

    private HashMap<String, String> buildHeaders(long mid) {
        HttpHeaders headers = new HttpHeaders();
        HashMap<String, String> map = headers.getCommonHeaders(API_HOST);
        // 空间投稿接口对 Referer 有校验：用具体 UP 主空间页更稳
        map.put("Referer", "https://space.bilibili.com/" + mid + "/video");
        map.put("Origin", "https://space.bilibili.com");
        // 412 通常就是 UA 缺失/不合法导致：应用内 Global.userAgent 已初始化时不会走到这里
        if (map.get("User-Agent") == null || map.get("User-Agent").trim().isEmpty()) {
            map.put("User-Agent", DEFAULT_UA);
        }
        return map;
    }

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private List<HttpCookie> buildCookies() {
        List<HttpCookie> cookies = HttpCookies.getGlobalCookies();
        if (cookies == null) {
            cookies = new ArrayList<>();
        }
        return cookies;
    }
}
