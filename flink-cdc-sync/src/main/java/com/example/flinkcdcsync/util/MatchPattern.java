package com.example.flinkcdcsync.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 忽略规则匹配工具：支持三种写法，优先级从高到低：
 * <ol>
 *   <li>精确匹配：规则与名称完全相等；</li>
 *   <li>正则匹配：规则以 <code>re:</code> 开头，其后为正则表达式（<code>re:^tmp_.*</code>）；</li>
 *   <li>通配匹配：规则包含 <code>*</code> 或 <code>?</code>，按 glob 语法转换为正则（<code>log_*</code>）。</li>
 * </ol>
 * 空规则/空名称视为不匹配。
 * <p>
 * 针对"表"级匹配另提供 {@link #matchesTable(List, String, String)}，支持
 * <b>库.表</b> 限定写法（每个库可配置多张表），且库段与表段各自独立支持
 * 精确 / glob / <code>re:</code> 正则：
 * <ul>
 *   <li><code>sales.log_*</code>：sales 库下所有 log_ 前缀表；</li>
 *   <li><code>re:^app_.*.sessions</code>（整体正则）：匹配表名或"库.表"全名；</li>
 *   <li><code>re:^app_.*\..*_tmp$</code>：库段用正则、表段用正则的组合（库.表整体正则）；</li>
 *   <li><code>orders.re:^tmp_.*</code>：orders 库 + 表段正则；</li>
 *   <li><code>log_*</code>：不含点，匹配任意库下的该表（向后兼容）。</li>
 * </ul>
 *
 * @author 50707
 */
public final class MatchPattern {

    private MatchPattern() {
    }

    /** 名称是否被任一规则命中（规则列表为空或 null 时返回 false） */
    public static boolean matchesAny(List<String> patterns, String name) {
        if (patterns == null || name == null) {
            return false;
        }
        for (String p : patterns) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (matchOne(p, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 表级匹配：支持"库.表"限定写法。
     *
     * @param patterns 规则列表（可含 "库.表" 限定，库段/表段各自支持精确/glob/re: 正则）
     * @param db       当前库名（可为 null，此时仅按表名匹配非限定规则）
     * @param table    当前表名
     * @return 是否命中任一规则
     */
    public static boolean matchesTable(List<String> patterns, String db, String table) {
        if (patterns == null || table == null) {
            return false;
        }
        for (String p : patterns) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (matchTableOne(p, db, table)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchTableOne(String pattern, String db, String table) {
        // 整体正则：既尝试匹配表名，也尝试匹配 "库.表" 全名
        if (pattern.startsWith("re:")) {
            if (matchOne(pattern, table)) {
                return true;
            }
            return db != null && matchOne(pattern, db + "." + table);
        }
        int dot = pattern.indexOf('.');
        if (dot >= 0) {
            // 库.表 限定：库段与表段各自匹配（表段可再带 re: 前缀）
            String dbPart = pattern.substring(0, dot);
            String tablePart = pattern.substring(dot + 1);
            if (db == null) {
                return false;
            }
            return matchOne(dbPart, db) && matchOne(tablePart, table);
        }
        // 不含点：按表名匹配任意库
        return matchOne(pattern, table);
    }

    private static boolean matchOne(String pattern, String name) {
        if (pattern.equals(name)) {
            return true;
        }
        if (pattern.startsWith("re:")) {
            try {
                return Pattern.compile(pattern.substring(3)).matcher(name).matches();
            } catch (Exception e) {
                return false;
            }
        }
        if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) {
            String rx = "^" + pattern
                    .replace("\\", "\\\\")
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".") + "$";
            try {
                return Pattern.compile(rx).matcher(name).matches();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
