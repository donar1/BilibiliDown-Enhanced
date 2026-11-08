package nicelee.bilibili.upall;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

import nicelee.bilibili.util.Encrypt;
import nicelee.bilibili.util.HttpCookies;
import nicelee.bilibili.util.HttpHeaders;
import nicelee.bilibili.util.HttpRequestUtil;
import nicelee.bilibili.util.Logger;

/**
 * B 站 wbi 签名（与 nicelee.bilibili.API#encWbi 同一套算法，并已与官方实现逐字节比对一致）。
 *
 * 步骤：
 * 1. GET https://api.bilibili.com/x/web-interface/nav 取 data.wbi_img.img_url / sub_url，
 *    各取文件名（去掉扩展名）得到 imgKey / subKey，拼接成 64 字符；
 * 2. 用固定 64 项置换表取前 32 位得到 mixinKey（约 6 小时轮换，需缓存并在失败时刷新）；
 * 3. 所有参数（含 dm_img_list / dm_img_str / dm_img_inter / dm_cover_img_str 等风控参数）
 *    按 key 排序后拼成 query，值里剔除 !'()*；
 * 4. 末尾追加 wts=秒级时间戳，MD5(query + mixinKey) 即 w_rid；
 * 5. 最终 query = 原参数 + &w_rid=xxx&wts=xxx。
 *
 * 注意：dm_img_* 这一类风控参数必须一起参与签名，否则服务端会判定 w_rid 不合法（412/-352）。
 */
public final class WbiSigner {

    /** 与 nicelee.bilibili.API.MixinArray 完全一致的 64 项置换表（已从运行时反射核对） */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52
    };

    private static final long KEY_TTL_MS = 6L * 60 * 60 * 1000;
    private static volatile String mixinKey;
    private static volatile long keyFetchedAt;

    private WbiSigner() {
    }

    /**
     * 对普通参数签名，返回可直接拼到 URL 的完整 query（不含 '?'）。
     */
    public static String signQuery(Map<String, String> params) {
        Map<String, String> encoded = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                encoded.put(e.getKey(), encode(filterIllegal(e.getValue())));
            }
        }
        return sign(encoded, null);
    }

    /**
     * 普通参数 + 已经拼好的附加参数串（如 API#genDmImgParams 返回的 "&dm_img_list=...&dm_img_str=..."）。
     * 附加参数按原样参与签名，不再重复编码。
     */
    public static String signQuery(Map<String, String> params, String extraQuery) {
        Map<String, String> encoded = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                encoded.put(e.getKey(), encode(filterIllegal(e.getValue())));
            }
        }
        return sign(encoded, extraQuery);
    }

    private static String sign(Map<String, String> encodedParams, String extraQuery) {
        ensureMixinKey();
        long wts = System.currentTimeMillis() / 1000L;

        // 收集 "k=v" 原始片段（附加参数保持原样，不做二次编码）
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> e : encodedParams.entrySet()) {
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        if (extraQuery != null && !extraQuery.isEmpty()) {
            String extra = extraQuery.startsWith("&") ? extraQuery.substring(1) : extraQuery;
            for (String piece : extra.split("&")) {
                if (!piece.isEmpty()) {
                    pairs.add(piece);
                }
            }
        }

        List<String> forSign = new ArrayList<>(pairs);
        Collections.sort(forSign);
        String signBase = String.join("&", forSign) + "&wts=" + wts;
        String wRid = Encrypt.MD5(signBase + mixinKey);

        return String.join("&", pairs) + "&w_rid=" + wRid + "&wts=" + wts;
    }

    /** 强制下次签名时重新拉取密钥（遇到风控类错误后可调用） */
    public static void invalidate() {
        mixinKey = null;
    }

    public static void ensureMixinKey() {
        if (mixinKey != null && System.currentTimeMillis() - keyFetchedAt < KEY_TTL_MS) {
            return;
        }
        synchronized (WbiSigner.class) {
            if (mixinKey != null && System.currentTimeMillis() - keyFetchedAt < KEY_TTL_MS) {
                return;
            }
            try {
                mixinKey = fetchMixinKey();
                keyFetchedAt = System.currentTimeMillis();
                Logger.println("[upall] wbi mixinKey 已更新");
            } catch (Exception e) {
                Logger.println("[upall] 获取 wbi 密钥失败: " + e.getMessage());
                throw new IllegalStateException("获取 wbi 密钥失败", e);
            }
        }
    }

    private static String fetchMixinKey() {
        HttpRequestUtil util = new HttpRequestUtil();
        HttpHeaders headers = new HttpHeaders();
        List<java.net.HttpCookie> cookies = HttpCookies.getGlobalCookies();
        // 注意：不要用 globalCookiesWithFingerprint()，它依赖 API.getFingerprint() 的 UA 上下文，
        // 在未初始化 Global 的调用路径（单测、插件）里会抛异常。nav 匿名即可拿到密钥。
        if (cookies == null) {
            cookies = new ArrayList<>();
        }
        String content = util.getContent("https://api.bilibili.com/x/web-interface/nav",
                headers.getCommonHeaders(), cookies);
        JSONObject obj = new JSONObject(content);
        JSONObject wbiImg = obj.getJSONObject("data").getJSONObject("wbi_img");
        String imgKey = fileNameOf(wbiImg.getString("img_url"));
        String subKey = fileNameOf(wbiImg.getString("sub_url"));
        return mixinKeyOf(imgKey + subKey);
    }

    private static String fileNameOf(String url) {
        int slash = url.lastIndexOf('/');
        int dot = url.indexOf('.', slash + 1);
        return dot > slash ? url.substring(slash + 1, dot) : url.substring(slash + 1);
    }

    /** 按置换表取前 32 位 */
    private static String mixinKeyOf(String rawWbiImg) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(rawWbiImg.charAt(MIXIN_KEY_ENC_TAB[i]));
        }
        return sb.toString();
    }

    /** wbi 要求剔除 !'()* 等字符 */
    private static String filterIllegal(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("!", "").replace("'", "").replace("(", "")
                .replace(")", "").replace("*", "");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
