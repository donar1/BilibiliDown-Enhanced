package nicelee.bilibili.upall;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 CSV 读取：只为了在界面里显示台账，不依赖任何第三方库。
 * 支持双引号包裹、引号内逗号与换行、"" 转义。
 */
final class SimpleCsv {

    private SimpleCsv() {
    }

    static List<String[]> readAll(File f) throws Exception {
        List<String[]> rows = new ArrayList<>();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        try {
            List<String> row;
            while ((row = parseRow(r)) != null) {
                rows.add(row.toArray(new String[0]));
            }
        } finally {
            r.close();
        }
        return rows;
    }

    private static List<String> parseRow(BufferedReader r) throws Exception {
        List<String> cells = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int ch;
        while ((ch = r.read()) != -1) {
            any = true;
            char c = (char) ch;
            if (quoted) {
                if (c == '"') {
                    r.mark(1);
                    int nx = r.read();
                    if (nx == '"') {
                        sb.append('"');
                    } else {
                        quoted = false;
                        if (nx == -1) {
                            cells.add(sb.toString());
                            return cells;
                        }
                        char n = (char) nx;
                        if (n == ',') {
                            cells.add(sb.toString());
                            sb.setLength(0);
                        } else if (n == '\n') {
                            cells.add(sb.toString());
                            return cells;
                        } else if (n == '\r') {
                            r.mark(1);
                            int nn = r.read();
                            if (nn != '\n' && nn != -1) {
                                // 极少见的孤立 \r，忽略
                            }
                            cells.add(sb.toString());
                            return cells;
                        } else {
                            sb.append(n);
                        }
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    cells.add(sb.toString());
                    sb.setLength(0);
                } else if (c == '\n') {
                    cells.add(sb.toString());
                    return cells;
                } else if (c == '\r') {
                    r.mark(1);
                    int nn = r.read();
                    if (nn != '\n' && nn != -1) {
                        // 孤立 \r 当普通字符
                    }
                    cells.add(sb.toString());
                    return cells;
                } else {
                    sb.append(c);
                }
            }
        }
        if (!any) {
            return null;
        }
        cells.add(sb.toString());
        return cells;
    }
}
