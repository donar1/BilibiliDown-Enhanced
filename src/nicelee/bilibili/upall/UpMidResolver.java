package nicelee.bilibili.upall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import nicelee.bilibili.API;
import nicelee.bilibili.util.HttpCookies;
import nicelee.bilibili.util.HttpHeaders;
import nicelee.bilibili.util.HttpRequestUtil;

/**
 * 输入解析：UID / 空间主页链接 / UP 主昵称。
 * 昵称搜索走 x/web-interface/wbi/search/type?search_type=bili_user。
 */
public class UpMidResolver {

    private static final Pattern SPACE_URL = Pattern.compile("space\\.bilibili\\.com/(\\d+)");
    private static final Pattern PURE_NUMBER = Pattern.compile("^\\d{1,12}$");

    private final HttpRequestUtil util;

    public UpMidResolver(HttpRequestUtil util) {
        this.util = util == null ? new HttpRequestUtil() : util;
    }

    /** UP 主搜索结果条目 */
    public static class UpUserHit {
        public final long mid;
        public final String uname;
        public final long fans;

        public UpUserHit(long mid, String uname, long fans) {
            this.mid = mid;
            this.uname = uname;
            this.fans = fans;
        }

        @Override
        public String toString() {
            return uname + "（UID " + mid + "，粉丝 " + fans + "）";
        }
    }

    /**
     * 尝试把输入直接解析成 UID；解析不出返回 -1（此时应走昵称搜索）。
     */
    public static long parseMid(String input) {
        if (input == null) {
            return -1;
        }
        String s = input.trim();
        if (PURE_NUMBER.matcher(s).matches()) {
            return Long.parseLong(s);
        }
        Matcher m = SPACE_URL.matcher(s);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return -1;
    }

    /** 按昵称搜索 UP 主，取第一页前若干条 */
    public List<UpUserHit> searchUser(String keyword) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search_type", "bili_user");
        params.put("keyword", keyword);
        params.put("page", "1");
        params.put("page_size", "10");

        String query = WbiSigner.signQuery(params);
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/type?" + query;

        HttpHeaders headers = new HttpHeaders();
        HashMap<String, String> headerMap = headers.getCommonHeaders("api.bilibili.com");
        headerMap.put("Referer", "https://search.bilibili.com/");
        String content = util.getContent(url, headerMap, HttpCookies.getGlobalCookies());

        JSONObject root = new JSONObject(content);
        if (root.optInt("code", -1) != 0) {
            throw new UpApiException(root.optInt("code", -1), root.optString("message", ""));
        }
        List<UpUserHit> hits = new ArrayList<>();
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return hits;
        }
        JSONArray result = data.optJSONArray("result");
        if (result == null) {
            return hits;
        }
        for (int i = 0; i < result.length(); i++) {
            JSONObject o = result.optJSONObject(i);
            if (o == null) {
                continue;
            }
            hits.add(new UpUserHit(o.optLong("mid"), o.optString("uname", ""), o.optLong("fans", 0)));
        }
        return hits;
    }
}
